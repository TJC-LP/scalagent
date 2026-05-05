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
    val pushConfig = PushNotificationConfig(
      url = "https://example.com/callback",
      id = Some("cfg-1"),
      token = Some("secret")
    )

    val config = JsBuilders.messageSendConfiguration(
      acceptedOutputModes = Some(List("text/plain")),
      blocking = Some(true),
      historyLength = Some(3),
      pushNotificationConfig = Some(A2AConverters.toJs(pushConfig))
    )

    assertEquals(config.pushNotificationConfig.toOption.map(_.url), Some(pushConfig.url))
    assertEquals(config.pushNotificationConfig.toOption.flatMap(_.id.toOption), pushConfig.id)

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
      PushNotificationConfig(
        url = "https://example.com/callback",
        id = Some("cfg-1"),
        token = Some("secret")
      )
    )
    assertEquals(A2AConverters.toScalaPushNotificationConfigResults(js.Array(wrapped)), List(parsed))

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
      )
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
    val flows = OAuth2Flows(
      authorizationCode = Some(
        OAuth2Flow(
          authorizationUrl = Some("https://auth.example.com/authorize"),
          tokenUrl = Some("https://auth.example.com/token"),
          refreshUrl = Some("https://auth.example.com/refresh"),
          scopes = Map("tasks:read" -> "Read tasks")
        )
      ),
      clientCredentials = Some(
        OAuth2Flow(
          tokenUrl = Some("https://auth.example.com/client-token"),
          scopes = Map("tasks:write" -> "Write tasks")
        )
      ),
      implicit_ = Some(
        OAuth2Flow(
          authorizationUrl = Some("https://auth.example.com/implicit"),
          scopes = Map("profile" -> "Profile access")
        )
      ),
      password = Some(
        OAuth2Flow(
          tokenUrl = Some("https://auth.example.com/password-token"),
          refreshUrl = Some("https://auth.example.com/password-refresh"),
          scopes = Map("offline_access" -> "Offline access")
        )
      )
    )
    val card = AgentCard.minimal("agent", "Test agent", "https://agent.example.com").copy(
      securitySchemes = Map("oauth" -> SecurityScheme.OAuth2(flows))
    )

    val roundTripped = A2AConverters.toScala(A2AConverters.toJs(card))

    assertEquals(roundTripped.securitySchemes, card.securitySchemes)

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
        case Left(error)  => assert(error.getMessage.contains("Unknown A2A stream event kind: future-event"))
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
