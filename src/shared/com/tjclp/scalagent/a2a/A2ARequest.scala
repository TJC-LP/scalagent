package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/** Request parameter types for A2A v1 protocol methods. */
object A2ARequest:

  /** Parameters for SendMessage and SendStreamingMessage. */
  final case class MessageSend(
    message: A2AMessage,
    configuration: Option[MessageSendConfiguration] = None,
    metadata: Option[Json] = None,
    tenant: Option[String] = None)
  object MessageSend:
    given JsonEncoder[MessageSend] = DeriveJsonEncoder.gen[MessageSend]
    given JsonDecoder[MessageSend] = DeriveJsonDecoder.gen[MessageSend]

  /** Alias for streaming (same params). */
  type MessageStream = MessageSend
  type SendMessageRequest = MessageSend
  type SendStreamingMessageRequest = MessageSend

  /** Parameters for GetTask. */
  final case class TasksGet(
    id: TaskId,
    historyLength: Option[Int] = None,
    tenant: Option[String] = None)
  object TasksGet:
    given JsonEncoder[TasksGet] = DeriveJsonEncoder.gen[TasksGet]
    given JsonDecoder[TasksGet] = DeriveJsonDecoder.gen[TasksGet]
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
    given JsonEncoder[TasksList] = DeriveJsonEncoder.gen[TasksList]
    given JsonDecoder[TasksList] = DeriveJsonDecoder.gen[TasksList]
  type ListTasksRequest = TasksList

  /** Parameters for CancelTask. */
  final case class TasksCancel(
    id: TaskId,
    metadata: Option[Json] = None,
    tenant: Option[String] = None)
  object TasksCancel:
    given JsonEncoder[TasksCancel] = DeriveJsonEncoder.gen[TasksCancel]
    given JsonDecoder[TasksCancel] = DeriveJsonDecoder.gen[TasksCancel]
  type CancelTaskRequest = TasksCancel

  /** Parameters for SubscribeToTask. */
  final case class TasksResubscribe(
    id: TaskId,
    tenant: Option[String] = None)
  object TasksResubscribe:
    given JsonEncoder[TasksResubscribe] = DeriveJsonEncoder.gen[TasksResubscribe]
    given JsonDecoder[TasksResubscribe] = DeriveJsonDecoder.gen[TasksResubscribe]
  type SubscribeToTaskRequest = TasksResubscribe

  /** Parameters for CreateTaskPushNotificationConfig are the config itself. */
  type PushNotificationConfigCreate = TaskPushNotificationConfig
  type CreateTaskPushNotificationConfigRequest = TaskPushNotificationConfig

  /** Parameters for GetTaskPushNotificationConfig. */
  final case class PushNotificationConfigGet(
    taskId: TaskId,
    id: String,
    tenant: Option[String] = None)
  object PushNotificationConfigGet:
    given JsonEncoder[PushNotificationConfigGet] = DeriveJsonEncoder.gen[PushNotificationConfigGet]
    given JsonDecoder[PushNotificationConfigGet] = DeriveJsonDecoder.gen[PushNotificationConfigGet]
  type GetTaskPushNotificationConfigRequest = PushNotificationConfigGet

  /** Parameters for ListTaskPushNotificationConfigs. */
  final case class PushNotificationConfigList(
    taskId: TaskId,
    pageSize: Option[Int] = None,
    pageToken: Option[String] = None,
    tenant: Option[String] = None)
  object PushNotificationConfigList:
    given JsonEncoder[PushNotificationConfigList] = DeriveJsonEncoder.gen[PushNotificationConfigList]
    given JsonDecoder[PushNotificationConfigList] = DeriveJsonDecoder.gen[PushNotificationConfigList]
  type ListTaskPushNotificationConfigsRequest = PushNotificationConfigList

  /** Parameters for DeleteTaskPushNotificationConfig. */
  final case class PushNotificationConfigDelete(
    taskId: TaskId,
    id: String,
    tenant: Option[String] = None)
  object PushNotificationConfigDelete:
    given JsonEncoder[PushNotificationConfigDelete] = DeriveJsonEncoder.gen[PushNotificationConfigDelete]
    given JsonDecoder[PushNotificationConfigDelete] = DeriveJsonDecoder.gen[PushNotificationConfigDelete]
  type DeleteTaskPushNotificationConfigRequest = PushNotificationConfigDelete

  /** Empty params for GetExtendedAgentCard. */
  final case class GetAuthenticatedExtendedCard(tenant: Option[String] = None)
  object GetAuthenticatedExtendedCard:
    given JsonEncoder[GetAuthenticatedExtendedCard] = DeriveJsonEncoder.gen[GetAuthenticatedExtendedCard]
    given JsonDecoder[GetAuthenticatedExtendedCard] = DeriveJsonDecoder.gen[GetAuthenticatedExtendedCard]
  type GetExtendedAgentCardRequest = GetAuthenticatedExtendedCard
end A2ARequest

/** Message send configuration (A2A v1 SendMessageConfiguration). */
final case class MessageSendConfiguration(
  acceptedOutputModes: List[String] = List("text/plain"),
  taskPushNotificationConfig: Option[TaskPushNotificationConfig] = None,
  historyLength: Option[Int] = None,
  returnImmediately: Boolean = false):

  /** Compatibility sugar for the old v0.3 `blocking` option. */
  def blocking: Option[Boolean] = Some(!returnImmediately)

  /** Compatibility sugar for the old v0.3 field name. */
  def pushNotificationConfig: Option[TaskPushNotificationConfig] =
    taskPushNotificationConfig

object MessageSendConfiguration:
  given JsonEncoder[MessageSendConfiguration] = JsonEncoder[Json].contramap { config =>
    var obj = Json.Obj(
      "acceptedOutputModes" -> Json.Arr(config.acceptedOutputModes.map(Json.Str(_))*),
      "returnImmediately"   -> Json.Bool(config.returnImmediately),
    )
    config.taskPushNotificationConfig.foreach(value => obj = obj.add("taskPushNotificationConfig", value.toJsonAST.toOption.get))
    config.historyLength.foreach(value => obj = obj.add("historyLength", Json.Num(java.math.BigDecimal.valueOf(value.toLong))))
    obj
  }

  given JsonDecoder[MessageSendConfiguration] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("MessageSendConfiguration must be an object").map { obj =>
      val fields = obj.toMap
      val returnImmediately = fields
        .get("returnImmediately")
        .orElse(fields.get("return_immediately"))
        .flatMap(_.asBoolean)
        .orElse(fields.get("blocking").flatMap(_.asBoolean).map(blocking => !blocking))
        .getOrElse(false)
      MessageSendConfiguration(
        acceptedOutputModes = fields
          .get("acceptedOutputModes")
          .orElse(fields.get("accepted_output_modes"))
          .flatMap(_.asArray)
          .map(_.toList.flatMap(_.asString))
          .getOrElse(List("text/plain")),
        taskPushNotificationConfig = fields
          .get("taskPushNotificationConfig")
          .orElse(fields.get("task_push_notification_config"))
          .orElse(fields.get("pushNotificationConfig"))
          .flatMap(_.as[TaskPushNotificationConfig].toOption),
        historyLength = fields.get("historyLength").orElse(fields.get("history_length")).flatMap(_.asNumber).map(_.value.intValue),
        returnImmediately = returnImmediately,
      )
    }
  }

  val default: MessageSendConfiguration = MessageSendConfiguration()

  def fromBlocking(
    acceptedOutputModes: List[String] = List("text/plain"),
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
