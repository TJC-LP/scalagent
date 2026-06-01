package com.tjclp.scalagent.a2a

import scala.util.Try

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
      val fields   = json.asObject.map(_.toMap).getOrElse(Map.empty)
      val payloads = List(
        A2AJson.nonNullNamedField(fields, "message"),
        A2AJson.nonNullNamedField(fields, "task"),
      ).flatten
      payloads match
        case ("message", messageJson) :: Nil =>
          messageJson.as[A2AMessage].map(MessageResult(_))
        case ("task", taskJson) :: Nil =>
          taskJson.as[A2ATask].map(TaskResult(_))
        case Nil =>
          fields.get("kind").flatMap(_.asString) match
            case Some("message") => json.as[A2AMessage].map(MessageResult(_))
            case Some("task")    => json.as[A2ATask].map(TaskResult(_))
            case _               => Left("SendMessageResult must contain exactly one of message or task")
        case _ =>
          Left("SendMessageResult must contain exactly one of message or task")
    }
  end SendMessageResult
  type SendMessageResponse = SendMessageResult

  /** Result for ListTasks. */
  final case class ListTasksResult(
    tasks: List[A2ATask],
    nextPageToken: Option[String] = None,
    pageSize: Int = 0,
    totalSize: Int = 0,
    includeArtifacts: Boolean = false)
  object ListTasksResult:
    private def decodeList[A: JsonDecoder](fields: Map[String, Json], name: String): Either[String, List[A]] =
      fields
        .get(name)
        .toRight(s"Missing $name")
        .flatMap(_.asArray.toRight(s"$name must be an array"))
        .flatMap(values =>
          values.toList.map(_.as[A]).foldRight[Either[String, List[A]]](Right(Nil)) {
            case (Right(value), Right(values)) => Right(value :: values)
            case (Left(error), _)              => Left(error)
            case (_, Left(error))              => Left(error)
          }
        )

    private def requiredString(
      fields: Map[String, Json],
      name: String,
      aliases: String*
    ): Either[String, String] =
      (name +: aliases).iterator.flatMap(fields.get).nextOption() match
        case Some(value) => value.asString.toRight(s"$name must be a string")
        case None        => Left(s"Missing $name")

    private def requiredInt(
      fields: Map[String, Json],
      name: String,
      aliases: String*
    ): Either[String, Int] =
      (name +: aliases).iterator.flatMap(fields.get).nextOption() match
        case Some(value) =>
          value.asNumber
            .toRight(s"$name must be an int32")
            .flatMap(number => Try(number.value.intValueExact).toEither.left.map(_ => s"$name must be an int32"))
        case None =>
          Left(s"Missing $name")

    given JsonEncoder[ListTasksResult] = JsonEncoder[Json].contramap { result =>
      Json.Obj(
        "tasks" -> Json.Arr(
          result.tasks.map(A2ATask.toJsonObject(_, includeEmptyArtifacts = result.includeArtifacts))*
        ),
        "nextPageToken" -> Json.Str(result.nextPageToken.getOrElse("")),
        "pageSize"      -> Json.Num(java.math.BigDecimal.valueOf(result.pageSize.toLong)),
        "totalSize"     -> Json.Num(java.math.BigDecimal.valueOf(result.totalSize.toLong)),
      )
    }

    given JsonDecoder[ListTasksResult] = JsonDecoder[Json].mapOrFail { json =>
      json.asObject.toRight("ListTasksResult must be an object").flatMap { obj =>
        val fields = obj.toMap
        for
          tasks         <- decodeList[A2ATask](fields, "tasks")
          nextPageToken <- requiredString(fields, "nextPageToken", "next_page_token")
          pageSize      <- requiredInt(fields, "pageSize", "page_size")
          totalSize     <- requiredInt(fields, "totalSize", "total_size")
        yield ListTasksResult(
          tasks = tasks,
          nextPageToken = Option(nextPageToken).filter(_.nonEmpty),
          pageSize = pageSize,
          totalSize = totalSize,
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
    private def optionalString(
      fields: Map[String, Json],
      name: String,
      aliases: String*
    ): Either[String, Option[String]] =
      (name +: aliases).iterator.flatMap(fields.get).nextOption() match
        case Some(Json.Null) => Right(None)
        case Some(value)     => value.asString.map(Some(_)).toRight(s"$name must be a string")
        case None            => Right(None)

    private def optionalList[A: JsonDecoder](fields: Map[String, Json], name: String): Either[String, List[A]] =
      fields.get(name) match
        case Some(Json.Null) => Right(Nil)
        case Some(value)     =>
          value.asArray.toRight(s"$name must be an array").flatMap { values =>
            values.toList.map(_.as[A]).foldRight[Either[String, List[A]]](Right(Nil)) {
              case (Right(value), Right(values)) => Right(value :: values)
              case (Left(error), _)              => Left(error)
              case (_, Left(error))              => Left(error)
            }
          }
        case None => Right(Nil)

    given JsonEncoder[PushNotificationConfigListResult] = JsonEncoder[Json].contramap { result =>
      Json.Obj(
        "configs"       -> Json.Arr(result.configs.map(_.toJsonAST.toOption.get)*),
        "nextPageToken" -> Json.Str(result.nextPageToken.getOrElse("")),
      )
    }

    given JsonDecoder[PushNotificationConfigListResult] = JsonDecoder[Json].mapOrFail { json =>
      json.asObject.toRight("PushNotificationConfigListResult must be an object").flatMap { obj =>
        val fields = obj.toMap
        for
          configs       <- optionalList[TaskPushNotificationConfig](fields, "configs")
          nextPageToken <- optionalString(fields, "nextPageToken", "next_page_token")
        yield PushNotificationConfigListResult(
          configs = configs,
          nextPageToken = nextPageToken.filter(_.nonEmpty),
        )
      }
    }
  end PushNotificationConfigListResult
  type ListTaskPushNotificationConfigsResponse = PushNotificationConfigListResult

  /** Stream event for SendStreamingMessage and SubscribeToTask. */
  enum StreamEvent:
    case TaskSnapshot(task: A2ATask)
    case Message(message: A2AMessage)
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
      lastChunk: Boolean = false,
      metadata: Option[Json] = None)
    case TaskMessage(
      id: TaskId,
      contextId: ContextId,
      message: A2AMessage)

    def taskId: TaskId = this match
      case TaskSnapshot(task)                    => task.id
      case Message(message)                      => A2AEventIds.taskIdFor(message)
      case TaskStatusUpdate(id, _, _, _, _)      => id
      case TaskArtifactUpdate(id, _, _, _, _, _) => id
      case TaskMessage(id, _, _)                 => id

    def isFinal: Boolean = this match
      case TaskSnapshot(task) =>
        task.isStreamEnding
      case Message(_) =>
        true
      case TaskStatusUpdate(_, _, status, explicit, _) =>
        explicit || status.state.isStreamEnding
      case _ => false

    def closesStream: Boolean = this match
      case TaskSnapshot(task) =>
        task.isStreamEnding
      case Message(_) =>
        true
      case TaskStatusUpdate(_, _, status, explicit, _) =>
        explicit || status.state.isStreamEnding
      case _ => false
  end StreamEvent

  object StreamEvent:
    private def artifactJsonWithFallbackId(taskId: TaskId, artifactJson: Json): Either[String, Json] =
      artifactJson.asObject.toRight("Artifact must be an object").map { artifactObj =>
        if artifactObj.toMap.contains("artifactId") || artifactObj.toMap.contains("artifact_id") then artifactJson
        else artifactObj.add("artifactId", Json.Str(taskId.value))
      }

    private def nestedBooleanField(
      json: Json,
      field: String,
      aliases: String*
    ): Either[String, Option[Boolean]] =
      json.asObject match
        case Some(obj) => A2AJson.optionalBoolean(obj.toMap, field, aliases*)
        case None      => Right(None)

    private def requiredNonEmptyString(
      fields: Map[String, Json],
      name: String,
      aliases: String*
    ): Either[String, String] =
      A2AJson.field(fields, (name +: aliases)*) match
        case Some(value) =>
          value.asString.toRight(s"$name must be a string").flatMap {
            case value if value.nonEmpty => Right(value)
            case _                       => Left(s"Missing $name")
          }
        case None =>
          Left(s"Missing $name")

    given JsonEncoder[StreamEvent] = JsonEncoder[Json].contramap {
      case TaskSnapshot(task) =>
        Json.Obj("task" -> task.toJsonAST.toOption.get)
      case Message(message) =>
        Json.Obj("message" -> message.toJsonAST.toOption.get)
      case TaskStatusUpdate(id, contextId, status, _, metadata) =>
        var update = Json.Obj(
          "taskId"    -> Json.Str(id.value),
          "contextId" -> Json.Str(contextId.value),
          "status"    -> status.toJsonAST.toOption.get,
        )
        metadata.foreach(value => update = update.add("metadata", value))
        Json.Obj("statusUpdate" -> update)
      case TaskArtifactUpdate(id, contextId, artifact, append, lastChunk, metadata) =>
        var update = Json.Obj(
          "taskId"    -> Json.Str(id.value),
          "contextId" -> Json.Str(contextId.value),
          "artifact"  -> artifact.toJsonAST.toOption.get,
        )
        if append then update = update.add("append", Json.Bool(true))
        if lastChunk then update = update.add("lastChunk", Json.Bool(true))
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
      val fields   = json.asObject.map(_.toMap).getOrElse(Map.empty)
      val payloads = List(
        A2AJson.nonNullNamedField(fields, "task"),
        A2AJson.nonNullNamedField(fields, "message"),
        A2AJson.nonNullNamedField(fields, "statusUpdate", "status_update"),
        A2AJson.nonNullNamedField(fields, "artifactUpdate", "artifact_update"),
      ).flatten
      if fields.contains("kind") then decodeLegacy(json, fields)
      else
        payloads match
          case ("task", taskJson) :: Nil =>
            taskJson.as[A2ATask].map(TaskSnapshot(_))
          case ("message", messageJson) :: Nil =>
            messageJson.as[A2AMessage].map { message =>
              message.taskId match
                case Some(taskId) => TaskMessage(taskId, A2AEventIds.contextIdFor(message), message)
                case None         => Message(message)
            }
          case ("statusUpdate", updateJson) :: Nil =>
            decodeStatusUpdate(updateJson, requireContextId = true)
          case ("artifactUpdate", updateJson) :: Nil =>
            decodeArtifactUpdate(updateJson, legacyDefaultLastChunk = false, requireContextId = true)
          case Nil =>
            decodeLegacy(json, fields)
          case _ =>
            Left("StreamResponse must contain exactly one of task, message, statusUpdate, or artifactUpdate")
    }

    private def decodeStatusUpdate(json: Json, requireContextId: Boolean): Either[String, StreamEvent] =
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      for
        taskId    <- requiredNonEmptyString(fields, "taskId", "task_id").map(TaskId(_))
        status    <- fields.get("status").toRight("Missing status").flatMap(_.as[TaskStatus])
        contextId <- fields
          .get("contextId")
          .orElse(fields.get("context_id")) match
          case Some(value) =>
            value.asString
              .filter(_.nonEmpty)
              .map(ContextId(_))
              .toRight("contextId must be a non-empty string")
          case None if requireContextId =>
            Left("Missing contextId")
          case None =>
            Right(ContextId(taskId.value))
        isFinal  <- A2AJson.optionalBoolean(fields, "final").map(_.getOrElse(false))
        metadata <- A2AJson.optionalStruct(fields, "metadata")
      yield TaskStatusUpdate(taskId, contextId, status, isFinal, metadata)
      end for
    end decodeStatusUpdate

    private def decodeArtifactUpdate(
      json: Json,
      legacyDefaultLastChunk: Boolean,
      requireContextId: Boolean,
    ): Either[String, StreamEvent] =
      val fields = json.asObject.map(_.toMap).getOrElse(Map.empty)
      for
        taskId         <- requiredNonEmptyString(fields, "taskId", "task_id").map(TaskId(_))
        artifactJson   <- fields.get("artifact").toRight("Missing artifact")
        artifact       <- artifactJsonWithFallbackId(taskId, artifactJson).flatMap(_.as[Artifact])
        topLevelAppend <- A2AJson.optionalBoolean(fields, "append")
        nestedAppend   <- nestedBooleanField(artifactJson, "append")
        append = topLevelAppend.orElse(nestedAppend).getOrElse(false)
        topLevelLastChunk <- A2AJson.optionalBoolean(fields, "lastChunk", "last_chunk")
        nestedLastChunk   <- nestedBooleanField(artifactJson, "lastChunk", "last_chunk")
        lastChunk = topLevelLastChunk.orElse(nestedLastChunk).getOrElse(legacyDefaultLastChunk)
        contextId <- fields
          .get("contextId")
          .orElse(fields.get("context_id")) match
          case Some(value) =>
            value.asString
              .filter(_.nonEmpty)
              .map(ContextId(_))
              .toRight("contextId must be a non-empty string")
          case None if requireContextId =>
            Left("Missing contextId")
          case None =>
            Right(ContextId(taskId.value))
        metadata <- A2AJson.optionalStruct(fields, "metadata")
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
          decodeStatusUpdate(json, requireContextId = false)
        case "artifact-update" | "artifact" =>
          decodeArtifactUpdate(json, legacyDefaultLastChunk = true, requireContextId = false)
        case "message" =>
          for
            message <- fields.get("message").getOrElse(json).as[A2AMessage]
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
