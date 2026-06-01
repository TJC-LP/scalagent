package com.tjclp.scalagent.a2a

import zio.*
import zio.stream.ZStream

private[a2a] enum A2AGrpcRequest(val operation: A2AOperation):
  case MessageSend(request: A2ARequest.MessageSend)           extends A2AGrpcRequest(A2AOperation.MessageSend)
  case MessageStream(request: A2ARequest.MessageSend)         extends A2AGrpcRequest(A2AOperation.MessageStream)
  case TasksGet(request: A2ARequest.TasksGet)                 extends A2AGrpcRequest(A2AOperation.TasksGet)
  case TasksList(request: A2ARequest.TasksList)               extends A2AGrpcRequest(A2AOperation.TasksList)
  case TasksCancel(request: A2ARequest.TasksCancel)           extends A2AGrpcRequest(A2AOperation.TasksCancel)
  case TasksResubscribe(request: A2ARequest.TasksResubscribe) extends A2AGrpcRequest(A2AOperation.TasksResubscribe)
  case PushNotificationConfigSet(request: TaskPushNotificationConfig)
      extends A2AGrpcRequest(A2AOperation.PushNotificationConfigSet)
  case PushNotificationConfigGet(request: A2ARequest.PushNotificationConfigGet)
      extends A2AGrpcRequest(A2AOperation.PushNotificationConfigGet)
  case PushNotificationConfigList(request: A2ARequest.PushNotificationConfigList)
      extends A2AGrpcRequest(A2AOperation.PushNotificationConfigList)
  case PushNotificationConfigDelete(request: A2ARequest.PushNotificationConfigDelete)
      extends A2AGrpcRequest(A2AOperation.PushNotificationConfigDelete)
  case GetAuthenticatedExtendedCard(request: A2ARequest.GetAuthenticatedExtendedCard)
      extends A2AGrpcRequest(A2AOperation.GetAuthenticatedExtendedCard)

  def tenant: Option[String] =
    this match
      case MessageSend(request)                  => request.tenant
      case MessageStream(request)                => request.tenant
      case TasksGet(request)                     => request.tenant
      case TasksList(request)                    => request.tenant
      case TasksCancel(request)                  => request.tenant
      case TasksResubscribe(request)             => request.tenant
      case PushNotificationConfigSet(request)    => request.tenant
      case PushNotificationConfigGet(request)    => request.tenant
      case PushNotificationConfigList(request)   => request.tenant
      case PushNotificationConfigDelete(request) => request.tenant
      case GetAuthenticatedExtendedCard(request) => request.tenant
end A2AGrpcRequest

private[a2a] enum A2AGrpcResponse:
  case SendMessage(result: A2AResponse.SendMessageResult)
  case Task(task: A2ATask)
  case ListTasks(result: A2AResponse.ListTasksResult)
  case PushNotificationConfig(config: TaskPushNotificationConfig)
  case PushNotificationConfigList(result: A2AResponse.PushNotificationConfigListResult)
  case Empty
  case AgentCard(card: com.tjclp.scalagent.a2a.AgentCard)

private[a2a] enum A2AGrpcDispatch:
  case Unary(response: A2AGrpcResponse, extensions: List[String])
  case Stream(events: ZStream[Any, Throwable, A2AResponse.StreamEvent], extensions: List[String])
  case Error(error: A2AError, extensions: List[String])

private[a2a] object A2AGrpcBinding:
  def dispatch(
    request: A2AGrpcRequest,
    context: ServerCallContext,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): UIO[A2AGrpcDispatch] =
    ZIO
      .fromEither(contextFor(request, context))
      .foldZIO(
        error => ZIO.succeed(A2AGrpcDispatch.Error(error, Nil)),
        effectiveContext =>
          val extensions = A2AServiceParameters.activatedExtensions(capabilities, effectiveContext)
          ZIO
            .fromEither(A2AServiceParameters.validate(agentCard, capabilities, effectiveContext, A2ATransport.GRPC))
            .foldZIO(
              error => ZIO.succeed(A2AGrpcDispatch.Error(error, Nil)),
              _ =>
                val routed =
                  if request.operation.streaming then
                    streamWithContext(request, effectiveContext, requestHandler)
                      .map(A2AGrpcDispatch.Stream(_, extensions))
                  else
                    unaryWithContext(request, effectiveContext, requestHandler)
                      .map(A2AGrpcDispatch.Unary(_, extensions))
                routed.catchAll(error => ZIO.succeed(A2AGrpcDispatch.Error(A2AError.fromThrowable(error), extensions))),
            ),
      )

  def contextFor(request: A2AGrpcRequest, context: ServerCallContext): Either[A2AError, ServerCallContext] =
    request.tenant.map(_.trim).filter(_.nonEmpty) match
      case None =>
        Right(context)
      case Some(requestTenant) =>
        context.tenant.map(_.trim).filter(_.nonEmpty) match
          case Some(existing) if existing != requestTenant =>
            Left(A2AError.invalidParams("Conflicting tenant values in gRPC request"))
          case _ =>
            Right(context.copy(tenant = Some(requestTenant)))

  private def unaryWithContext(
    request: A2AGrpcRequest,
    context: ServerCallContext,
    requestHandler: A2ARequestHandler,
  ): Task[A2AGrpcResponse] =
    request match
      case A2AGrpcRequest.MessageSend(params) =>
        requestHandler.sendMessage(params, context).map(A2AGrpcResponse.SendMessage(_))
      case A2AGrpcRequest.TasksGet(params) =>
        requestHandler.getTask(params, context).map(A2AGrpcResponse.Task(_))
      case A2AGrpcRequest.TasksList(params) =>
        requestHandler.listTasks(params, context).map(A2AGrpcResponse.ListTasks(_))
      case A2AGrpcRequest.TasksCancel(params) =>
        requestHandler.cancelTask(params, context).map(A2AGrpcResponse.Task(_))
      case A2AGrpcRequest.PushNotificationConfigSet(params) =>
        requestHandler.createPushConfig(params, context).map(A2AGrpcResponse.PushNotificationConfig(_))
      case A2AGrpcRequest.PushNotificationConfigGet(params) =>
        requestHandler.getPushConfig(params, context).map(A2AGrpcResponse.PushNotificationConfig(_))
      case A2AGrpcRequest.PushNotificationConfigList(params) =>
        requestHandler.listPushConfigs(params, context).map(A2AGrpcResponse.PushNotificationConfigList(_))
      case A2AGrpcRequest.PushNotificationConfigDelete(params) =>
        requestHandler.deletePushConfig(params, context).as(A2AGrpcResponse.Empty)
      case A2AGrpcRequest.GetAuthenticatedExtendedCard(_) =>
        requestHandler.getExtendedAgentCard(context).map(A2AGrpcResponse.AgentCard(_))
      case _ =>
        ZIO.fail(A2AError.methodNotFound(request.operation.grpcMethodName))

  private def streamWithContext(
    request: A2AGrpcRequest,
    context: ServerCallContext,
    requestHandler: A2ARequestHandler,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    request match
      case A2AGrpcRequest.MessageStream(params) =>
        requestHandler.sendMessageStream(params, context)
      case A2AGrpcRequest.TasksResubscribe(params) =>
        requestHandler.resubscribe(params, context)
      case _ =>
        ZIO.fail(A2AError.methodNotFound(request.operation.grpcMethodName))
end A2AGrpcBinding
