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
    case TaskStatusUpdate(id: TaskId, status: TaskStatus, `final`: Boolean = false)
    case TaskArtifactUpdate(id: TaskId, artifact: Artifact)
    case TaskMessage(id: TaskId, message: A2AMessage)

    def taskId: TaskId = this match
      case TaskStatusUpdate(id, _, _)   => id
      case TaskArtifactUpdate(id, _)    => id
      case TaskMessage(id, _)           => id

    def isFinal: Boolean = this match
      case TaskStatusUpdate(_, _, f) => f
      case _                         => false

  object StreamEvent:
    given JsonEncoder[StreamEvent] = JsonEncoder[Json].contramap {
      case TaskStatusUpdate(id, status, isFinal) =>
        Json.Obj(
          "kind"   -> Json.Str("status"),
          "id"     -> Json.Str(id.value),
          "status" -> status.toJsonAST.toOption.get,
          "final"  -> Json.Bool(isFinal)
        )
      case TaskArtifactUpdate(id, artifact) =>
        Json.Obj(
          "kind"     -> Json.Str("artifact"),
          "id"       -> Json.Str(id.value),
          "artifact" -> artifact.toJsonAST.toOption.get
        )
      case TaskMessage(id, message) =>
        Json.Obj(
          "kind"    -> Json.Str("message"),
          "id"      -> Json.Str(id.value),
          "message" -> message.toJsonAST.toOption.get
        )
    }

    given JsonDecoder[StreamEvent] = JsonDecoder[Json].mapOrFail { json =>
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      fields.get("kind").flatMap(_.asString).toRight("Missing 'kind' field").flatMap {
        case "status" =>
          for
            id     <- fields.get("id").flatMap(_.asString).map(TaskId(_)).toRight("Missing id")
            status <- fields.get("status").toRight("Missing status").flatMap(_.as[TaskStatus])
            isFinal = fields.get("final").flatMap(_.asBoolean).getOrElse(false)
          yield TaskStatusUpdate(id, status, isFinal)
        case "artifact" =>
          for
            id       <- fields.get("id").flatMap(_.asString).map(TaskId(_)).toRight("Missing id")
            artifact <- fields.get("artifact").toRight("Missing artifact").flatMap(_.as[Artifact])
          yield TaskArtifactUpdate(id, artifact)
        case "message" =>
          for
            id      <- fields.get("id").flatMap(_.asString).map(TaskId(_)).toRight("Missing id")
            message <- fields.get("message").toRight("Missing message").flatMap(_.as[A2AMessage])
          yield TaskMessage(id, message)
        case other => Left(s"Unknown stream event kind: $other")
      }
    }

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

/** Server-Sent Event wrapper for SSE streaming */
final case class SseEvent(
    event: Option[String] = None,
    data: String,
    id: Option[String] = None,
    retry: Option[Int] = None
):
  /** Format as SSE wire format */
  def toWire: String =
    val parts = List(
      event.map(e => s"event: $e"),
      Some(s"data: $data"),
      id.map(i => s"id: $i"),
      retry.map(r => s"retry: $r")
    ).flatten
    parts.mkString("\n") + "\n\n"

object SseEvent:
  /** Create an SSE event from a stream event */
  def fromStreamEvent(event: A2AResponse.StreamEvent): SseEvent =
    val eventType = event match
      case _: A2AResponse.StreamEvent.TaskStatusUpdate   => "status"
      case _: A2AResponse.StreamEvent.TaskArtifactUpdate => "artifact"
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
      var data: List[String] = Nil
      var id: Option[String] = None
      var retry: Option[Int] = None

      lines.foreach { line =>
        if line.startsWith("event:") then event = Some(line.drop(6).trim)
        else if line.startsWith("data:") then data = data :+ line.drop(5).trim
        else if line.startsWith("id:") then id = Some(line.drop(3).trim)
        else if line.startsWith("retry:") then retry = line.drop(6).trim.toIntOption
      }

      if data.nonEmpty then Some(SseEvent(event, data.mkString("\n"), id, retry))
      else None
