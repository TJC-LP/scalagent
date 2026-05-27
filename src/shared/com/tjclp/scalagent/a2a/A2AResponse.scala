package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/** Response types for A2A v1 protocol methods. */
object A2AResponse:

  /** Result type for SendMessage. */
  enum SendMessageResult:
    case MessageResult(message: A2AMessage)
    case TaskResult(task: A2ATask)

  object SendMessageResult:
    given JsonEncoder[SendMessageResult] = JsonEncoder[Json].contramap {
      case MessageResult(message) => Json.Obj("message" -> message.toJsonAST.toOption.get)
      case TaskResult(task)       => Json.Obj("task" -> task.toJsonAST.toOption.get)
    }

    given JsonDecoder[SendMessageResult] = JsonDecoder[Json].mapOrFail { json =>
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      fields.get("message") match
        case Some(message) => message.as[A2AMessage].map(MessageResult(_))
        case None          =>
          fields.get("task") match
            case Some(task) => task.as[A2ATask].map(TaskResult(_))
            case None       =>
              fields.get("kind").flatMap(_.asString) match
                case Some("message") => json.as[A2AMessage].map(MessageResult(_))
                case Some("task")    => json.as[A2ATask].map(TaskResult(_))
                case _               => Left("SendMessageResult must contain message or task")
    }
  end SendMessageResult
  type SendMessageResponse = SendMessageResult

  /** Result for ListTasks. */
  final case class ListTasksResult(
    tasks: List[A2ATask],
    nextPageToken: Option[String] = None,
    pageSize: Int = 0,
    totalSize: Int = 0)
  object ListTasksResult:
    given JsonEncoder[ListTasksResult] = JsonEncoder[Json].contramap { result =>
      Json.Obj(
        "tasks"         -> Json.Arr(result.tasks.map(_.toJsonAST.toOption.get)*),
        "nextPageToken" -> Json.Str(result.nextPageToken.getOrElse("")),
        "pageSize"      -> Json.Num(java.math.BigDecimal.valueOf(result.pageSize.toLong)),
        "totalSize"     -> Json.Num(java.math.BigDecimal.valueOf(result.totalSize.toLong)),
      )
    }

    given JsonDecoder[ListTasksResult] = JsonDecoder[Json].mapOrFail { json =>
      json.asObject.toRight("ListTasksResult must be an object").flatMap { obj =>
        val fields = obj.toMap
        for tasks <- fields
            .get("tasks")
            .flatMap(_.asArray)
            .map(values =>
              values.toList.map(_.as[A2ATask]).foldRight[Either[String, List[A2ATask]]](Right(Nil)) {
                case (Right(task), Right(tasks)) => Right(task :: tasks)
                case (Left(error), _)            => Left(error)
                case (_, Left(error))            => Left(error)
              }
            )
            .getOrElse(Right(Nil))
        yield ListTasksResult(
          tasks = tasks,
          nextPageToken = fields
            .get("nextPageToken")
            .orElse(fields.get("next_page_token"))
            .flatMap(_.asString)
            .filter(_.nonEmpty),
          pageSize = fields
            .get("pageSize")
            .orElse(fields.get("page_size"))
            .flatMap(_.asNumber)
            .map(_.value.intValue)
            .getOrElse(0),
          totalSize = fields
            .get("totalSize")
            .orElse(fields.get("total_size"))
            .flatMap(_.asNumber)
            .map(_.value.intValue)
            .getOrElse(0),
        )
      }
    }
  end ListTasksResult
  type ListTasksResponse = ListTasksResult

  /** Result for ListTaskPushNotificationConfigs. */
  final case class PushNotificationConfigListResult(
    configs: List[TaskPushNotificationConfig],
    nextPageToken: Option[String] = None)
  object PushNotificationConfigListResult:
    given JsonEncoder[PushNotificationConfigListResult] = JsonEncoder[Json].contramap { result =>
      Json.Obj(
        "configs"       -> Json.Arr(result.configs.map(_.toJsonAST.toOption.get)*),
        "nextPageToken" -> Json.Str(result.nextPageToken.getOrElse("")),
      )
    }

    given JsonDecoder[PushNotificationConfigListResult] = JsonDecoder[Json].mapOrFail { json =>
      json.asObject.toRight("PushNotificationConfigListResult must be an object").flatMap { obj =>
        val fields = obj.toMap
        for configs <- fields
            .get("configs")
            .flatMap(_.asArray)
            .map(values =>
              values.toList
                .map(_.as[TaskPushNotificationConfig])
                .foldRight[Either[String, List[TaskPushNotificationConfig]]](Right(Nil)) {
                  case (Right(config), Right(configs)) => Right(config :: configs)
                  case (Left(error), _)                => Left(error)
                  case (_, Left(error))                => Left(error)
                }
            )
            .getOrElse(Right(Nil))
        yield PushNotificationConfigListResult(
          configs = configs,
          nextPageToken = fields
            .get("nextPageToken")
            .orElse(fields.get("next_page_token"))
            .flatMap(_.asString)
            .filter(_.nonEmpty),
        )
      }
    }
  end PushNotificationConfigListResult
  type ListTaskPushNotificationConfigsResponse = PushNotificationConfigListResult

  /** Stream event for SendStreamingMessage and SubscribeToTask. */
  enum StreamEvent:
    case TaskSnapshot(task: A2ATask)
    case TaskStatusUpdate(
      id: TaskId,
      contextId: ContextId,
      status: TaskStatus,
      `final`: Boolean = false,
      metadata: Option[Json] = None)
    case TaskArtifactUpdate(
      id: TaskId,
      contextId: ContextId,
      artifact: Artifact,
      append: Boolean = false,
      lastChunk: Boolean = true,
      metadata: Option[Json] = None)
    case TaskMessage(
      id: TaskId,
      contextId: ContextId,
      message: A2AMessage)

    def taskId: TaskId = this match
      case TaskSnapshot(task)                    => task.id
      case TaskStatusUpdate(id, _, _, _, _)      => id
      case TaskArtifactUpdate(id, _, _, _, _, _) => id
      case TaskMessage(id, _, _)                 => id

    def isFinal: Boolean = this match
      case TaskSnapshot(task) =>
        task.status.state.isTerminal || task.status.state == TaskState.InputRequired || task.status.state == TaskState.AuthRequired
      case TaskStatusUpdate(_, _, status, explicit, _) =>
        explicit || status.state.isTerminal || status.state == TaskState.InputRequired || status.state == TaskState.AuthRequired
      case _ => false
  end StreamEvent

  object StreamEvent:
    private def artifactJsonWithFallbackId(taskId: TaskId, artifactJson: Json): Either[String, Json] =
      artifactJson.asObject.toRight("Artifact must be an object").map { artifactObj =>
        if artifactObj.toMap.contains("artifactId") || artifactObj.toMap.contains("artifact_id") then artifactJson
        else artifactObj.add("artifactId", Json.Str(taskId.value))
      }

    private def nestedBooleanField(json: Json, field: String): Option[Boolean] =
      json.asObject.flatMap(_.toMap.get(field)).flatMap(_.asBoolean)

    given JsonEncoder[StreamEvent] = JsonEncoder[Json].contramap {
      case TaskSnapshot(task) =>
        Json.Obj("task" -> task.toJsonAST.toOption.get)
      case TaskStatusUpdate(id, contextId, status, isFinal, metadata) =>
        var update = Json.Obj(
          "taskId"    -> Json.Str(id.value),
          "contextId" -> Json.Str(contextId.value),
          "status"    -> status.toJsonAST.toOption.get,
        )
        if isFinal then update = update.add("final", Json.Bool(true))
        metadata.foreach(value => update = update.add("metadata", value))
        Json.Obj("statusUpdate" -> update)
      case TaskArtifactUpdate(id, contextId, artifact, append, lastChunk, metadata) =>
        var update = Json.Obj(
          "taskId"    -> Json.Str(id.value),
          "contextId" -> Json.Str(contextId.value),
          "artifact"  -> artifact.toJsonAST.toOption.get,
          "append"    -> Json.Bool(append),
          "lastChunk" -> Json.Bool(lastChunk),
        )
        metadata.foreach(value => update = update.add("metadata", value))
        Json.Obj("artifactUpdate" -> update)
      case TaskMessage(id, contextId, message) =>
        val messageWithIds = message.copy(
          taskId = message.taskId.orElse(Some(id)),
          contextId = message.contextId.orElse(Some(contextId)),
        )
        Json.Obj("message" -> messageWithIds.toJsonAST.toOption.get)
    }

    given JsonDecoder[StreamEvent] = JsonDecoder[Json].mapOrFail { json =>
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      fields.get("task") match
        case Some(taskJson) =>
          taskJson.as[A2ATask].map(TaskSnapshot(_))
        case None =>
          fields.get("message") match
            case Some(messageJson) =>
              messageJson.as[A2AMessage].map { message =>
                TaskMessage(A2AEventIds.taskIdFor(message), A2AEventIds.contextIdFor(message), message)
              }
            case None =>
              fields.get("statusUpdate").orElse(fields.get("status_update")) match
                case Some(updateJson) =>
                  decodeStatusUpdate(updateJson)
                case None =>
                  fields.get("artifactUpdate").orElse(fields.get("artifact_update")) match
                    case Some(updateJson) => decodeArtifactUpdate(updateJson, legacyDefaultLastChunk = false)
                    case None             => decodeLegacy(json, fields)
    }

    private def decodeStatusUpdate(json: Json): Either[String, StreamEvent] =
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      for
        taskId <- fields
          .get("taskId")
          .orElse(fields.get("task_id"))
          .flatMap(_.asString)
          .map(TaskId(_))
          .toRight("Missing taskId")
        status <- fields.get("status").toRight("Missing status").flatMap(_.as[TaskStatus])
        contextId = fields
          .get("contextId")
          .orElse(fields.get("context_id"))
          .flatMap(_.asString)
          .map(ContextId(_))
          .getOrElse(ContextId(taskId.value))
        isFinal  = fields.get("final").flatMap(_.asBoolean).getOrElse(false)
        metadata = fields.get("metadata")
      yield TaskStatusUpdate(taskId, contextId, status, isFinal, metadata)

    private def decodeArtifactUpdate(json: Json, legacyDefaultLastChunk: Boolean): Either[String, StreamEvent] =
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      for
        taskId <- fields
          .get("taskId")
          .orElse(fields.get("task_id"))
          .flatMap(_.asString)
          .map(TaskId(_))
          .toRight("Missing taskId")
        artifactJson <- fields.get("artifact").toRight("Missing artifact")
        artifact     <- artifactJsonWithFallbackId(taskId, artifactJson).flatMap(_.as[Artifact])
        append = fields
          .get("append")
          .flatMap(_.asBoolean)
          .orElse(nestedBooleanField(artifactJson, "append"))
          .getOrElse(false)
        lastChunk = fields
          .get("lastChunk")
          .orElse(fields.get("last_chunk"))
          .flatMap(_.asBoolean)
          .orElse(nestedBooleanField(artifactJson, "lastChunk"))
          .orElse(nestedBooleanField(artifactJson, "last_chunk"))
          .getOrElse(legacyDefaultLastChunk)
        contextId = fields
          .get("contextId")
          .orElse(fields.get("context_id"))
          .flatMap(_.asString)
          .map(ContextId(_))
          .getOrElse(ContextId(taskId.value))
        metadata = fields.get("metadata")
      yield TaskArtifactUpdate(taskId, contextId, artifact, append, lastChunk, metadata)
      end for
    end decodeArtifactUpdate

    private def decodeLegacy(json: Json, fields: Map[String, Json]): Either[String, StreamEvent] =
      def taskId =
        fields.get("taskId").orElse(fields.get("id")).flatMap(_.asString).map(TaskId(_)).toRight("Missing taskId")
      fields.get("kind").flatMap(_.asString).toRight("Missing stream response variant").flatMap {
        case "task" =>
          json.as[A2ATask].map(TaskSnapshot(_))
        case "status-update" | "status" =>
          decodeStatusUpdate(json)
        case "artifact-update" | "artifact" =>
          decodeArtifactUpdate(json, legacyDefaultLastChunk = true)
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
        case other =>
          Left(s"Unknown stream event kind: $other")
      }
    end decodeLegacy
  end StreamEvent
  type StreamResponse = StreamEvent

  /** Result aliases. */
  type TasksGetResult                     = A2ATask
  type TasksCancelResult                  = A2ATask
  type PushNotificationConfigResult       = TaskPushNotificationConfig
  type GetAuthenticatedExtendedCardResult = AgentCard
end A2AResponse

/** Server-Sent Event wrapper for SSE streaming. */
final case class SseEvent(
  event: Option[String] = None,
  data: String,
  id: Option[String] = None,
  retry: Option[Int] = None):
  /** Format as SSE wire format. */
  def toWire: String =
    val parts = List(
      event.map(e => s"event: $e"),
      Some(s"data: $data"),
      id.map(i => s"id: $i"),
      retry.map(r => s"retry: $r"),
    ).flatten
    parts.mkString("\n") + "\n\n"

object SseEvent:
  /** Create an SSE event from a stream event. */
  def fromStreamEvent(event: A2AResponse.StreamEvent): SseEvent =
    SseEvent(data = event.toJson)

  /** Create an error SSE event. */
  def error(err: A2AError): SseEvent =
    SseEvent(event = Some("error"), data = err.toJson)

  /** Create a done SSE event. */
  def done: SseEvent =
    SseEvent(event = Some("done"), data = "{}")

  /** Parse an SSE event from wire format. */
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
