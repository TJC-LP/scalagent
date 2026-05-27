package com.tjclp.scalagent.a2a

import zio.*
import zio.stream.*

private[a2a] object A2ARequestHandler:
  final case class Config(
    capabilities: AgentCapabilities,
    eventStore: Option[A2AEventStore],
    replayProvider: Option[A2AReplayProvider],
    eventReplayLimit: Int,
    eventStoreAppendTimeout: Duration,
    eventStoreLoadTimeout: Duration,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy)

  final case class PreparedRun(
    message: A2AMessage,
    task: A2ATask,
    bus: A2AEventBus)

  type ExecuteRun = (PreparedRun, A2AEventPublisher) => Task[Unit]
end A2ARequestHandler

private[a2a] final class A2ARequestHandler(
  config: A2ARequestHandler.Config,
  runtime: Runtime[Any],
  taskStore: A2ATaskStore,
  pushStore: A2APushNotificationStore,
  runtimeRegistry: A2ARuntimeRegistry,
  pushSender: A2APushNotificationSender,
  agentCardProvider: () => AgentCard,
  executeRun: A2ARequestHandler.ExecuteRun):

  import A2ARequestHandler.PreparedRun

  private val eventPersister: Option[EventStorePersister] =
    config.eventStore.map(EventStorePersister(_, config.eventStoreAppendTimeout))

  def agentCard: AgentCard = agentCardProvider()

  def sendMessage(
    params: A2ARequest.MessageSend,
    context: ServerCallContext,
  ): Task[A2AResponse.SendMessageResult] =
    for
      prepared <- prepare(params, context)
      result   <-
        val historyLength = params.configuration.flatMap(_.historyLength)
        val project       = (task: A2ATask) => A2ATaskStore.applyHistoryLength(task, historyLength)
        val run           =
          for
            _ <- saveInlinePushConfig(params.configuration, prepared.task.id, context)
            stream = prepared.bus.stream
            _      <- startExecution(prepared, context)
            result <-
              if params.configuration.exists(_.returnImmediately) then
                ZIO.succeed(A2AResponse.SendMessageResult.TaskResult(project(prepared.task)))
              else
                waitForFinal(prepared.task.id, stream, context)
                  .map(task => A2AResponse.SendMessageResult.TaskResult(project(task)))
          yield result
        run.onError(_ => cleanupPrepared(prepared, context))
    yield result

  def sendMessageStream(
    params: A2ARequest.MessageSend,
    context: ServerCallContext,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    requireStreaming *> {
      for
        prepared <- prepare(params, context)
        stream   <-
          val run =
            for
              _ <- saveInlinePushConfig(params.configuration, prepared.task.id, context)
              stream = prepared.bus.stream
              _ <- startExecution(prepared, context)
            yield stream
          run.onError(_ => cleanupPrepared(prepared, context))
      yield stream
    }

  def getTask(params: A2ARequest.TasksGet, context: ServerCallContext): Task[A2ATask] =
    validateHistoryLength(params.historyLength) *>
      taskStore.load(params.id, context.tenant).flatMap {
        case Some(task) => ZIO.succeed(A2ATaskStore.applyHistoryLength(task, params.historyLength))
        case None       => ZIO.fail(A2AError.taskNotFound(params.id))
      }

  def listTasks(params: A2ARequest.TasksList, context: ServerCallContext): Task[A2AResponse.ListTasksResult] =
    taskStore.list(params, context.tenant)

  def cancelTask(params: A2ARequest.TasksCancel, context: ServerCallContext): Task[A2ATask] =
    taskStore.load(params.id, context.tenant).flatMap {
      case Some(task) if task.isTerminal =>
        ZIO.fail(A2AError.taskNotCancelable(params.id))
      case Some(task) =>
        val canceled = task.copy(status = TaskStatus.canceled)
        val event    = A2AResponse.StreamEvent.TaskStatusUpdate(
          params.id,
          task.contextId,
          canceled.status,
          `final` = true,
        )
        val key = taskRuntimeKey(params.id, context)
        for
          runtimeEntry <- runtimeRegistry.markCanceled(key)
          _            <- runtimeEntry.flatMap(_._2) match
            case Some(fiber) => fiber.interrupt.unit
            case None        => ZIO.unit
          _            <- taskStore.save(canceled, context.tenant)
          awaitPersist <- eventPersister.fold[UIO[UIO[Unit]]](ZIO.succeed(ZIO.unit))(_.enqueue(event, context.tenant))
          _            <- runtimeEntry.map(_._1) match
            case Some(bus) => bus.publish(event) *> bus.finish
            case None      => ZIO.unit
          _ <- pushSender.send(event, context)
          _ <- awaitPersist
          _ <- runtimeRegistry.remove(key)
        yield canceled
      case None =>
        ZIO.fail(A2AError.taskNotFound(params.id))
    }

  def resubscribe(params: A2ARequest.TasksResubscribe, context: ServerCallContext)
    : Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    requireStreaming *> taskStore.load(params.id, context.tenant).flatMap {
      case Some(task) =>
        runtimeRegistry.bus(taskRuntimeKey(params.id, context)).flatMap {
          case Some(bus) => ZIO.succeed(ZStream.succeed(A2AResponse.StreamEvent.TaskSnapshot(task)) ++ bus.stream)
          case None      => durableReplay(task, context)
        }
      case None =>
        ZIO.fail(A2AError.taskNotFound(params.id))
    }

  private def durableReplay(task: A2ATask, context: ServerCallContext)
    : Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    val snapshot    = ZStream.succeed(A2AResponse.StreamEvent.TaskSnapshot(task))
    val notSnapshot = (event: A2AResponse.StreamEvent) => !event.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot]
    config.replayProvider match
      case Some(provider) =>
        ZIO.succeed(snapshot ++ provider.replay(task, context.tenant).filter(notSnapshot))
      case None =>
        config.eventStore match
          case Some(store) =>
            store
              .load(task.id, context.tenant, config.eventReplayLimit)
              .timeout(config.eventStoreLoadTimeout)
              .flatMap {
                case Some(events) =>
                  val replay = events.filter(notSnapshot)
                  if task.isTerminal || replay.exists(_.isFinal) then
                    ZIO.succeed(snapshot ++ ZStream.fromIterable(replay))
                  else inactiveNonTerminalReplayFailure(task, "the durable event store has no terminal event")
                case None =>
                  if task.isTerminal then ZIO.succeed(snapshot)
                  else
                    inactiveNonTerminalReplayFailure(
                      task,
                      s"event store load timed out after ${config.eventStoreLoadTimeout}",
                    )
              }
          case None =>
            if task.isTerminal then ZIO.succeed(snapshot)
            else inactiveNonTerminalReplayFailure(task, "no event store / replay provider is configured")
    end match
  end durableReplay

  private def inactiveNonTerminalReplayFailure(
    task: A2ATask,
    reason: String,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    ZIO.fail(
      A2AError.unsupportedOperation(
        s"Task ${task.id.value} has no active runtime bus and cannot be replayed to a terminal event ($reason). Poll tasks/get for status."
      )
    )

  def createPushConfig(configParam: TaskPushNotificationConfig, context: ServerCallContext)
    : Task[TaskPushNotificationConfig] =
    requirePush *> {
      val taskId = configParam.taskId.getOrElse(TaskId(""))
      if taskId.isEmpty then ZIO.fail(A2AError.invalidParams("taskId is required"))
      else
        ensureTask(taskId, context) *> config.pushNotificationUrlPolicy
          .validate(configParam.url) *> pushStore.save(taskId, context.tenant, configParam)
    }

  def getPushConfig(params: A2ARequest.PushNotificationConfigGet, context: ServerCallContext)
    : Task[TaskPushNotificationConfig] =
    requirePush *> ensureTask(params.taskId, context) *>
      pushStore.load(params.taskId, context.tenant).flatMap { configs =>
        configs.find(_.id.contains(params.id)) match
          case Some(config) => ZIO.succeed(config)
          case None         => ZIO.fail(A2AError.invalidParams(s"Push notification config not found: ${params.id}"))
      }

  def listPushConfigs(params: A2ARequest.PushNotificationConfigList, context: ServerCallContext)
    : Task[A2AResponse.PushNotificationConfigListResult] =
    requirePush *> ensureTask(params.taskId, context) *>
      pushStore
        .load(params.taskId, context.tenant)
        .map(configs => A2AResponse.PushNotificationConfigListResult(configs))

  def deletePushConfig(params: A2ARequest.PushNotificationConfigDelete, context: ServerCallContext): Task[Unit] =
    requirePush *> ensureTask(params.taskId, context) *> pushStore.delete(params.taskId, context.tenant, params.id)

  def getExtendedAgentCard(context: ServerCallContext): Task[AgentCard] =
    if config.capabilities.extendedAgentCard then ZIO.succeed(agentCard)
    else ZIO.fail(A2AError.authenticatedExtendedCardNotConfigured)

  private def prepare(params: A2ARequest.MessageSend, context: ServerCallContext): Task[PreparedRun] =
    val incoming = params.message
    val taskId   = incoming.taskId.getOrElse(TaskId.generate)
    val key      = taskRuntimeKey(taskId, context)
    for
      _        <- validateHistoryLength(params.configuration.flatMap(_.historyLength))
      _        <- validateInlinePushConfig(params.configuration)
      existing <- taskStore.load(taskId, context.tenant)
      _        <- existing match
        case Some(task) if task.isTerminal =>
          ZIO.fail(A2AError.unsupportedOperation(s"Task ${task.id.value} is terminal and cannot be modified"))
        case Some(task) if incoming.contextId.exists(_ != task.contextId) =>
          ZIO.fail(A2AError.invalidParams("contextId does not match task contextId"))
        case _ =>
          ZIO.unit
      maybeBus <- runtimeRegistry.reserve(key, config.eventReplayLimit)
      bus      <- maybeBus match
        case Some(bus) => ZIO.succeed(bus)
        case None      => ZIO.fail(A2AError.unsupportedOperation(s"Task ${taskId.value} already has an active run"))
      contextId = incoming.contextId.orElse(existing.map(_.contextId)).getOrElse(ContextId.generate)
      message   = incoming.copy(taskId = Some(taskId), contextId = Some(contextId))
      task      = existing
        .map(task => task.copy(status = TaskStatus.working(), history = task.history :+ message))
        .getOrElse(A2ATask(id = taskId, contextId = contextId, status = TaskStatus.working(), history = List(message)))
      _ <- taskStore.save(task, context.tenant)
    yield PreparedRun(message, task, bus)
    end for
  end prepare

  private def startExecution(prepared: PreparedRun, context: ServerCallContext): UIO[Unit] =
    val manager =
      ResultManager(taskStore, eventPersister, pushSender, prepared.bus, runtimeRegistry, context, prepared.message)
    val key = taskRuntimeKey(prepared.task.id, context)
    val run =
      manager.publish(A2AResponse.StreamEvent.TaskSnapshot(prepared.task)) *>
        executeRun(prepared, manager)
          .catchAll { error =>
            val detail       = Option(error.getMessage).getOrElse(error.getClass.getName)
            val errorMessage = A2AMessage
              .agentText(s"Error: $detail", Some(prepared.task.contextId))
              .copy(taskId = Some(prepared.task.id))
            manager.publish(
              A2AResponse.StreamEvent.TaskStatusUpdate(
                prepared.task.id,
                prepared.task.contextId,
                TaskStatus.failed(errorMessage),
                `final` = true,
              )
            )
          }
          .ensuring(
            runtimeRegistry.isCanceled(key).flatMap { canceled =>
              if canceled then ZIO.unit else manager.finish
            } *> runtimeRegistry.remove(key)
          )
    ZIO
      .succeed {
        Unsafe.unsafe { implicit unsafe => runtime.unsafe.fork(run) }
      }
      .flatMap(fiber => runtimeRegistry.attachFiber(key, fiber))
  end startExecution

  private def waitForFinal(
    taskId: TaskId,
    stream: ZStream[Any, Throwable, A2AResponse.StreamEvent],
    context: ServerCallContext,
  ): Task[A2ATask] =
    stream
      .filter(_.isFinal)
      .runHead
      .flatMap {
        case Some(_) => taskStore.load(taskId, context.tenant).someOrFail(A2AError.taskNotFound(taskId))
        case None    => ZIO.fail(A2AError.internalError(s"Terminal event never received for task ${taskId.value}"))
      }

  private def cleanupPrepared(prepared: PreparedRun, context: ServerCallContext): UIO[Unit] =
    runtimeRegistry.remove(taskRuntimeKey(prepared.task.id, context)) *> prepared.bus.finish

  private def saveInlinePushConfig(
    messageConfig: Option[MessageSendConfiguration],
    taskId: TaskId,
    context: ServerCallContext,
  ): Task[Unit] =
    messageConfig.flatMap(_.taskPushNotificationConfig) match
      case Some(pushConfig) =>
        validateInlinePushConfig(messageConfig) *> pushStore.save(taskId, context.tenant, pushConfig).unit
      case None =>
        ZIO.unit

  private def validateInlinePushConfig(messageConfig: Option[MessageSendConfiguration]): Task[Unit] =
    messageConfig.flatMap(_.taskPushNotificationConfig) match
      case Some(pushConfig) if agentCard.capabilities.pushNotifications =>
        config.pushNotificationUrlPolicy.validate(pushConfig.url)
      case Some(_) =>
        ZIO.fail(A2AError.pushNotificationNotSupported)
      case None =>
        ZIO.unit

  private def requirePush: Task[Unit] =
    ZIO.fail(A2AError.pushNotificationNotSupported).unless(agentCard.capabilities.pushNotifications).unit

  private def requireStreaming: Task[Unit] =
    ZIO.fail(A2AError.unsupportedOperation("Streaming not supported")).unless(agentCard.capabilities.streaming).unit

  private def validateHistoryLength(historyLength: Option[Int]): Task[Unit] =
    historyLength match
      case Some(length) if length < 0 =>
        ZIO.fail(A2AError.invalidParams(s"historyLength must be non-negative integer, got $length"))
      case _ =>
        ZIO.unit

  private def ensureTask(taskId: TaskId, context: ServerCallContext): Task[Unit] =
    taskStore.load(taskId, context.tenant).flatMap {
      case Some(_) => ZIO.unit
      case None    => ZIO.fail(A2AError.taskNotFound(taskId))
    }
end A2ARequestHandler
