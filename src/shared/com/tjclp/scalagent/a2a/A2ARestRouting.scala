package com.tjclp.scalagent.a2a

import zio.*
import zio.json.*
import zio.stream.*

private[a2a] enum A2ARestDispatch:
  case Json(
    body: String,
    status: Int,
    extensions: List[String])
  case Stream(body: ZStream[Any, Throwable, String], extensions: List[String])
  case Empty(status: Int, extensions: List[String])
  case Error(error: A2AError, extensions: List[String])

private[a2a] trait A2ARestBodyReader:
  def bodyAs[A: JsonDecoder]: Task[A]
  def optionalBodyAs[A: JsonDecoder]: Task[Option[A]]

private[a2a] object A2ARestRouting:
  def dispatch(
    routed: A2APathRouting.RoutedRest,
    query: A2APathRouting.Query,
    contextFor: Option[String] => Task[ServerCallContext],
    bodyReader: A2ARestBodyReader,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): Option[UIO[A2ARestDispatch]] =
    import A2APathRouting.RestRoute

    routed.route match
      case Some(RestRoute.MessageSend) =>
        Some(
          decodeBody[A2ARequest.MessageSend](bodyReader)(request =>
            json(contextFor(request.tenant), agentCard, capabilities)(context =>
              requestHandler.sendMessage(request, context)
            )
          )
        )
      case Some(RestRoute.MessageStream) =>
        Some(
          decodeBody[A2ARequest.MessageSend](bodyReader)(request =>
            stream(contextFor(request.tenant), agentCard, capabilities)(context =>
              requestHandler.sendMessageStream(request, context)
            )
          )
        )
      case Some(RestRoute.TasksList) =>
        Some(
          queryJson(contextFor, agentCard, capabilities) { context =>
            ZIO
              .fromEither(A2APathRouting.tasksList(query, context.tenant))
              .flatMap(requestHandler.listTasks(_, context))
          }
        )
      case Some(RestRoute.TaskCancel(rawTaskAction)) =>
        Some(
          bodyReader
            .optionalBodyAs[A2ARequest.TasksCancelRestBody]
            .foldZIO(
              error => ZIO.succeed(A2ARestDispatch.Error(A2AError.fromThrowable(error), Nil)),
              body =>
                json(contextFor(body.flatMap(_.tenant)), agentCard, capabilities, status = 202) { context =>
                  ZIO
                    .fromEither(A2APathRouting.tasksCancel(rawTaskAction, body, context.tenant))
                    .flatMap(requestHandler.cancelTask(_, context))
                },
            )
        )
      case Some(RestRoute.TaskSubscribe(rawTaskAction)) =>
        Some(
          stream(contextFor(None), agentCard, capabilities) { context =>
            ZIO
              .fromEither(A2APathRouting.tasksResubscribe(rawTaskAction, context.tenant))
              .flatMap(requestHandler.resubscribe(_, context))
          }
        )
      case Some(RestRoute.TaskGet(rawTaskId)) =>
        Some(
          queryJson(contextFor, agentCard, capabilities) { context =>
            ZIO
              .fromEither(A2APathRouting.tasksGet(rawTaskId, query, context.tenant))
              .flatMap(requestHandler.getTask(_, context))
          }
        )
      case Some(RestRoute.PushConfigCreate(rawTaskId)) =>
        Some(
          decodeBody[TaskPushNotificationConfig](bodyReader)(config =>
            json(contextFor(config.tenant), agentCard, capabilities, status = 201)(context =>
              ZIO
                .fromEither(A2APathRouting.pushConfigCreate(rawTaskId, config, context.tenant))
                .flatMap(requestHandler.createPushConfig(_, context))
            )
          )
        )
      case Some(RestRoute.PushConfigList(rawTaskId)) =>
        Some(
          queryJson(contextFor, agentCard, capabilities) { context =>
            ZIO
              .fromEither(A2APathRouting.pushConfigList(rawTaskId, query, context.tenant))
              .flatMap(requestHandler.listPushConfigs(_, context))
          }
        )
      case Some(RestRoute.PushConfigGet(rawTaskId, rawConfigId)) =>
        Some(
          queryJson(contextFor, agentCard, capabilities) { context =>
            ZIO
              .fromEither(A2APathRouting.pushConfigGet(rawTaskId, rawConfigId, context.tenant))
              .flatMap(requestHandler.getPushConfig(_, context))
          }
        )
      case Some(RestRoute.PushConfigDelete(rawTaskId, rawConfigId)) =>
        Some(
          empty(contextFor(None), agentCard, capabilities, status = 204) { context =>
            ZIO
              .fromEither(A2APathRouting.pushConfigDelete(rawTaskId, rawConfigId, context.tenant))
              .flatMap(requestHandler.deletePushConfig(_, context))
          }
        )
      case Some(RestRoute.ExtendedAgentCard) =>
        Some(queryJson(contextFor, agentCard, capabilities)(requestHandler.getExtendedAgentCard))
      case Some(RestRoute.MissingTaskId) =>
        Some(
          queryJson[A2ATask](contextFor, agentCard, capabilities)(_ =>
            ZIO.fail(A2AError.invalidParams("Missing task ID"))
          )
        )
      case _ =>
        None
    end match
  end dispatch

  private def decodeBody[A: JsonDecoder](
    bodyReader: A2ARestBodyReader
  )(next: A => UIO[A2ARestDispatch]
  ): UIO[A2ARestDispatch] =
    bodyReader
      .bodyAs[A]
      .foldZIO(error => ZIO.succeed(A2ARestDispatch.Error(A2AError.fromThrowable(error), Nil)), next)

  private def queryJson[A: JsonEncoder](
    contextFor: Option[String] => Task[ServerCallContext],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    status: Int = 200,
  )(effect: ServerCallContext => Task[A]
  ): UIO[A2ARestDispatch] =
    json(contextFor(None), agentCard, capabilities, status)(effect)

  private def json[A: JsonEncoder](
    contextTask: Task[ServerCallContext],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    status: Int = 200,
  )(effect: ServerCallContext => Task[A]
  ): UIO[A2ARestDispatch] =
    withValidatedContext(contextTask, agentCard, capabilities) { (context, extensions) =>
      effect(context)
        .map(value => A2ARestDispatch.Json(value.toJson, status, extensions))
        .catchAll(error => ZIO.succeed(A2ARestDispatch.Error(A2AError.fromThrowable(error), extensions)))
    }

  private def stream(
    contextTask: Task[ServerCallContext],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
  )(effect: ServerCallContext => Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]]
  ): UIO[A2ARestDispatch] =
    withValidatedContext(contextTask, agentCard, capabilities) { (context, extensions) =>
      effect(context)
        .map(stream => A2ARestDispatch.Stream(stream.map(_.toJson), extensions))
        .catchAll(error => ZIO.succeed(A2ARestDispatch.Error(A2AError.fromThrowable(error), extensions)))
    }

  private def empty(
    contextTask: Task[ServerCallContext],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    status: Int,
  )(effect: ServerCallContext => Task[Unit]
  ): UIO[A2ARestDispatch] =
    withValidatedContext(contextTask, agentCard, capabilities) { (context, extensions) =>
      effect(context)
        .as(A2ARestDispatch.Empty(status, extensions))
        .catchAll(error => ZIO.succeed(A2ARestDispatch.Error(A2AError.fromThrowable(error), extensions)))
    }

  private def withValidatedContext(
    contextTask: Task[ServerCallContext],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
  )(next: (ServerCallContext, List[String]) => UIO[A2ARestDispatch]
  ): UIO[A2ARestDispatch] =
    contextTask.foldZIO(
      error => ZIO.succeed(A2ARestDispatch.Error(A2AError.fromThrowable(error), Nil)),
      context =>
        val extensions = A2AServiceParameters.activatedExtensions(capabilities, context)
        ZIO
          .fromEither(A2AServiceParameters.validate(agentCard, capabilities, context, A2ATransport.HTTP_JSON))
          .foldZIO(
            error => ZIO.succeed(A2ARestDispatch.Error(error, Nil)),
            _ => next(context, extensions),
          ),
    )
end A2ARestRouting
