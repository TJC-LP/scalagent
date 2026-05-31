package com.tjclp.scalagent.a2a

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

class A2AProtoParitySpec extends FunSuite:
  import A2APathRouting.RestRoute

  private final case class ProtoBinding(rpc: String, verb: String, path: String)

  private val specPath: Path =
    sys.env
      .get("A2A_PROTO_SPEC")
      .map(Path.of(_))
      .getOrElse(Path.of(sys.props("user.home"), "git", "a2a", "specification", "a2a.proto"))

  test("JSON-RPC method constants match A2AService RPC names from local a2a proto"):
    withProtoSpec { proto =>
      assertEquals(
        methodConstants,
        rpcNames(proto),
      )
    }

  test("REST router covers A2AService HTTP annotations from local a2a proto"):
    withProtoSpec { proto =>
      val bindings = protoBindings(proto)
      assertEquals(bindings.size, 22)

      bindings.foreach { binding =>
        val samplePath = sampleRoute(binding.path)
        val routed     = A2APathRouting.route(binding.verb.toUpperCase, samplePath)
        assertEquals(
          routed,
          A2APathRouting.RoutedRest(
            pathTenant = if binding.path.startsWith("/{tenant}/") then Some("tenant-a") else None,
            route = Some(expectedRoute(binding.rpc)),
          ),
          s"${binding.verb.toUpperCase} ${binding.path}",
        )
      }
    }

  test("representative shared codecs emit ProtoJSON field names from local a2a proto"):
    withProtoSpec { proto =>
      val expectedByMessage = protoJsonFieldSamples
      expectedByMessage.foreach { case (messageName, actualFields) =>
        assertEquals(
          actualFields,
          protoJsonFields(proto, messageName),
          messageName,
        )
      }
    }

  test("field-name parity samples cover every local a2a proto message"):
    withProtoSpec { proto =>
      assertEquals(
        protoJsonFieldSamples.map(_._1).toSet,
        protoMessageNames(proto),
      )
    }

  test("shared enum codecs match local a2a proto enum values"):
    withProtoSpec { proto =>
      assertEquals(
        taskStateValues.view.mapValues(_._1).toMap,
        protoEnumValues(proto, "TaskState"),
      )
      taskStateValues.foreach { case (name, (_, state)) =>
        assertEquals(jsonString(state), name, s"TaskState encoder for $state")
        assertEquals(s""""$name"""".fromJson[TaskState], Right(state), s"TaskState decoder for $name")
      }

      assertEquals(
        roleValues.view.mapValues(_._1).toMap,
        protoEnumValues(proto, "Role"),
      )
      roleValues.foreach { case (name, (_, role)) =>
        assertEquals(jsonString(role), name, s"Role encoder for $role")
        assertEquals(s""""$name"""".fromJson[A2ARole], Right(role), s"Role decoder for $name")
      }
    }

  private def withProtoSpec(assertions: String => Unit): Unit =
    if Files.exists(specPath) then assertions(Files.readString(specPath, StandardCharsets.UTF_8))
    else fail(s"A2A proto spec checkout not found: $specPath; set A2A_PROTO_SPEC to run this parity test")

  private def methodConstants: Set[String] =
    Set(
      A2AMethod.MessageSend,
      A2AMethod.MessageStream,
      A2AMethod.TasksGet,
      A2AMethod.TasksList,
      A2AMethod.TasksCancel,
      A2AMethod.TasksResubscribe,
      A2AMethod.PushNotificationConfigSet,
      A2AMethod.PushNotificationConfigGet,
      A2AMethod.PushNotificationConfigList,
      A2AMethod.PushNotificationConfigDelete,
      A2AMethod.GetAuthenticatedExtendedCard,
    )

  private def rpcNames(proto: String): Set[String] =
    val Rpc = """^\s*rpc\s+([A-Za-z0-9_]+)\s*\(.*""".r
    serviceBlock(proto).linesIterator.collect { case Rpc(name) => name }.toSet

  private def protoBindings(proto: String): List[ProtoBinding] =
    val Rpc   = """^\s*rpc\s+([A-Za-z0-9_]+)\s*\(.*""".r
    val Route = """^\s*(get|post|delete):\s*"([^"]+)".*""".r
    var currentRpc = Option.empty[String]
    serviceBlock(proto).linesIterator.toList.flatMap {
      case Rpc(name) =>
        currentRpc = Some(name)
        Nil
      case Route(verb, path) =>
        currentRpc.map(ProtoBinding(_, verb, path)).toList
      case _ =>
        Nil
    }

  private def serviceBlock(proto: String): String =
    val start = proto.indexOf("service A2AService")
    val end   = proto.indexOf("// Configuration of a send message request.")
    assert(start >= 0, "service A2AService not found in proto")
    assert(end > start, "A2AService block end marker not found in proto")
    proto.substring(start, end)

  private def messageBlock(proto: String, messageName: String): String =
    val marker = s"message $messageName"
    block(proto, marker)

  private def enumBlock(proto: String, enumName: String): String =
    block(proto, s"enum $enumName")

  private def block(proto: String, marker: String): String =
    val start  = proto.indexOf(marker)
    assert(start >= 0, s"$marker not found in proto")
    val open = proto.indexOf('{', start)
    assert(open >= 0, s"$marker opening brace not found in proto")
    var index = open + 1
    var depth = 1
    while index < proto.length && depth > 0 do
      proto.charAt(index) match
        case '{' => depth += 1
        case '}' => depth -= 1
        case _   => ()
      index += 1
    assert(depth == 0, s"$marker closing brace not found in proto")
    proto.substring(open + 1, index - 1)

  private def protoJsonFields(proto: String, messageName: String): Set[String] =
    val Field =
      """^\s*(?:optional\s+|repeated\s+)?(?:map<[^>]+>|[A-Za-z_][A-Za-z0-9_.]*)\s+([a-z][A-Za-z0-9_]*)\s*=\s*\d+.*;.*$""".r
    messageBlock(proto, messageName).linesIterator.collect { case Field(name) => snakeToLowerCamel(name) }.toSet

  private def protoMessageNames(proto: String): Set[String] =
    val Message = """^message\s+([A-Za-z0-9_]+)\s*\{.*$""".r
    proto.linesIterator.collect { case Message(name) => name }.toSet

  private def protoEnumValues(proto: String, enumName: String): Map[String, Int] =
    val Value = """^\s*([A-Z][A-Z0-9_]*)\s*=\s*(\d+)\s*;.*$""".r
    enumBlock(proto, enumName).linesIterator.collect { case Value(name, number) => name -> number.toInt }.toMap

  private def snakeToLowerCamel(name: String): String =
    name.split("_", -1).toList match
      case Nil          => name
      case head :: tail =>
        head + tail.map(part => part.headOption.fold(part)(_.toUpper.toString + part.drop(1))).mkString

  private def sampleRoute(path: String): String =
    path
      .replace("{tenant}", "tenant-a")
      .replace("pushNotificationConfigs/{id=*}", "pushNotificationConfigs/push-1")
      .replace("{task_id=*}", "task-1")
      .replace("{id=*}", "task-1")

  private def expectedRoute(rpc: String): RestRoute =
    rpc match
      case "SendMessage"                      => RestRoute.MessageSend
      case "SendStreamingMessage"             => RestRoute.MessageStream
      case "GetTask"                          => RestRoute.TaskGet("task-1")
      case "ListTasks"                        => RestRoute.TasksList
      case "CancelTask"                       => RestRoute.TaskCancel("task-1:cancel")
      case "SubscribeToTask"                  => RestRoute.TaskSubscribe("task-1:subscribe")
      case "CreateTaskPushNotificationConfig" => RestRoute.PushConfigCreate("task-1")
      case "GetTaskPushNotificationConfig"    => RestRoute.PushConfigGet("task-1", "push-1")
      case "ListTaskPushNotificationConfigs"  => RestRoute.PushConfigList("task-1")
      case "DeleteTaskPushNotificationConfig" => RestRoute.PushConfigDelete("task-1", "push-1")
      case "GetExtendedAgentCard"             => RestRoute.ExtendedAgentCard
      case other                              => fail(s"unexpected A2AService rpc: $other")

  private def protoJsonFieldSamples: List[(String, Set[String])] =
    val flow = OAuth2Flow(
      authorizationUrl = Some("https://auth.example.test/authorize"),
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
      pkceRequired = true,
    )
    val clientCredentialsFlow = OAuth2Flow(
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val implicitFlow = OAuth2Flow(
      authorizationUrl = Some("https://auth.example.test/authorize"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val passwordFlow = OAuth2Flow(
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val deviceCodeFlow = OAuth2Flow(
      deviceAuthorizationUrl = Some("https://auth.example.test/device"),
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val securityRequirement = SecurityRequirement(Map("bearer" -> List("tasks:read")))
    val extension = AgentExtension(
      uri = "https://example.test/ext",
      description = "Extension",
      required = true,
      params = Some(Json.Obj("enabled" -> Json.Bool(true))),
    )
    val capabilities = AgentCapabilities(
      streaming = true,
      pushNotifications = true,
      extensions = List(extension),
      extendedAgentCard = true,
    )
    val interface = AgentInterface(
      url = "https://agent.example.test/a2a",
      protocolBinding = A2ATransport.JSONRPC,
      tenant = Some("tenant-a"),
      protocolVersion = "1.0",
    )
    val skill = AgentSkill(
      id = "skill-1",
      name = "Skill",
      description = "Skill description",
      tags = List("tag"),
      examples = List("example"),
      inputModes = List("text/plain"),
      outputModes = List("text/plain"),
      securityRequirements = List(securityRequirement),
    )
    val signature = AgentCardSignature(
      `protected` = "protected",
      signature = "signature",
      header = Some(Json.Obj("alg" -> Json.Str("ES256"))),
    )
    val card = AgentCard(
      name = "ParityAgent",
      description = "Parity agent",
      supportedInterfaces = List(interface),
      version = "1.0.0",
      provider = Some(AgentProvider("https://provider.example.test", "Provider")),
      documentationUrl = Some("https://agent.example.test/docs"),
      capabilities = capabilities,
      securitySchemes = Map("bearer" -> SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth")),
      securityRequirements = List(securityRequirement),
      defaultInputModes = List("text/plain"),
      defaultOutputModes = List("text/plain"),
      skills = List(skill),
      signatures = List(signature),
      iconUrl = Some("https://agent.example.test/icon.png"),
    )
    val taskId    = TaskId("task-1")
    val contextId = ContextId("ctx-1")
    val textPart  = Part.Text(
      "hello",
      metadata = Some(Json.Obj("m" -> Json.Str("text"))),
      filename = Some("hello.txt"),
      mediaType = Some("text/plain"),
    )
    val rawPart = Part.File(
      FileContent.Bytes("cmF3", name = Some("raw.bin"), mimeType = Some("application/octet-stream")),
      metadata = Some(Json.Obj("m" -> Json.Str("raw"))),
    )
    val urlPart = Part.File(
      FileContent.Uri("https://files.example.test/file.txt", name = Some("file.txt"), mimeType = Some("text/plain")),
      metadata = Some(Json.Obj("m" -> Json.Str("url"))),
    )
    val dataPart = Part.Data(
      Json.Obj("value" -> Json.Str("data")),
      metadata = Some(Json.Obj("m" -> Json.Str("data"))),
      filename = Some("data.json"),
      mediaType = Some("application/json"),
    )
    val message = A2AMessage(
      role = A2ARole.User,
      parts = List(textPart, rawPart, urlPart, dataPart),
      messageId = MessageId("msg-1"),
      contextId = Some(contextId),
      taskId = Some(taskId),
      referenceTaskIds = List(TaskId("task-ref")),
      metadata = Some(Json.Obj("source" -> Json.Str("parity"))),
      extensions = List(extension.uri),
    )
    val artifact = Artifact(
      artifactId = "artifact-1",
      parts = List(textPart),
      name = Some("Artifact"),
      description = Some("Artifact description"),
      metadata = Some(Json.Obj("kind" -> Json.Str("text"))),
      extensions = List(extension.uri),
    )
    val status = TaskStatus(
      state = TaskState.Working,
      message = Some(message),
      timestamp = Some("2026-05-31T00:00:00Z"),
    )
    val task = A2ATask(
      id = taskId,
      contextId = contextId,
      status = status,
      artifacts = List(artifact),
      history = List(message),
      metadata = Some(Json.Obj("tenant" -> Json.Str("tenant-a"))),
    )
    val pushConfig = TaskPushNotificationConfig(
      tenant = Some("tenant-a"),
      id = Some("push-1"),
      taskId = Some(taskId),
      url = "https://callback.example.test/a2a",
      token = Some("token-1"),
      authentication = Some(AuthenticationInfo("Bearer", "secret")),
    )
    val sendConfig = MessageSendConfiguration(
      acceptedOutputModes = List("text/plain"),
      taskPushNotificationConfig = Some(pushConfig),
      historyLength = Some(3),
      returnImmediately = true,
    )

    List(
      "AgentCard" -> jsonFields(card),
      "AgentProvider" -> jsonFields(AgentProvider("https://provider.example.test", "Provider")),
      "AgentCapabilities" -> jsonFields(capabilities),
      "AgentExtension" -> jsonFields(extension),
      "AgentInterface" -> jsonFields(interface),
      "AgentSkill" -> jsonFields(skill),
      "AgentCardSignature" -> jsonFields(signature),
      "SecurityRequirement" -> jsonFields(securityRequirement),
      "SecurityScheme" -> jsonFieldUnion(
        SecurityScheme.ApiKey("x-api-key", "header", "API key"),
        SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth"),
        SecurityScheme.OAuth2(OAuth2Flows(authorizationCode = Some(flow)), Some("https://auth.example.test/.well-known/oauth"), "OAuth"),
        SecurityScheme.OpenIdConnect("https://auth.example.test/.well-known/openid-configuration", "OIDC"),
        SecurityScheme.MutualTLS("mTLS"),
      ),
      "StringList" -> nestedJsonFields(securityRequirement, "schemes", "bearer"),
      "APIKeySecurityScheme" -> nestedJsonFields(
        SecurityScheme.ApiKey("x-api-key", "header", "API key"),
        "apiKeySecurityScheme",
      ),
      "HTTPAuthSecurityScheme" -> nestedJsonFields(
        SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth"),
        "httpAuthSecurityScheme",
      ),
      "OAuth2SecurityScheme" -> nestedJsonFields(
        SecurityScheme.OAuth2(OAuth2Flows(authorizationCode = Some(flow)), Some("https://auth.example.test/.well-known/oauth"), "OAuth"),
        "oauth2SecurityScheme",
      ),
      "OpenIdConnectSecurityScheme" -> nestedJsonFields(
        SecurityScheme.OpenIdConnect("https://auth.example.test/.well-known/openid-configuration", "OIDC"),
        "openIdConnectSecurityScheme",
      ),
      "MutualTlsSecurityScheme" -> nestedJsonFields(
        SecurityScheme.MutualTLS("mTLS"),
        "mtlsSecurityScheme",
      ),
      "OAuthFlows" -> jsonFieldUnion(
        OAuth2Flows(authorizationCode = Some(flow)),
        OAuth2Flows(clientCredentials = Some(clientCredentialsFlow)),
        OAuth2Flows(implicit_ = Some(implicitFlow)),
        OAuth2Flows(password = Some(passwordFlow)),
        OAuth2Flows(deviceCode = Some(deviceCodeFlow)),
      ),
      "AuthorizationCodeOAuthFlow" -> jsonFields(flow),
      "ClientCredentialsOAuthFlow" -> jsonFields(clientCredentialsFlow),
      "ImplicitOAuthFlow" -> jsonFields(implicitFlow),
      "PasswordOAuthFlow" -> jsonFields(passwordFlow),
      "DeviceCodeOAuthFlow" -> jsonFields(deviceCodeFlow),
      "SendMessageConfiguration" -> jsonFields(sendConfig),
      "AuthenticationInfo" -> jsonFields(AuthenticationInfo("Bearer", "secret")),
      "TaskPushNotificationConfig" -> jsonFields(pushConfig),
      "Message" -> jsonFields(message),
      "Part" -> jsonFieldUnion(textPart, rawPart, urlPart, dataPart),
      "Artifact" -> jsonFields(artifact),
      "TaskStatus" -> jsonFields(status),
      "Task" -> jsonFields(task),
      "TaskStatusUpdateEvent" -> nestedJsonFields(
        A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, status, metadata = Some(Json.Obj("m" -> Json.Str("status")))): A2AResponse.StreamEvent,
        "statusUpdate",
      ),
      "TaskArtifactUpdateEvent" -> nestedJsonFields(
        A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact, append = true, lastChunk = true, metadata = Some(Json.Obj("m" -> Json.Str("artifact")))): A2AResponse.StreamEvent,
        "artifactUpdate",
      ),
      "SendMessageRequest" -> jsonFields(
        A2ARequest.MessageSend(message, configuration = Some(sendConfig), metadata = Some(Json.Obj("m" -> Json.Str("request"))), tenant = Some("tenant-a"))
      ),
      "GetTaskRequest" -> jsonFields(A2ARequest.TasksGet(taskId, historyLength = Some(2), tenant = Some("tenant-a"))),
      "ListTasksRequest" -> jsonFields(
        A2ARequest.TasksList(
          contextId = Some(contextId),
          status = Some(TaskState.Working),
          pageSize = Some(10),
          pageToken = Some("page-1"),
          historyLength = Some(3),
          statusTimestampAfter = Some("2026-05-31T00:00:00Z"),
          includeArtifacts = Some(true),
          tenant = Some("tenant-a"),
        )
      ),
      "ListTasksResponse" -> jsonFields(
        A2AResponse.ListTasksResult(
          tasks = List(task),
          nextPageToken = Some("next-task-page"),
          pageSize = 1,
          totalSize = 1,
        )
      ),
      "CancelTaskRequest" -> jsonFields(A2ARequest.TasksCancel(taskId, metadata = Some(Json.Obj("m" -> Json.Str("cancel"))), tenant = Some("tenant-a"))),
      "GetTaskPushNotificationConfigRequest" -> jsonFields(A2ARequest.PushNotificationConfigGet(taskId, "push-1", tenant = Some("tenant-a"))),
      "DeleteTaskPushNotificationConfigRequest" -> jsonFields(A2ARequest.PushNotificationConfigDelete(taskId, "push-1", tenant = Some("tenant-a"))),
      "SubscribeToTaskRequest" -> jsonFields(A2ARequest.TasksResubscribe(taskId, tenant = Some("tenant-a"))),
      "ListTaskPushNotificationConfigsRequest" -> jsonFields(
        A2ARequest.PushNotificationConfigList(taskId, pageSize = Some(5), pageToken = Some("push-page"), tenant = Some("tenant-a"))
      ),
      "GetExtendedAgentCardRequest" -> jsonFields(A2ARequest.GetAuthenticatedExtendedCard(Some("tenant-a"))),
      "SendMessageResponse" -> jsonFieldUnion(
        A2AResponse.SendMessageResult.TaskResult(task),
        A2AResponse.SendMessageResult.MessageResult(message.copy(role = A2ARole.Agent)),
      ),
      "StreamResponse" -> jsonFieldUnion(
        A2AResponse.StreamEvent.TaskSnapshot(task),
        A2AResponse.StreamEvent.TaskMessage(taskId, contextId, message.copy(role = A2ARole.Agent)),
        A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, status),
        A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact),
      ),
      "ListTaskPushNotificationConfigsResponse" -> jsonFields(
        A2AResponse.PushNotificationConfigListResult(List(pushConfig), nextPageToken = Some("next-push-page"))
      ),
    )

  private def jsonFields[A: JsonEncoder](value: A): Set[String] =
    value.toJsonAST.toOption.map(jsonObjectFields).getOrElse(Set.empty)

  private def jsonFieldUnion[A: JsonEncoder](values: A*): Set[String] =
    values.flatMap(jsonFields[A]).toSet

  private def nestedJsonFields[A: JsonEncoder](value: A, fields: String*): Set[String] =
    fields
      .foldLeft(value.toJsonAST.toOption) { case (json, field) =>
        json.flatMap(_.asObject).flatMap(_.toMap.get(field))
      }
      .map(jsonObjectFields)
      .getOrElse(Set.empty)

  private def jsonObjectFields(json: Json): Set[String] =
    json.asObject.map(_.toMap.keySet).getOrElse(Set.empty)

  private def jsonString[A: JsonEncoder](value: A): String =
    value.toJsonAST.toOption.flatMap(_.asString).getOrElse(fail(s"$value did not encode as a JSON string"))

  private def taskStateValues: Map[String, (Int, TaskState)] =
    Map(
      "TASK_STATE_UNSPECIFIED"  -> (0, TaskState.Unknown),
      "TASK_STATE_SUBMITTED"    -> (1, TaskState.Submitted),
      "TASK_STATE_WORKING"      -> (2, TaskState.Working),
      "TASK_STATE_COMPLETED"    -> (3, TaskState.Completed),
      "TASK_STATE_FAILED"       -> (4, TaskState.Failed),
      "TASK_STATE_CANCELED"     -> (5, TaskState.Canceled),
      "TASK_STATE_INPUT_REQUIRED" -> (6, TaskState.InputRequired),
      "TASK_STATE_REJECTED"     -> (7, TaskState.Rejected),
      "TASK_STATE_AUTH_REQUIRED" -> (8, TaskState.AuthRequired),
    )

  private def roleValues: Map[String, (Int, A2ARole)] =
    Map(
      "ROLE_UNSPECIFIED" -> (0, A2ARole.Unspecified),
      "ROLE_USER"        -> (1, A2ARole.User),
      "ROLE_AGENT"       -> (2, A2ARole.Agent),
    )
end A2AProtoParitySpec
