package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/** Response types for A2A protocol methods */
object A2AResponse:

  /** Result type for message/send - can be Message or Task */
  enum SendMessageResult:
    case MessageResult(message: A2AMessage)
    case TaskResult(task: A2ATask)

  object SendMessageResult:
    given JsonEncoder[SendMessageResult] = JsonEncoder[Json].contramap {
      case MessageResult(msg) =>
        val base = msg.toJsonAST.toOption.get.asObject.get.toMap
        Json.Obj(zio.Chunk.fromIterable((base + ("kind" -> Json.Str("message"))).toSeq)*)
      case TaskResult(task) =>
        val base = task.toJsonAST.toOption.get.asObject.get.toMap
        Json.Obj(zio.Chunk.fromIterable((base + ("kind" -> Json.Str("task"))).toSeq)*)
    }

    given JsonDecoder[SendMessageResult] = JsonDecoder[Json].mapOrFail { json =>
      json.asObject.flatMap(_.toMap.get("kind").flatMap(_.asString)).toRight("Missing 'kind' field").flatMap {
        case "message" => json.as[A2AMessage].map(MessageResult(_))
        case "task"    => json.as[A2ATask].map(TaskResult(_))
        case other     => Left(s"Unknown result kind: $other")
      }
    }

  /** Stream event for message/stream and tasks/resubscribe */
  enum StreamEvent:
    case TaskSnapshot(task: A2ATask)
    case TaskStatusUpdate(
      id: TaskId,
      contextId: ContextId,
      status: TaskStatus,
      `final`: Boolean = false)
    case TaskArtifactUpdate(
      id: TaskId,
      contextId: ContextId,
      artifact: Artifact,
      append: Boolean = false,
      lastChunk: Boolean = true)
    case TaskMessage(
      id: TaskId,
      contextId: ContextId,
      message: A2AMessage)

    def taskId: TaskId = this match
      case TaskSnapshot(task)                 => task.id
      case TaskStatusUpdate(id, _, _, _)      => id
      case TaskArtifactUpdate(id, _, _, _, _) => id
      case TaskMessage(id, _, _)              => id

    def isFinal: Boolean = this match
      case TaskSnapshot(task)           => task.isTerminal
      case TaskStatusUpdate(_, _, _, f) => f
      case _                            => false
  end StreamEvent

  object StreamEvent:
    private def artifactJsonWithFallbackId(taskId: TaskId, artifactJson: Json): Either[String, Json] =
      artifactJson.asObject.toRight("Artifact must be an object").map { artifactObj =>
        if artifactObj.toMap.contains("artifactId") then artifactJson
        else artifactObj.add("artifactId", Json.Str(taskId.value))
      }

    private def nestedBooleanField(json: Json, field: String): Option[Boolean] =
      json.asObject.flatMap(_.toMap.get(field)).flatMap(_.asBoolean)

    given JsonEncoder[StreamEvent] = JsonEncoder[Json].contramap {
      case TaskSnapshot(task) =>
        val base = task.toJsonAST.toOption.get.asObject.get.toMap
        Json.Obj(zio.Chunk.fromIterable((base + ("kind" -> Json.Str("task"))).toSeq)*)
      case TaskStatusUpdate(id, contextId, status, isFinal) =>
        Json.Obj(
          "kind"      -> Json.Str("status-update"),
          "taskId"    -> Json.Str(id.value),
          "contextId" -> Json.Str(contextId.value),
          "status"    -> status.toJsonAST.toOption.get,
          "final"     -> Json.Bool(isFinal),
        )
      case TaskArtifactUpdate(id, contextId, artifact, append, lastChunk) =>
        Json.Obj(
          "kind"      -> Json.Str("artifact-update"),
          "taskId"    -> Json.Str(id.value),
          "contextId" -> Json.Str(contextId.value),
          "artifact"  -> artifact.toJsonAST.toOption.get,
          "append"    -> Json.Bool(append),
          "lastChunk" -> Json.Bool(lastChunk),
        )
      case TaskMessage(id, contextId, message) =>
        Json.Obj(
          "kind"      -> Json.Str("message"),
          "taskId"    -> Json.Str(id.value),
          "contextId" -> Json.Str(contextId.value),
          "message"   -> message.toJsonAST.toOption.get,
        )
    }

    given JsonDecoder[StreamEvent] = JsonDecoder[Json].mapOrFail { json =>
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      def taskId =
        fields.get("taskId").orElse(fields.get("id")).flatMap(_.asString).map(TaskId(_)).toRight("Missing taskId")
      fields.get("kind").flatMap(_.asString).toRight("Missing 'kind' field").flatMap {
        case "task" =>
          json.as[A2ATask].map(TaskSnapshot(_))
        case "status-update" | "status" =>
          for
            id     <- taskId
            status <- fields.get("status").toRight("Missing status").flatMap(_.as[TaskStatus])
            isFinal   = fields.get("final").flatMap(_.asBoolean).getOrElse(false)
            contextId = fields.get("contextId").flatMap(_.asString).map(ContextId(_)).getOrElse(ContextId(id.value))
          yield TaskStatusUpdate(id, contextId, status, isFinal)
        case "artifact-update" | "artifact" =>
          for
            id           <- taskId
            artifactJson <- fields.get("artifact").toRight("Missing artifact")
            artifact     <- artifactJsonWithFallbackId(id, artifactJson).flatMap(_.as[Artifact])
            append = fields
              .get("append")
              .flatMap(_.asBoolean)
              .orElse(nestedBooleanField(artifactJson, "append"))
              .getOrElse(false)
            lastChunk =
              fields
                .get("lastChunk")
                .flatMap(_.asBoolean)
                .orElse(nestedBooleanField(artifactJson, "lastChunk"))
                .getOrElse(true)
            contextId = fields.get("contextId").flatMap(_.asString).map(ContextId(_)).getOrElse(ContextId(id.value))
          yield TaskArtifactUpdate(id, contextId, artifact, append, lastChunk)
        case "message" =>
          for
            message <- fields.get("message").toRight("Missing message").flatMap(_.as[A2AMessage])
            id = fields
              .get("taskId")
              .orElse(fields.get("id"))
              .flatMap(_.asString)
              .map(TaskId(_))
              .getOrElse(A2AEventIds.taskIdFor(message))
            contextId = fields
              .get("contextId")
              .flatMap(_.asString)
              .map(ContextId(_))
              .getOrElse(A2AEventIds.contextIdFor(message))
          yield TaskMessage(id, contextId, message)
        case other => Left(s"Unknown stream event kind: $other")
      }
    }
  end StreamEvent

  /** Result for tasks/get */
  type TasksGetResult = A2ATask

  /** Result for tasks/cancel */
  type TasksCancelResult = A2ATask

  /** Result for push notification config operations */
  type PushNotificationConfigResult = PushNotificationConfig

  /** Result for push notification config list */
  type PushNotificationConfigListResult = List[PushNotificationConfig]

  /** Result for agent/getAuthenticatedExtendedCard */
  type GetAuthenticatedExtendedCardResult = AgentCard
end A2AResponse

/** Server-Sent Event wrapper for SSE streaming */
final case class SseEvent(
  event: Option[String] = None,
  data: String,
  id: Option[String] = None,
  retry: Option[Int] = None):
  /** Format as SSE wire format */
  def toWire: String =
    val parts = List(
      event.map(e => s"event: $e"),
      Some(s"data: $data"),
      id.map(i => s"id: $i"),
      retry.map(r => s"retry: $r"),
    ).flatten
    parts.mkString("\n") + "\n\n"

object SseEvent:
  /** Create an SSE event from a stream event */
  def fromStreamEvent(event: A2AResponse.StreamEvent): SseEvent =
    val eventType = event match
      case _: A2AResponse.StreamEvent.TaskSnapshot       => "task"
      case _: A2AResponse.StreamEvent.TaskStatusUpdate   => "status-update"
      case _: A2AResponse.StreamEvent.TaskArtifactUpdate => "artifact-update"
      case _: A2AResponse.StreamEvent.TaskMessage        => "message"
    SseEvent(event = Some(eventType), data = event.toJson)

  /** Create an error SSE event */
  def error(err: A2AError): SseEvent =
    SseEvent(event = Some("error"), data = err.toJson)

  /** Create a done SSE event */
  def done: SseEvent =
    SseEvent(event = Some("done"), data = "{}")

  /** Parse an SSE event from wire format */
  def parse(lines: List[String]): Option[SseEvent] =
    if lines.isEmpty then None
    else
      var event: Option[String] = None
      var data: List[String]    = Nil
      var id: Option[String]    = None
      var retry: Option[Int]    = None

      lines.foreach { line =>
        if line.startsWith("event:") then event = Some(line.drop(6).trim)
        else if line.startsWith("data:") then data = data :+ line.drop(5).trim
        else if line.startsWith("id:") then id = Some(line.drop(3).trim)
        else if line.startsWith("retry:") then retry = line.drop(6).trim.toIntOption
      }

      if data.nonEmpty then Some(SseEvent(event, data.mkString("\n"), id, retry))
      else None
end SseEvent
