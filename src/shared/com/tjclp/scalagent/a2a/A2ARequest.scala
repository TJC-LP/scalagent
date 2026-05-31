package com.tjclp.scalagent.a2a

import scala.util.Try

import zio.json.*
import zio.json.ast.Json

/** Request parameter types for A2A v1 protocol methods. */
object A2ARequest:
  private def objectFields(json: Json, label: String): Either[String, Map[String, Json]] =
    json.asObject.map(_.toMap).toRight(s"$label must be an object")

  private def field(fields: Map[String, Json], names: String*): Option[Json] =
    names.iterator.flatMap(fields.get).nextOption()

  private def optionalString(fields: Map[String, Json], names: String*): Either[String, Option[String]] =
    field(fields, names*) match
      case Some(value) => value.asString.map(Some(_)).toRight(s"${names.head} must be a string")
      case None        => Right(None)

  private def requiredString(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, String] =
    optionalString(fields, (name +: aliases)*) flatMap {
      case Some(value) if value.nonEmpty => Right(value)
      case _                             => Left(s"Missing $name")
    }

  private def optionalInt(fields: Map[String, Json], names: String*): Either[String, Option[Int]] =
    field(fields, names*) match
      case Some(value) =>
        value.asNumber
          .toRight(s"${names.head} must be an int32")
          .flatMap(number => Try(number.value.intValueExact).toEither.left.map(_ => s"${names.head} must be an int32"))
          .map(Some(_))
      case None => Right(None)

  private def optionalBoundedInt(
    fields: Map[String, Json],
    name: String,
    min: Int,
    max: Int,
    aliases: String*
  ): Either[String, Option[Int]] =
    optionalInt(fields, (name +: aliases)*) flatMap {
      case Some(value) if value < min || value > max =>
        Left(s"$name must be between $min and $max inclusive")
      case other => Right(other)
    }

  private def optionalNonNegativeInt(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, Option[Int]] =
    optionalInt(fields, (name +: aliases)*) flatMap {
      case Some(value) if value < 0 => Left(s"$name must be non-negative integer, got $value")
      case other                    => Right(other)
    }

  private def optionalUtcTimestamp(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, Option[String]] =
    optionalString(fields, (name +: aliases)*) flatMap {
      case Some(value) if value.endsWith("Z") && Try(java.time.Instant.parse(value)).isSuccess =>
        Right(Some(value))
      case Some(value) =>
        Left(s"$name must be an ISO 8601 UTC timestamp ending in Z, got $value")
      case None => Right(None)
    }

  private def optionalBool(fields: Map[String, Json], names: String*): Either[String, Option[Boolean]] =
    field(fields, names*) match
      case Some(value) => value.asBoolean.map(Some(_)).toRight(s"${names.head} must be a boolean")
      case None        => Right(None)

  /** Parameters for SendMessage and SendStreamingMessage. */
  final case class MessageSend(
    message: A2AMessage,
    configuration: Option[MessageSendConfiguration] = None,
    metadata: Option[Json] = None,
    tenant: Option[String] = None)
  object MessageSend:
    given JsonEncoder[MessageSend] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj("message" -> request.message.toJsonAST.toOption.get)
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      request.configuration.foreach(value => obj = obj.add("configuration", value.toJsonAST.toOption.get))
      request.metadata.foreach(value => obj = obj.add("metadata", value))
      obj
    }
    given JsonDecoder[MessageSend] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "SendMessageRequest").flatMap { fields =>
        for
          message       <- field(fields, "message").toRight("Missing message").flatMap(_.as[A2AMessage])
          configuration <- field(fields, "configuration") match
            case Some(value) => value.as[MessageSendConfiguration].map(Some(_))
            case None        => Right(None)
          metadata <- A2AJson.optionalStruct(fields, "metadata")
          tenant   <- optionalString(fields, "tenant")
        yield MessageSend(message, configuration, metadata, tenant)
      }
    }
  end MessageSend

  /** Alias for streaming (same params). */
  type MessageStream               = MessageSend
  type SendMessageRequest          = MessageSend
  type SendStreamingMessageRequest = MessageSend

  /** Parameters for GetTask. */
  final case class TasksGet(
    id: TaskId,
    historyLength: Option[Int] = None,
    tenant: Option[String] = None)
  object TasksGet:
    given JsonEncoder[TasksGet] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj("id" -> Json.Str(request.id.value))
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      request.historyLength.foreach(value =>
        obj = obj.add("historyLength", Json.Num(java.math.BigDecimal.valueOf(value.toLong)))
      )
      obj
    }
    given JsonDecoder[TasksGet] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "GetTaskRequest").flatMap { fields =>
        for
          id            <- requiredString(fields, "id")
          historyLength <- optionalNonNegativeInt(fields, "historyLength", "history_length")
          tenant        <- optionalString(fields, "tenant")
        yield TasksGet(TaskId(id), historyLength, tenant)
      }
    }
  type GetTaskRequest = TasksGet

  /** Parameters for ListTasks. */
  final case class TasksList(
    contextId: Option[ContextId] = None,
    status: Option[TaskState] = None,
    pageSize: Option[Int] = None,
    pageToken: Option[String] = None,
    historyLength: Option[Int] = None,
    statusTimestampAfter: Option[String] = None,
    includeArtifacts: Option[Boolean] = None,
    tenant: Option[String] = None)
  object TasksList:
    given JsonEncoder[TasksList] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj()
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      request.contextId.foreach(value => obj = obj.add("contextId", Json.Str(value.value)))
      request.status.foreach(value => obj = obj.add("status", value.toJsonAST.toOption.get))
      request.pageSize.foreach(value => obj = obj.add("pageSize", Json.Num(java.math.BigDecimal.valueOf(value.toLong))))
      request.pageToken.filter(_.nonEmpty).foreach(value => obj = obj.add("pageToken", Json.Str(value)))
      request.historyLength.foreach(value =>
        obj = obj.add("historyLength", Json.Num(java.math.BigDecimal.valueOf(value.toLong)))
      )
      request.statusTimestampAfter
        .filter(_.nonEmpty)
        .foreach(value => obj = obj.add("statusTimestampAfter", Json.Str(value)))
      request.includeArtifacts.foreach(value => obj = obj.add("includeArtifacts", Json.Bool(value)))
      obj
    }
    given JsonDecoder[TasksList] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "ListTasksRequest").flatMap { fields =>
        for
          contextId <- optionalString(fields, "contextId", "context_id")
          status    <- field(fields, "status") match
            case Some(value) => value.as[TaskState].flatMap(TaskState.requireSpecified(_, "status")).map(Some(_))
            case None        => Right(None)
          pageSize             <- optionalBoundedInt(fields, "pageSize", 1, 100, "page_size")
          pageToken            <- optionalString(fields, "pageToken", "page_token")
          historyLength        <- optionalNonNegativeInt(fields, "historyLength", "history_length")
          statusTimestampAfter <- optionalUtcTimestamp(fields, "statusTimestampAfter", "status_timestamp_after")
          includeArtifacts     <- optionalBool(fields, "includeArtifacts", "include_artifacts")
          tenant               <- optionalString(fields, "tenant")
        yield TasksList(
          contextId = contextId.filter(_.nonEmpty).map(ContextId(_)),
          status = status,
          pageSize = pageSize,
          pageToken = pageToken,
          historyLength = historyLength,
          statusTimestampAfter = statusTimestampAfter,
          includeArtifacts = includeArtifacts,
          tenant = tenant,
        )
      }
    }
  end TasksList
  type ListTasksRequest = TasksList

  /** Parameters for CancelTask. */
  final case class TasksCancel(
    id: TaskId,
    metadata: Option[Json] = None,
    tenant: Option[String] = None)
  object TasksCancel:
    given JsonEncoder[TasksCancel] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj("id" -> Json.Str(request.id.value))
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      request.metadata.foreach(value => obj = obj.add("metadata", value))
      obj
    }
    given JsonDecoder[TasksCancel] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "CancelTaskRequest").flatMap { fields =>
        for
          id       <- requiredString(fields, "id")
          metadata <- A2AJson.optionalStruct(fields, "metadata")
          tenant   <- optionalString(fields, "tenant")
        yield TasksCancel(TaskId(id), metadata, tenant)
      }
    }
  type CancelTaskRequest = TasksCancel

  /** REST body shape for CancelTask. Path binding supplies `id`; body may supply tenant/metadata. */
  private[a2a] final case class TasksCancelRestBody(
    id: Option[TaskId] = None,
    metadata: Option[Json] = None,
    tenant: Option[String] = None)
  private[a2a] object TasksCancelRestBody:
    given JsonDecoder[TasksCancelRestBody] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "CancelTaskRequest").flatMap { fields =>
        for
          id       <- optionalString(fields, "id")
          metadata <- A2AJson.optionalStruct(fields, "metadata")
          tenant   <- optionalString(fields, "tenant")
        yield TasksCancelRestBody(
          id = id.filter(_.nonEmpty).map(TaskId(_)),
          metadata = metadata,
          tenant = tenant.filter(_.nonEmpty),
        )
      }
    }

  /** Parameters for SubscribeToTask. */
  final case class TasksResubscribe(
    id: TaskId,
    tenant: Option[String] = None)
  object TasksResubscribe:
    given JsonEncoder[TasksResubscribe] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj("id" -> Json.Str(request.id.value))
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      obj
    }
    given JsonDecoder[TasksResubscribe] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "SubscribeToTaskRequest").flatMap { fields =>
        for
          id     <- requiredString(fields, "id")
          tenant <- optionalString(fields, "tenant")
        yield TasksResubscribe(TaskId(id), tenant)
      }
    }
  type SubscribeToTaskRequest = TasksResubscribe

  /** Parameters for CreateTaskPushNotificationConfig are the config itself. */
  type PushNotificationConfigCreate            = TaskPushNotificationConfig
  type CreateTaskPushNotificationConfigRequest = TaskPushNotificationConfig

  /** Parameters for GetTaskPushNotificationConfig. */
  final case class PushNotificationConfigGet(
    taskId: TaskId,
    id: String,
    tenant: Option[String] = None)
  object PushNotificationConfigGet:
    given JsonEncoder[PushNotificationConfigGet] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj(
        "taskId" -> Json.Str(request.taskId.value),
        "id"     -> Json.Str(request.id),
      )
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      obj
    }
    given JsonDecoder[PushNotificationConfigGet] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "GetTaskPushNotificationConfigRequest").flatMap { fields =>
        for
          taskId <- requiredString(fields, "taskId", "task_id")
          id     <- requiredString(fields, "id")
          tenant <- optionalString(fields, "tenant")
        yield PushNotificationConfigGet(TaskId(taskId), id, tenant)
      }
    }
  type GetTaskPushNotificationConfigRequest = PushNotificationConfigGet

  /** Parameters for ListTaskPushNotificationConfigs. */
  final case class PushNotificationConfigList(
    taskId: TaskId,
    pageSize: Option[Int] = None,
    pageToken: Option[String] = None,
    tenant: Option[String] = None)
  object PushNotificationConfigList:
    given JsonEncoder[PushNotificationConfigList] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj("taskId" -> Json.Str(request.taskId.value))
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      request.pageSize.foreach(value => obj = obj.add("pageSize", Json.Num(java.math.BigDecimal.valueOf(value.toLong))))
      request.pageToken.filter(_.nonEmpty).foreach(value => obj = obj.add("pageToken", Json.Str(value)))
      obj
    }
    given JsonDecoder[PushNotificationConfigList] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "ListTaskPushNotificationConfigsRequest").flatMap { fields =>
        for
          taskId    <- requiredString(fields, "taskId", "task_id")
          pageSize  <- optionalInt(fields, "pageSize", "page_size")
          pageToken <- optionalString(fields, "pageToken", "page_token")
          tenant    <- optionalString(fields, "tenant")
        yield PushNotificationConfigList(TaskId(taskId), pageSize, pageToken, tenant)
      }
    }
  type ListTaskPushNotificationConfigsRequest = PushNotificationConfigList

  /** Parameters for DeleteTaskPushNotificationConfig. */
  final case class PushNotificationConfigDelete(
    taskId: TaskId,
    id: String,
    tenant: Option[String] = None)
  object PushNotificationConfigDelete:
    given JsonEncoder[PushNotificationConfigDelete] = JsonEncoder[Json].contramap { request =>
      var obj = Json.Obj(
        "taskId" -> Json.Str(request.taskId.value),
        "id"     -> Json.Str(request.id),
      )
      request.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
      obj
    }
    given JsonDecoder[PushNotificationConfigDelete] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "DeleteTaskPushNotificationConfigRequest").flatMap { fields =>
        for
          taskId <- requiredString(fields, "taskId", "task_id")
          id     <- requiredString(fields, "id")
          tenant <- optionalString(fields, "tenant")
        yield PushNotificationConfigDelete(TaskId(taskId), id, tenant)
      }
    }
  type DeleteTaskPushNotificationConfigRequest = PushNotificationConfigDelete

  /** Empty params for GetExtendedAgentCard. */
  final case class GetAuthenticatedExtendedCard(tenant: Option[String] = None)
  object GetAuthenticatedExtendedCard:
    given JsonEncoder[GetAuthenticatedExtendedCard] = JsonEncoder[Json].contramap { request =>
      request.tenant.filter(_.nonEmpty) match
        case Some(tenant) => Json.Obj("tenant" -> Json.Str(tenant))
        case None         => Json.Obj()
    }
    given JsonDecoder[GetAuthenticatedExtendedCard] = JsonDecoder[Json].mapOrFail { json =>
      objectFields(json, "GetExtendedAgentCardRequest").flatMap { fields =>
        optionalString(fields, "tenant").map(tenant => GetAuthenticatedExtendedCard(tenant.filter(_.nonEmpty)))
      }
    }
  type GetExtendedAgentCardRequest = GetAuthenticatedExtendedCard
end A2ARequest

/** Message send configuration (A2A v1 SendMessageConfiguration). */
final case class MessageSendConfiguration(
  acceptedOutputModes: List[String] = Nil,
  taskPushNotificationConfig: Option[TaskPushNotificationConfig] = None,
  historyLength: Option[Int] = None,
  returnImmediately: Boolean = false):

  /** Compatibility sugar for the old v0.3 `blocking` option. */
  def blocking: Option[Boolean] = Some(!returnImmediately)

  /** Compatibility sugar for the old v0.3 field name. */
  def pushNotificationConfig: Option[TaskPushNotificationConfig] =
    taskPushNotificationConfig

object MessageSendConfiguration:
  private def field(fields: Map[String, Json], names: String*): Option[Json] =
    names.iterator.flatMap(fields.get).nextOption()

  private def optionalStringList(
    fields: Map[String, Json],
    names: String*
  ): Either[String, Option[List[String]]] =
    field(fields, names*) match
      case Some(value) => value.as[List[String]].map(Some(_))
      case None        => Right(None)

  private def optionalBool(
    fields: Map[String, Json],
    names: String*
  ): Either[String, Option[Boolean]] =
    field(fields, names*) match
      case Some(value) => value.asBoolean.map(Some(_)).toRight(s"${names.head} must be a boolean")
      case None        => Right(None)

  private def optionalInt(
    fields: Map[String, Json],
    names: String*
  ): Either[String, Option[Int]] =
    field(fields, names*) match
      case Some(value) =>
        value.asNumber
          .toRight(s"${names.head} must be an int32")
          .flatMap(number => Try(number.value.intValueExact).toEither.left.map(_ => s"${names.head} must be an int32"))
          .map(Some(_))
      case None => Right(None)

  private def optionalNonNegativeInt(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, Option[Int]] =
    optionalInt(fields, (name +: aliases)*) flatMap {
      case Some(value) if value < 0 => Left(s"$name must be non-negative integer, got $value")
      case other                    => Right(other)
    }

  given JsonEncoder[MessageSendConfiguration] = JsonEncoder[Json].contramap { config =>
    var obj = Json.Obj()
    if config.acceptedOutputModes.nonEmpty then
      obj = obj.add("acceptedOutputModes", Json.Arr(config.acceptedOutputModes.map(Json.Str(_))*))
    if config.returnImmediately then obj = obj.add("returnImmediately", Json.Bool(true))
    config.taskPushNotificationConfig.foreach(value =>
      obj = obj.add("taskPushNotificationConfig", value.toJsonAST.toOption.get)
    )
    config.historyLength.foreach(value =>
      obj = obj.add("historyLength", Json.Num(java.math.BigDecimal.valueOf(value.toLong)))
    )
    obj
  }

  given JsonDecoder[MessageSendConfiguration] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("MessageSendConfiguration must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        acceptedOutputModes        <- optionalStringList(fields, "acceptedOutputModes", "accepted_output_modes")
        taskPushNotificationConfig <- field(
          fields,
          "taskPushNotificationConfig",
          "task_push_notification_config",
          "pushNotificationConfig",
        ) match
          case Some(value) => value.as[TaskPushNotificationConfig].map(Some(_))
          case None        => Right(None)
        historyLength             <- optionalNonNegativeInt(fields, "historyLength", "history_length")
        explicitReturnImmediately <- optionalBool(fields, "returnImmediately", "return_immediately")
        blocking                  <- optionalBool(fields, "blocking")
      yield MessageSendConfiguration(
        acceptedOutputModes = acceptedOutputModes.getOrElse(Nil),
        taskPushNotificationConfig = taskPushNotificationConfig,
        historyLength = historyLength,
        returnImmediately = explicitReturnImmediately.orElse(blocking.map(value => !value)).getOrElse(false),
      )
    }
  }

  val default: MessageSendConfiguration = MessageSendConfiguration()

  def fromBlocking(
    acceptedOutputModes: List[String] = Nil,
    blocking: Option[Boolean] = None,
    historyLength: Option[Int] = None,
    pushNotificationConfig: Option[TaskPushNotificationConfig] = None,
  ): MessageSendConfiguration =
    MessageSendConfiguration(
      acceptedOutputModes = acceptedOutputModes,
      taskPushNotificationConfig = pushNotificationConfig,
      historyLength = historyLength,
      returnImmediately = blocking.exists(!_),
    )
end MessageSendConfiguration

/** Backwards-compatible alias. */
type TaskConfiguration = MessageSendConfiguration
val TaskConfiguration = MessageSendConfiguration
