package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.timers.setTimeout
import zio.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.a2a.facade.*

class A2AInteropSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def asyncIterableOf(values: List[js.Any], failAfterValues: Option[js.Any] = None): js.Dynamic =
    var index = 0
    val iterator = js.Dynamic.literal(
      next = () =>
        if index < values.length then
          val value = values(index)
          index += 1
          js.Promise.resolve(js.Dynamic.literal(done = false, value = value))
        else
          failAfterValues match
            case Some(error) => js.Promise.reject(error)
            case None        => js.Promise.resolve(js.Dynamic.literal(done = true))
    )
    iterator.updateDynamic("return")((_: js.UndefOr[js.Any]) => js.Promise.resolve(js.Dynamic.literal(done = true)))

    val stream = js.Dynamic.literal()
    js.Dynamic.global.Reflect.set(stream, js.Symbol.asyncIterator, () => iterator)
    stream

  private def delayedAsyncIterableOf(values: List[(js.Any, Int)]): js.Dynamic =
    var index = 0
    def delayedStep(done: Boolean, value: js.Any, delayMs: Int): js.Promise[js.Dynamic] =
      new js.Promise[js.Dynamic]((resolve, _) =>
        setTimeout(delayMs) {
          resolve(js.Dynamic.literal(done = done, value = value))
        }
      )

    val iterator = js.Dynamic.literal(
      next = () =>
        if index < values.length then
          val (value, delayMs) = values(index)
          index += 1
          delayedStep(done = false, value = value, delayMs = delayMs)
        else delayedStep(done = true, value = js.undefined, delayMs = 0)
    )
    iterator.updateDynamic("return")((_: js.UndefOr[js.Any]) => js.Promise.resolve(js.Dynamic.literal(done = true)))

    val stream = js.Dynamic.literal()
    js.Dynamic.global.Reflect.set(stream, js.Symbol.asyncIterator, () => iterator)
    stream

  private def readChunk(response: js.Dynamic): js.Promise[String] =
    val reader  = response.body.getReader()
    val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
    reader
      .read()
      .asInstanceOf[js.Promise[js.Dynamic]]
      .`then`[String] { step =>
        if step.done.asInstanceOf[Boolean] then ""
        else decoder.decode(step.value).asInstanceOf[String]
      }

  test("message send configuration includes push notification config"):
    val pushConfig = TaskPushNotificationConfig(
      url = "https://example.com/callback",
      tenant = Some("tenant-a"),
      id = Some("cfg-1"),
      taskId = Some(TaskId("task-1")),
      token = Some("secret")
    )

    val config = JsBuilders.messageSendConfiguration(
      acceptedOutputModes = Some(List("text/plain")),
      blocking = Some(true),
      returnImmediately = Some(false),
      historyLength = Some(3),
      pushNotificationConfig = Some(A2AConverters.toJs(pushConfig)),
      taskPushNotificationConfig = Some(A2AConverters.toJs(pushConfig)),
    )

    assertEquals(config.returnImmediately.toOption, Some(false))
    assertEquals(config.pushNotificationConfig.toOption.map(_.url), Some(pushConfig.url))
    assertEquals(config.pushNotificationConfig.toOption.flatMap(_.id.toOption), pushConfig.id)
    assertEquals(config.taskPushNotificationConfig.toOption.flatMap(_.taskId.toOption), Some("task-1"))

  test("message send default normalizer follows execution mode only when configuration is omitted"):
    val missingConfiguration =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "SendMessage",
        |  "params": {"message": {}},
        |  "id": 1
        |}""".stripMargin
    val explicitReturnImmediately =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "SendMessage",
        |  "params": {
        |    "message": {},
        |    "configuration": {"returnImmediately": false}
        |  },
        |  "id": 2
        |}""".stripMargin

    val normalizedDefault =
      js.JSON.parse(A2AJsonRpcRequests.withDefaultMessageSendExecutionMode(missingConfiguration, ExecutionMode.Default))
        .asInstanceOf[js.Dynamic]
        .params
        .configuration
    val normalizedAsync =
      js.JSON.parse(A2AJsonRpcRequests.withDefaultMessageSendExecutionMode(missingConfiguration, ExecutionMode.Asynchronous))
        .asInstanceOf[js.Dynamic]
        .params
        .configuration
    val normalizedExplicit =
      js.JSON.parse(A2AJsonRpcRequests.withDefaultMessageSendExecutionMode(explicitReturnImmediately, ExecutionMode.Asynchronous))
        .asInstanceOf[js.Dynamic]
        .params
        .configuration

    assertEquals(normalizedDefault.blocking.asInstanceOf[Boolean], true)
    assertEquals(normalizedDefault.returnImmediately.asInstanceOf[Boolean], false)
    assertEquals(normalizedAsync.blocking.asInstanceOf[Boolean], false)
    assertEquals(normalizedAsync.returnImmediately.asInstanceOf[Boolean], true)
    assertEquals(normalizedExplicit.blocking.asInstanceOf[Boolean], true)
    assertEquals(normalizedExplicit.returnImmediately.asInstanceOf[Boolean], false)

  test("delete push notification params use task id under id field"):
    val params = JsBuilders.deletePushNotificationConfigParams("task-123", "cfg-456").asInstanceOf[js.Dynamic]

    assertEquals(params.id.asInstanceOf[String], "task-123")
    assertEquals(params.pushNotificationConfigId.asInstanceOf[String], "cfg-456")
    assert(js.isUndefined(params.selectDynamic("taskId")))

  test("get push notification params include optional config id"):
    val defaultParams = JsBuilders.getPushNotificationConfigParams("task-123").asInstanceOf[js.Dynamic]
    val specificParams = JsBuilders.getPushNotificationConfigParams("task-123", Some("cfg-456")).asInstanceOf[js.Dynamic]

    assertEquals(defaultParams.id.asInstanceOf[String], "task-123")
    assert(js.isUndefined(defaultParams.selectDynamic("pushNotificationConfigId")))
    assertEquals(specificParams.id.asInstanceOf[String], "task-123")
    assertEquals(specificParams.pushNotificationConfigId.asInstanceOf[String], "cfg-456")

  test("push notification wrapper results are unwrapped before conversion"):
    val wrapped = js.Dynamic.literal(
      taskId = "task-123",
      pushNotificationConfig = js.Dynamic.literal(
        url = "https://example.com/callback",
        id = "cfg-1",
        token = "secret"
      )
    )

    val parsed = A2AConverters.toScalaPushNotificationConfigResult(wrapped)

    assertEquals(
      parsed,
      TaskPushNotificationConfig(
        url = "https://example.com/callback",
        id = Some("cfg-1"),
        taskId = Some(TaskId("task-123")),
        token = Some("secret")
      )
    )
    assertEquals(A2AConverters.toScalaPushNotificationConfigResults(js.Array(wrapped)), List(parsed))

  test("push notification configs preserve v1 taskId and tenant through JS facades"):
    val config = TaskPushNotificationConfig(
      url = "https://example.com/callback",
      tenant = Some("tenant-a"),
      id = Some("cfg-1"),
      taskId = Some(TaskId("task-123")),
      token = Some("secret"),
      authentication = Some(AuthenticationInfo("Bearer", "token")),
    )

    val jsConfig = A2AConverters.toJs(config).asInstanceOf[js.Dynamic]
    val decoded  = A2AConverters.toScala(jsConfig.asInstanceOf[JsPushNotificationConfig])

    assertEquals(jsConfig.tenant.asInstanceOf[String], "tenant-a")
    assertEquals(jsConfig.taskId.asInstanceOf[String], "task-123")
    assertEquals(decoded, config)

  test("missing data parts decode to Json.Null instead of throwing"):
    val dataPart = js.Dynamic.literal(kind = "data").asInstanceOf[JsPart]

    assertEquals(A2AConverters.toScalaPart(dataPart), Part.Data(Json.Null))

  test("data parts preserve arbitrary JSON through JS facades"):
    val part = Part.Data(
      Json.Obj(
        "kind" -> Json.Str("tool_use"),
        "input" -> Json.Obj(
          "path"  -> Json.Str("/tmp/test.txt"),
          "flags" -> Json.Arr(Json.Str("a"), Json.Str("b"))
        )
      ),
      filename = Some("tool-use.json"),
      mediaType = Some("application/vnd.example.tool+json")
    )

    assertEquals(A2AConverters.toScalaPart(A2AConverters.toJsPart(part)), part)

  test("messages preserve data parts through JS facades"):
    val message = A2AMessage(
      role = A2ARole.Agent,
      parts = List(
        Part.Text("Calling Read"),
        Part.Data(Json.Obj("kind" -> Json.Str("tool_use"), "name" -> Json.Str("Read")))
      ),
      contextId = Some(ContextId("ctx-data")),
      taskId = Some(TaskId("task-data")),
    )

    assertEquals(A2AConverters.toScala(A2AConverters.toJs(message)), message)

  test("messages preserve unspecified proto role through JS facades"):
    val message = A2AMessage(
      role = A2ARole.Unspecified,
      parts = List(Part.Text("role not set")),
      messageId = MessageId("msg-unspecified-role"),
    )

    assertEquals(A2AConverters.toScala(A2AConverters.toJs(message)), message)

  test("JS converters use shared task-state wire aliases"):
    val status = js.Dynamic
      .literal(state = "TASK_STATE_AUTH_REQUIRED")
      .asInstanceOf[JsTaskStatus]
    val encoded = A2AConverters.toJs(TaskStatus(TaskState.InputRequired)).asInstanceOf[js.Dynamic]

    assertEquals(A2AConverters.toScala(status).state, TaskState.AuthRequired)
    assertEquals(encoded.state.asInstanceOf[String], "input-required")

  test("legacy file parts preserve top-level name and mime type"):
    val json =
      """{
        |  "role": "user",
        |  "parts": [
        |    {
        |      "kind": "file",
        |      "name": "report.pdf",
        |      "mimeType": "application/pdf",
        |      "file": {
        |        "uri": "https://example.com/report.pdf"
        |      }
        |    }
        |  ],
        |  "messageId": "msg-legacy"
        |}""".stripMargin

    assertEquals(
      json.fromJson[A2AMessage],
      Right(
        A2AMessage(
          role = A2ARole.User,
          parts = List(
            Part.File(
              FileContent.Uri(
                uri = "https://example.com/report.pdf",
                name = Some("report.pdf"),
                mimeType = Some("application/pdf")
              )
            )
          ),
          messageId = MessageId("msg-legacy")
        )
      )
    )

  test("OAuth2 security schemes round-trip through JS facades"):
    val flowCases = List(
      OAuth2Flows(
        authorizationCode = Some(
          OAuth2Flow(
            authorizationUrl = Some("https://auth.example.com/authorize"),
            tokenUrl = Some("https://auth.example.com/token"),
            refreshUrl = Some("https://auth.example.com/refresh"),
            scopes = Map("tasks:read" -> "Read tasks"),
            pkceRequired = true
          )
        )
      ),
      OAuth2Flows(
        clientCredentials = Some(
          OAuth2Flow(
            tokenUrl = Some("https://auth.example.com/client-token"),
            scopes = Map("tasks:write" -> "Write tasks")
          )
        )
      ),
      OAuth2Flows(
        implicit_ = Some(
          OAuth2Flow(
            authorizationUrl = Some("https://auth.example.com/implicit"),
            scopes = Map("profile" -> "Profile access")
          )
        )
      ),
      OAuth2Flows(
        password = Some(
          OAuth2Flow(
            tokenUrl = Some("https://auth.example.com/password-token"),
            refreshUrl = Some("https://auth.example.com/password-refresh"),
            scopes = Map("offline_access" -> "Offline access")
          )
        )
      ),
      OAuth2Flows(
        deviceCode = Some(
          OAuth2Flow(
            deviceAuthorizationUrl = Some("https://auth.example.com/device"),
            tokenUrl = Some("https://auth.example.com/device-token"),
            scopes = Map("device" -> "Device flow")
          )
        )
      )
    )

    flowCases.foreach { flows =>
      val card = AgentCard.minimal("agent", "Test agent", "https://agent.example.com").copy(
        securitySchemes = Map("oauth" -> SecurityScheme.OAuth2(flows))
      )

      val roundTripped = A2AConverters.toScala(A2AConverters.toJs(card))

      assertEquals(roundTripped.securitySchemes, card.securitySchemes)
    }

  test("security schemes preserve v1 oneof wrappers and descriptions through JS facades"):
    val oauthFlow = js.Dynamic.literal(
      authorizationUrl = "https://auth.example.com/authorize",
      tokenUrl = "https://auth.example.com/token",
      scopes = js.Dictionary("tasks:read" -> "Read tasks"),
      pkceRequired = true,
    )
    val oauthFlows = js.Dynamic.literal(authorizationCode = oauthFlow)
    val jsCard = js.Dynamic
      .literal(
        name = "agent",
        description = "Test agent",
        version = "1.0.0",
        capabilities = js.Dynamic.literal(streaming = true),
        supportedInterfaces = js.Array(
          js.Dynamic.literal(
            url = "https://agent.example.com/a2a",
            protocolBinding = "JSONRPC",
            protocolVersion = "1.0",
          )
        ),
        defaultInputModes = js.Array("text/plain"),
        defaultOutputModes = js.Array("text/plain"),
        skills = js.Array(),
        security = js.Array(
          js.Dynamic.literal(
            schemes = js.Dynamic.literal(
              api = js.Dynamic.literal(list = js.Array("tasks:read"))
            )
          )
        ),
        securitySchemes = js.Dynamic.literal(
          api = js.Dynamic.literal(
            apiKeySecurityScheme = js.Dynamic.literal(
              name = "x-api-key",
              location = "header",
              description = "API key auth",
            )
          ),
          bearer = js.Dynamic.literal(
            httpAuthSecurityScheme = js.Dynamic.literal(
              scheme = "Bearer",
              bearerFormat = "JWT",
              description = "Bearer auth",
            )
          ),
          oauth = js.Dynamic.literal(
            oauth2SecurityScheme = js.Dynamic.literal(
              flows = oauthFlows,
              oauth2MetadataUrl = "https://auth.example.com/.well-known/oauth-authorization-server",
              description = "OAuth auth",
            )
          ),
          oidc = js.Dynamic.literal(
            openIdConnectSecurityScheme = js.Dynamic.literal(
              openIdConnectUrl = "https://accounts.example.com/.well-known/openid-configuration",
              description = "OIDC auth",
            )
          ),
          mtls = js.Dynamic.literal(
            mtlsSecurityScheme = js.Dynamic.literal(description = "mTLS auth")
          ),
        ),
      )
      .asInstanceOf[JsAgentCard]

    val decoded = A2AConverters.toScala(jsCard)

    assertEquals(decoded.securityRequirements, List(SecurityRequirement(Map("api" -> List("tasks:read")))))
    assertEquals(decoded.securitySchemes("api"), SecurityScheme.ApiKey("x-api-key", "header", "API key auth"))
    assertEquals(decoded.securitySchemes("bearer"), SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth"))
    assertEquals(
      decoded.securitySchemes("oidc"),
      SecurityScheme.OpenIdConnect(
        "https://accounts.example.com/.well-known/openid-configuration",
        "OIDC auth",
      ),
    )
    assertEquals(decoded.securitySchemes("mtls"), SecurityScheme.MutualTLS("mTLS auth"))
    assertEquals(
      decoded.securitySchemes("oauth"),
      SecurityScheme.OAuth2(
        OAuth2Flows(
          authorizationCode = Some(
            OAuth2Flow(
              authorizationUrl = Some("https://auth.example.com/authorize"),
              tokenUrl = Some("https://auth.example.com/token"),
              scopes = Map("tasks:read" -> "Read tasks"),
              pkceRequired = true,
            )
          )
        ),
        oauth2MetadataUrl = Some("https://auth.example.com/.well-known/oauth-authorization-server"),
        description = "OAuth auth",
      ),
    )

  test("security scheme descriptions survive OpenAPI-style JS facade round-trips"):
    val card = AgentCard.minimal("agent", "Test agent", "https://agent.example.com").copy(
      securitySchemes = Map(
        "api"    -> SecurityScheme.ApiKey("x-api-key", "header", "API key auth"),
        "bearer" -> SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth"),
        "mtls"   -> SecurityScheme.MutualTLS("mTLS auth"),
      )
    )

    val jsCard       = A2AConverters.toJs(card).asInstanceOf[js.Dynamic]
    val schemes      = jsCard.securitySchemes.asInstanceOf[js.Dynamic]
    val apiScheme    = schemes.api.asInstanceOf[js.Dynamic]
    val bearerScheme = schemes.bearer.asInstanceOf[js.Dynamic]
    val mtlsScheme   = schemes.mtls.asInstanceOf[js.Dynamic]
    val roundTripped = A2AConverters.toScala(jsCard.asInstanceOf[JsAgentCard])

    assertEquals(apiScheme.selectDynamic("type").asInstanceOf[String], "apiKey")
    assertEquals(apiScheme.description.asInstanceOf[String], "API key auth")
    assertEquals(bearerScheme.description.asInstanceOf[String], "Bearer auth")
    assertEquals(mtlsScheme.description.asInstanceOf[String], "mTLS auth")
    assertEquals(roundTripped.securitySchemes, card.securitySchemes)

  test("AgentCard converters preserve v1 supportedInterfaces with custom protocol bindings"):
    val customBinding = "https://example.test/bindings/websocket/v1"
    val card = AgentCard.minimal("agent", "Test agent", "https://agent.example.com").copy(
      supportedInterfaces = List(
        AgentInterface(
          url = "https://agent.example.com/ws",
          protocolBinding = A2ATransport.Custom(customBinding),
          tenant = Some("tenant-a"),
          protocolVersion = "1.0",
        )
      )
    )

    val jsCard             = A2AConverters.toJs(card).asInstanceOf[js.Dynamic]
    val supportedInterface = jsCard.supportedInterfaces.asInstanceOf[js.Array[js.Dynamic]].head
    val roundTripped       = A2AConverters.toScala(jsCard.asInstanceOf[JsAgentCard])

    assertEquals(supportedInterface.protocolBinding.asInstanceOf[String], customBinding)
    assertEquals(supportedInterface.protocolVersion.asInstanceOf[String], "1.0")
    assertEquals(supportedInterface.tenant.asInstanceOf[String], "tenant-a")
    assertEquals(roundTripped.supportedInterfaces, card.supportedInterfaces)

  test("AgentCard converters read v1 supportedInterfaces and extended card capability without legacy url"):
    val customBinding = "https://example.test/bindings/websocket/v1"
    val jsCard = js.Dynamic
      .literal(
        name = "agent",
        description = "Test agent",
        version = "1.0.0",
        capabilities = js.Dynamic.literal(streaming = true, extendedAgentCard = true),
        supportedInterfaces = js.Array(
          js.Dynamic.literal(
            url = "https://agent.example.com/ws",
            protocolBinding = customBinding,
            protocolVersion = "1.0",
            tenant = "tenant-a",
          )
        ),
        defaultInputModes = js.Array("text/plain"),
        defaultOutputModes = js.Array("text/plain"),
        skills = js.Array(),
      )
      .asInstanceOf[JsAgentCard]

    val decoded = A2AConverters.toScala(jsCard)

    assertEquals(decoded.capabilities.extendedAgentCard, true)
    assertEquals(
      decoded.supportedInterfaces,
      List(
        AgentInterface(
          url = "https://agent.example.com/ws",
          protocolBinding = A2ATransport.Custom(customBinding),
          tenant = Some("tenant-a"),
          protocolVersion = "1.0",
        )
      ),
    )

  test("AgentCard converters treat omitted streaming capability as unsupported"):
    val capabilitiesOmitted = js.Dynamic
      .literal(
        name = "agent",
        description = "Test agent",
        version = "1.0.0",
        supportedInterfaces = js.Array(
          js.Dynamic.literal(
            url = "https://agent.example.com/a2a",
            protocolBinding = "JSONRPC",
            protocolVersion = "1.0",
          )
        ),
        defaultInputModes = js.Array("text/plain"),
        defaultOutputModes = js.Array("text/plain"),
        skills = js.Array(),
      )
      .asInstanceOf[JsAgentCard]
    val streamingOmitted = js.Dynamic
      .literal(
        name = "agent",
        description = "Test agent",
        version = "1.0.0",
        capabilities = js.Dynamic.literal(),
        supportedInterfaces = js.Array(
          js.Dynamic.literal(
            url = "https://agent.example.com/a2a",
            protocolBinding = "JSONRPC",
            protocolVersion = "1.0",
          )
        ),
        defaultInputModes = js.Array("text/plain"),
        defaultOutputModes = js.Array("text/plain"),
        skills = js.Array(),
      )
      .asInstanceOf[JsAgentCard]

    val decodedMissingCapabilities = A2AConverters.toScala(capabilitiesOmitted)
    val decodedMissingStreaming    = A2AConverters.toScala(streamingOmitted)

    assertEquals(decodedMissingCapabilities.capabilities.streaming, false)
    assertEquals(decodedMissingStreaming.capabilities.streaming, false)
    assertEquals(decodedMissingStreaming.capabilities.pushNotifications, false)
    assertEquals(decodedMissingStreaming.capabilities.extendedAgentCard, false)

  test("stream parser preserves full task snapshots"):
    val contextId = ContextId("ctx-1")
    val task = A2ATask(
      id = TaskId("task-1"),
      contextId = contextId,
      status = TaskStatus.completed(A2AMessage.agentText("done", Some(contextId))),
      artifacts = List(Artifact(artifactId = "artifact-1", parts = List(Part.Text("42")), name = Some("answer"))),
      history = List(
        A2AMessage.userText("hello", Some(contextId)),
        A2AMessage.agentText("done", Some(contextId))
      ),
      metadata = Some(Json.Obj("source" -> Json.Str("sdk")))
    )

    runTask(A2AStreamEventParser.parse(A2AConverters.toJs(task))).map { parsed =>
      assertEquals(parsed, A2AResponse.StreamEvent.TaskSnapshot(task))
      assertEquals(parsed.taskId, task.id)
      assertEquals(parsed.isFinal, true)
    }

  test("stream parser rejects unknown event kinds"):
    runTask(A2AStreamEventParser.parse(js.Dynamic.literal(kind = "future-event")).either).map { result =>
      result match
        case Left(error) =>
          assert(error.getMessage.contains("A2A stream event"))
          assert(error.getMessage.contains("future-event"))
        case Right(value) => fail(s"Expected parser failure, got $value")
    }

  test("fallback ids are stable for message-only responses"):
    val response = A2AMessage.agentText("done").copy(messageId = MessageId("msg-42"))
    val request = A2AMessage.userText("hello")

    val wrappedTask = A2AStreamEventParser.taskFromMessage(request, response)
    assertEquals(wrappedTask.id, TaskId("msg-42"))
    assertEquals(wrappedTask.contextId, ContextId("msg-42"))

    runTask(A2AStreamEventParser.parse(A2AConverters.toJs(response))).map {
      case A2AResponse.StreamEvent.TaskMessage(taskId, contextId, message) =>
        assertEquals(taskId, TaskId("msg-42"))
        assertEquals(contextId, ContextId("msg-42"))
        assertEquals(message.messageId, MessageId("msg-42"))
      case other =>
        fail(s"Expected task message event, got $other")
    }

  test("stream event decoder derives stable ids from embedded messages"):
    val message = A2AMessage.agentText("done").copy(messageId = MessageId("msg-99"))
    val json = s"""{"kind":"message","message":${message.toJson}}"""

    assertEquals(
      json.fromJson[A2AResponse.StreamEvent],
      Right(
        A2AResponse.StreamEvent.TaskMessage(
          TaskId("msg-99"),
          ContextId("msg-99"),
          message
        )
      )
    )

  test("task snapshot events round-trip through JSON"):
    val task = A2ATask(
      id = TaskId("task-2"),
      contextId = ContextId("ctx-2"),
      status = TaskStatus.submitted
    )
    val event: A2AResponse.StreamEvent = A2AResponse.StreamEvent.TaskSnapshot(task)

    assertEquals(event.toJson.fromJson[A2AResponse.StreamEvent], Right(event))

  test("legacy artifact stream events decode append semantics and fallback artifact id"):
    val json =
      """{
        |  "kind": "artifact",
        |  "taskId": "task-legacy",
        |  "artifact": {
        |    "parts": [
        |      {
        |        "kind": "text",
        |        "text": "partial chunk"
        |      }
        |    ],
        |    "append": true,
        |    "lastChunk": false,
        |    "name": "partial"
        |  }
        |}""".stripMargin

    assertEquals(
      json.fromJson[A2AResponse.StreamEvent],
      Right(
        A2AResponse.StreamEvent.TaskArtifactUpdate(
          id = TaskId("task-legacy"),
          contextId = ContextId("task-legacy"),
          artifact = Artifact(
            artifactId = "task-legacy",
            parts = List(Part.Text("partial chunk")),
            name = Some("partial")
          ),
          append = true,
          lastChunk = false
        )
      )
    )

  test("stream parser accepts legacy artifact events"):
    val event = js.Dynamic.literal(
      kind = "artifact",
      taskId = "task-legacy",
      artifact = js.Dynamic.literal(
        parts = js.Array(js.Dynamic.literal(kind = "text", text = "partial chunk")),
        append = true,
        lastChunk = false,
        name = "partial"
      )
    )

    runTask(A2AStreamEventParser.parse(event)).map { parsed =>
      assertEquals(
        parsed,
        A2AResponse.StreamEvent.TaskArtifactUpdate(
          id = TaskId("task-legacy"),
          contextId = ContextId("task-legacy"),
          artifact = Artifact(
            artifactId = "task-legacy",
            parts = List(Part.Text("partial chunk")),
            name = Some("partial")
          ),
          append = true,
          lastChunk = false
        )
      )
    }

  test("Bun JSON-RPC responses stream async iterables as SSE"):
    val rpcEvent = js.Dynamic.literal(
      jsonrpc = "2.0",
      id = 1,
      result = js.Dynamic.literal(kind = "status-update", taskId = "task-1")
    )
    val response = BunJsonRpcResponses.fromResult(asyncIterableOf(List(rpcEvent)), 1)

    assertEquals(response.headers.get("Content-Type").asInstanceOf[String], "text/event-stream")
    response.text().asInstanceOf[js.Promise[String]].toFuture.map { body =>
      assertEquals(body, s"data: ${js.JSON.stringify(rpcEvent)}\n\n")
    }

  test("Bun JSON-RPC responses emit delayed SSE chunks incrementally"):
    val first = js.Dynamic.literal(
      jsonrpc = "2.0",
      id = 1,
      result = js.Dynamic.literal(kind = "status-update", taskId = "task-1", step = "one")
    )
    val second = js.Dynamic.literal(
      jsonrpc = "2.0",
      id = 1,
      result = js.Dynamic.literal(kind = "status-update", taskId = "task-1", step = "two")
    )
    val response = BunJsonRpcResponses.fromResult(
      delayedAsyncIterableOf(List(first -> 20, second -> 200)),
      1,
    )

    readChunk(response).toFuture.map { chunk =>
      assert(chunk.contains(""""step":"one""""))
      assert(!chunk.contains(""""step":"two""""))
    }

  test("Bun JSON-RPC responses emit keep-alive comments while async iterable is idle"):
    val event = js.Dynamic.literal(
      jsonrpc = "2.0",
      id = 1,
      result = js.Dynamic.literal(kind = "status-update", taskId = "task-1", step = "after-idle")
    )
    val response = BunJsonRpcResponses.fromResult(
      delayedAsyncIterableOf(List(event -> 35)),
      1,
      keepAliveMillis = 10,
    )

    readChunk(response).toFuture.map { chunk =>
      assertEquals(chunk, A2AHttpBinding.sseKeepAliveFrame)
    }
