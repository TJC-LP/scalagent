package com.tjclp.scalagent.a2a

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

class A2ACodecSpec extends FunSuite:
  private def roundTrip[A: JsonEncoder: JsonDecoder](value: A): Unit =
    assertEquals(value.toJson.fromJson[A], Right(value))

  private val contextId = ContextId("ctx-codec")
  private val taskId    = TaskId("task-codec")
  private val message = A2AMessage(
    role = A2ARole.User,
    parts = List(
      Part.Text("hello"),
      Part.File(FileContent.Bytes("cmF3", name = Some("raw.bin"), mimeType = Some("application/octet-stream"))),
      Part.File(FileContent.Uri("https://example.test/file.txt", name = Some("file.txt"), mimeType = Some("text/plain"))),
      Part.Data(Json.Obj("key" -> Json.Str("value"))),
    ),
    messageId = MessageId("msg-codec"),
    contextId = Some(contextId),
    taskId = Some(taskId),
    referenceTaskIds = List(TaskId("task-ref")),
    metadata = Some(Json.Obj("source" -> Json.Str("test"))),
    extensions = List("https://example.test/ext"),
  )
  private val artifact = Artifact(
    artifactId = "artifact-codec",
    parts = List(Part.Text("artifact")),
    name = Some("artifact.txt"),
    description = Some("Artifact"),
    extensions = List("https://example.test/artifact-ext"),
    metadata = Some(Json.Obj("kind" -> Json.Str("text"))),
  )
  private val task = A2ATask(
    id = taskId,
    contextId = contextId,
    status = TaskStatus(TaskState.Completed, Some(message.copy(role = A2ARole.Agent)), Some("2026-05-04T00:00:00Z")),
    artifacts = List(artifact),
    history = List(message),
    metadata = Some(Json.Obj("tenant" -> Json.Str("codec"))),
  )
  private val pushConfig = TaskPushNotificationConfig(
    tenant = Some("tenant-codec"),
    id = Some("push-codec"),
    taskId = Some(taskId),
    url = "https://callback.example.test/a2a",
    token = Some("token"),
    authentication = Some(AuthenticationInfo("Bearer", "secret")),
  )

  test("agent card, security schemes, and security requirements round-trip"):
    val card = AgentCard(
      name = "CodecAgent",
      description = "Codec test agent",
      supportedInterfaces = List(AgentInterface.jsonRpc("https://agent.example.test/a2a"), AgentInterface.rest("https://agent.example.test")),
      provider = Some(AgentProvider(url = "https://provider.example.test", organization = "Provider")),
      capabilities = AgentCapabilities(
        streaming = true,
        pushNotifications = true,
        extensions = List(AgentExtension("https://example.test/ext", required = true)),
        extendedAgentCard = true,
      ),
      securitySchemes = Map("bearer" -> SecurityScheme.Http("Bearer", bearerFormat = Some("JWT"))),
      securityRequirements = List(SecurityRequirement(Map("bearer" -> List("tasks:read")))),
      skills = List(AgentSkill("codec", "Codec", "Codec skill", tags = List("test"))),
      signatures = List(AgentCardSignature("protected", "signature")),
      iconUrl = Some("https://agent.example.test/icon.png"),
    )

    roundTrip(card)
    assert(card.toJson.contains("supportedInterfaces"))
    assert(card.toJson.contains("protocolVersion"))

  test("message parts use v1 text/raw/url/data fields"):
    roundTrip(message)
    val json = message.toJson
    assert(json.contains(""""text":"hello""""))
    assert(json.contains(""""raw":"cmF3""""))
    assert(json.contains(""""url":"https://example.test/file.txt""""))
    assert(json.contains(""""data":{"key":"value"}"""))

  test("task status and state enum values use proto names"):
    roundTrip(task)
    assert(task.toJson.contains("TASK_STATE_COMPLETED"))

  test("request and response model groups round-trip"):
    roundTrip(A2ARequest.MessageSend(message, Some(MessageSendConfiguration(taskPushNotificationConfig = Some(pushConfig), historyLength = Some(1)))))
    roundTrip(A2ARequest.TasksGet(taskId, historyLength = Some(1), tenant = Some("tenant-codec")))
    roundTrip(A2ARequest.TasksList(contextId = Some(contextId), status = Some(TaskState.Completed), pageSize = Some(10), pageToken = Some("10")))
    roundTrip(A2ARequest.TasksCancel(taskId))
    roundTrip(A2ARequest.TasksResubscribe(taskId))
    roundTrip(A2ARequest.PushNotificationConfigGet(taskId, "push-codec"))
    roundTrip(A2ARequest.PushNotificationConfigList(taskId, pageSize = Some(10), pageToken = Some("next")))
    roundTrip(A2ARequest.PushNotificationConfigDelete(taskId, "push-codec"))
    roundTrip(A2AResponse.SendMessageResult.TaskResult(task))
    roundTrip(A2AResponse.ListTasksResult(List(task), nextPageToken = Some("next"), pageSize = 1, totalSize = 1))
    roundTrip(A2AResponse.PushNotificationConfigListResult(List(pushConfig), nextPageToken = Some("next")))

  test("stream response variants round-trip"):
    roundTrip[A2AResponse.StreamResponse](A2AResponse.StreamEvent.TaskSnapshot(task))
    roundTrip[A2AResponse.StreamResponse](
      A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, task.status, metadata = Some(Json.Obj("m" -> Json.Str("v"))))
    )
    roundTrip[A2AResponse.StreamResponse](A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact, append = true, lastChunk = false))
    roundTrip[A2AResponse.StreamResponse](A2AResponse.StreamEvent.TaskMessage(taskId, contextId, message))

  test("snake_case aliases decode for interop"):
    val decoded =
      """{
        |  "message": {
        |    "role": "ROLE_USER",
        |    "parts": [{"text": "hello"}],
        |    "message_id": "msg-snake",
        |    "context_id": "ctx-snake",
        |    "task_id": "task-snake",
        |    "reference_task_ids": ["task-ref"]
        |  },
        |  "configuration": {
        |    "accepted_output_modes": ["text/plain"],
        |    "return_immediately": true,
        |    "history_length": 1,
        |    "task_push_notification_config": {
        |      "url": "https://callback.example.test",
        |      "task_id": "task-snake"
        |    }
        |  }
        |}""".stripMargin.fromJson[A2ARequest.MessageSend]

    assert(decoded.exists(_.configuration.exists(_.returnImmediately)))
    assertEquals(decoded.toOption.flatMap(_.message.taskId), Some(TaskId("task-snake")))
end A2ACodecSpec
