package com.tjclp.scalagent.a2a

/** Shared catalog of A2A service operations across JSON-RPC, REST, and gRPC. */
private[a2a] enum A2AOperation(val methodName: String, val streaming: Boolean):
  case MessageSend                  extends A2AOperation(A2AMethod.MessageSend, streaming = false)
  case MessageStream                extends A2AOperation(A2AMethod.MessageStream, streaming = true)
  case TasksGet                     extends A2AOperation(A2AMethod.TasksGet, streaming = false)
  case TasksList                    extends A2AOperation(A2AMethod.TasksList, streaming = false)
  case TasksCancel                  extends A2AOperation(A2AMethod.TasksCancel, streaming = false)
  case TasksResubscribe             extends A2AOperation(A2AMethod.TasksResubscribe, streaming = true)
  case PushNotificationConfigSet    extends A2AOperation(A2AMethod.PushNotificationConfigSet, streaming = false)
  case PushNotificationConfigGet    extends A2AOperation(A2AMethod.PushNotificationConfigGet, streaming = false)
  case PushNotificationConfigList   extends A2AOperation(A2AMethod.PushNotificationConfigList, streaming = false)
  case PushNotificationConfigDelete extends A2AOperation(A2AMethod.PushNotificationConfigDelete, streaming = false)
  case GetAuthenticatedExtendedCard extends A2AOperation(A2AMethod.GetAuthenticatedExtendedCard, streaming = false)

  def grpcMethodName: String = methodName

object A2AOperation:
  val all: List[A2AOperation] = A2AOperation.values.toList

  val methodNames: Set[String] =
    all.map(_.methodName).toSet

  val grpcMethodNames: Set[String] =
    all.map(_.grpcMethodName).toSet

  val streamingMethodNames: Set[String] =
    all.filter(_.streaming).map(_.methodName).toSet

  def fromMethodName(methodName: String): Option[A2AOperation] =
    all.find(_.methodName == methodName)

  def fromGrpcMethodName(methodName: String): Option[A2AOperation] =
    all.find(_.grpcMethodName == methodName)
end A2AOperation
