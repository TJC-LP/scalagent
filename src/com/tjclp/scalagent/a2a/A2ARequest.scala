package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/** Request parameter types for A2A protocol methods */
object A2ARequest:

  /** Parameters for message/send and message/stream */
  final case class MessageSend(
      message: A2AMessage,
      configuration: Option[TaskConfiguration] = None
  )
  object MessageSend:
    given JsonEncoder[MessageSend] = DeriveJsonEncoder.gen[MessageSend]
    given JsonDecoder[MessageSend] = DeriveJsonDecoder.gen[MessageSend]

  /** Alias for streaming (same params) */
  type MessageStream = MessageSend

  /** Parameters for tasks/get */
  final case class TasksGet(
      id: TaskId,
      historyLength: Option[Int] = None
  )
  object TasksGet:
    given JsonEncoder[TasksGet] = DeriveJsonEncoder.gen[TasksGet]
    given JsonDecoder[TasksGet] = DeriveJsonDecoder.gen[TasksGet]

  /** Parameters for tasks/cancel */
  final case class TasksCancel(id: TaskId)
  object TasksCancel:
    given JsonEncoder[TasksCancel] = DeriveJsonEncoder.gen[TasksCancel]
    given JsonDecoder[TasksCancel] = DeriveJsonDecoder.gen[TasksCancel]

  /** Parameters for tasks/resubscribe */
  final case class TasksResubscribe(id: TaskId)
  object TasksResubscribe:
    given JsonEncoder[TasksResubscribe] = DeriveJsonEncoder.gen[TasksResubscribe]
    given JsonDecoder[TasksResubscribe] = DeriveJsonDecoder.gen[TasksResubscribe]

  /** Parameters for tasks/pushNotificationConfig/set */
  final case class PushNotificationConfigSet(
      id: TaskId,
      pushNotificationConfig: PushNotificationConfig
  )
  object PushNotificationConfigSet:
    given JsonEncoder[PushNotificationConfigSet] = DeriveJsonEncoder.gen[PushNotificationConfigSet]
    given JsonDecoder[PushNotificationConfigSet] = DeriveJsonDecoder.gen[PushNotificationConfigSet]

  /** Parameters for tasks/pushNotificationConfig/get */
  final case class PushNotificationConfigGet(id: TaskId)
  object PushNotificationConfigGet:
    given JsonEncoder[PushNotificationConfigGet] = DeriveJsonEncoder.gen[PushNotificationConfigGet]
    given JsonDecoder[PushNotificationConfigGet] = DeriveJsonDecoder.gen[PushNotificationConfigGet]

  /** Parameters for tasks/pushNotificationConfig/delete */
  final case class PushNotificationConfigDelete(id: TaskId)
  object PushNotificationConfigDelete:
    given JsonEncoder[PushNotificationConfigDelete] = DeriveJsonEncoder.gen[PushNotificationConfigDelete]
    given JsonDecoder[PushNotificationConfigDelete] = DeriveJsonDecoder.gen[PushNotificationConfigDelete]

  /** Empty params for agent/getAuthenticatedExtendedCard */
  final case class GetAuthenticatedExtendedCard()
  object GetAuthenticatedExtendedCard:
    given JsonEncoder[GetAuthenticatedExtendedCard] = DeriveJsonEncoder.gen[GetAuthenticatedExtendedCard]
    given JsonDecoder[GetAuthenticatedExtendedCard] = DeriveJsonDecoder.gen[GetAuthenticatedExtendedCard]

/** Task configuration for requests */
final case class TaskConfiguration(
    acceptedOutputModes: List[String] = List("text/plain"),
    historyLength: Option[Int] = None,
    pushNotificationConfig: Option[PushNotificationConfig] = None
)
object TaskConfiguration:
  given JsonEncoder[TaskConfiguration] = DeriveJsonEncoder.gen[TaskConfiguration]
  given JsonDecoder[TaskConfiguration] = DeriveJsonDecoder.gen[TaskConfiguration]

  val default: TaskConfiguration = TaskConfiguration()
