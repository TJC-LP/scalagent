package com.tjclp.scalagent.a2a

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

private[a2a] enum A2AJsonRpcDispatch:
  case Single(response: JsonRpcResponse, extensions: List[String])
  case Stream(
    id: Option[JsonRpcId],
    events: ZStream[Any, Throwable, A2AResponse.StreamEvent],
    extensions: List[String])

private[a2a] object A2AJsonRpcRouting:
  def dispatch(
    request: JsonRpcRequest,
    context: ServerCallContext,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): UIO[A2AJsonRpcDispatch] =
    ZIO
      .fromEither(contextFor(request, context))
      .foldZIO(
        error => ZIO.succeed(errorDispatch(request.id, error)),
        effectiveContext =>
          val extensions = A2AServiceParameters.activatedExtensions(capabilities, effectiveContext)
          val routed     =
            if A2AOperation.fromMethodName(request.method).exists(_.streaming) then
              streamWithContext(request, effectiveContext, requestHandler)
                .map(events => A2AJsonRpcDispatch.Stream(request.id, events, extensions))
            else
              singleWithContext(request, effectiveContext, requestHandler)
                .map(response => A2AJsonRpcDispatch.Single(response, extensions))
          ZIO
            .fromEither(A2AServiceParameters.validate(agentCard, capabilities, effectiveContext, A2ATransport.JSONRPC))
            .foldZIO(
              error => ZIO.succeed(errorDispatch(request.id, error)),
              _ =>
                routed
                  .catchAll(error => ZIO.succeed(errorDispatch(request.id, A2AError.fromThrowable(error), extensions))),
            ),
      )

  def single(
    request: JsonRpcRequest,
    context: ServerCallContext,
    requestHandler: A2ARequestHandler,
  ): Task[JsonRpcResponse] =
    ZIO.fromEither(contextFor(request, context)).flatMap(singleWithContext(request, _, requestHandler))

  def stream(
    request: JsonRpcRequest,
    context: ServerCallContext,
    requestHandler: A2ARequestHandler,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    ZIO.fromEither(contextFor(request, context)).flatMap(streamWithContext(request, _, requestHandler))

  def contextFor(request: JsonRpcRequest, context: ServerCallContext): Either[A2AError, ServerCallContext] =
    if A2AOperation.fromMethodName(request.method).isEmpty then Right(context)
    else
      tenantFromParams(request.params).flatMap {
        case None         => Right(context)
        case Some(tenant) =>
          context.tenant.map(_.trim).filter(_.nonEmpty) match
            case Some(existing) if existing != tenant =>
              Left(A2AError.invalidParams("Conflicting tenant values in JSON-RPC request"))
            case _ =>
              Right(context.copy(tenant = Some(tenant)))
      }

  private def paramsAs[A: JsonDecoder](request: JsonRpcRequest): Task[A] =
    ZIO.fromEither(
      request.params.toRight(A2AError.invalidParams("Missing params")).flatMap(_.as[A].left.map(A2AError.invalidParams))
    )

  private def singleWithContext(
    request: JsonRpcRequest,
    context: ServerCallContext,
    requestHandler: A2ARequestHandler,
  ): Task[JsonRpcResponse] =
    A2AOperation.fromMethodName(request.method) match
      case Some(A2AOperation.MessageSend) =>
        paramsAs[A2ARequest.MessageSend](request)
          .flatMap(requestHandler.sendMessage(_, context))
          .map(JsonRpcResponse.success(request.id, _))
      case Some(A2AOperation.TasksGet) =>
        paramsAs[A2ARequest.TasksGet](request)
          .flatMap(requestHandler.getTask(_, context))
          .map(JsonRpcResponse.success(request.id, _))
      case Some(A2AOperation.TasksList) =>
        paramsAs[A2ARequest.TasksList](request)
          .flatMap(requestHandler.listTasks(_, context))
          .map(JsonRpcResponse.success(request.id, _))
      case Some(A2AOperation.TasksCancel) =>
        paramsAs[A2ARequest.TasksCancel](request)
          .flatMap(requestHandler.cancelTask(_, context))
          .map(JsonRpcResponse.success(request.id, _))
      case Some(A2AOperation.PushNotificationConfigSet) =>
        paramsAs[TaskPushNotificationConfig](request)
          .flatMap(requestHandler.createPushConfig(_, context))
          .map(JsonRpcResponse.success(request.id, _))
      case Some(A2AOperation.PushNotificationConfigGet) =>
        paramsAs[A2ARequest.PushNotificationConfigGet](request)
          .flatMap(requestHandler.getPushConfig(_, context))
          .map(JsonRpcResponse.success(request.id, _))
      case Some(A2AOperation.PushNotificationConfigList) =>
        paramsAs[A2ARequest.PushNotificationConfigList](request)
          .flatMap(requestHandler.listPushConfigs(_, context))
          .map(JsonRpcResponse.success(request.id, _))
      case Some(A2AOperation.PushNotificationConfigDelete) =>
        paramsAs[A2ARequest.PushNotificationConfigDelete](request)
          .flatMap(requestHandler.deletePushConfig(_, context))
          .as(JsonRpcResponse.success(request.id, Json.Obj()))
      case Some(A2AOperation.GetAuthenticatedExtendedCard) =>
        requestHandler.getExtendedAgentCard(context).map(JsonRpcResponse.success(request.id, _))
      case _ =>
        ZIO.fail(A2AError.methodNotFound(request.method))

  private def streamWithContext(
    request: JsonRpcRequest,
    context: ServerCallContext,
    requestHandler: A2ARequestHandler,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    A2AOperation.fromMethodName(request.method) match
      case Some(A2AOperation.MessageStream) =>
        paramsAs[A2ARequest.MessageSend](request)
          .flatMap(requestHandler.sendMessageStream(_, context))
      case Some(A2AOperation.TasksResubscribe) =>
        paramsAs[A2ARequest.TasksResubscribe](request)
          .flatMap(requestHandler.resubscribe(_, context))
      case _ =>
        ZIO.fail(A2AError.methodNotFound(request.method))

  private def errorDispatch(
    id: Option[JsonRpcId],
    error: A2AError,
    extensions: List[String] = Nil,
  ): A2AJsonRpcDispatch =
    A2AJsonRpcDispatch.Single(JsonRpcResponse.fromA2AError(id, error), extensions)

  private def tenantFromParams(params: Option[Json]): Either[A2AError, Option[String]] =
    params match
      case None       => Right(None)
      case Some(json) =>
        json.asObject match
          case None         => Right(None)
          case Some(fields) =>
            fields.toMap.get("tenant") match
              case None        => Right(None)
              case Some(value) =>
                value.asString match
                  case Some(tenant) => Right(Some(tenant.trim).filter(_.nonEmpty))
                  case None         => Left(A2AError.invalidParams("tenant must be a string"))
end A2AJsonRpcRouting
