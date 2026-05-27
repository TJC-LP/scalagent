package com.tjclp.scalagent.a2a

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

import scala.collection.mutable

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/**
 * JVM-side server configuration. Mirror of the JS `A2AServerLive.Config`
 * minus Claude-Agent-SDK-specific fields (`agentOptions`,
 * `invocationPreparer`) since the JVM scalagent build doesn't include the
 * Claude Agent SDK adapters.
 *
 * For CMA-backed agents, the common fields are `name`, `description`, `port`,
 * `executionOverride`, `skills`, and optionally the task/event stores.
 */
object A2AServerLive:

  final case class Config(
    name: String,
    description: String,
    host: String = "0.0.0.0",
    port: Int = 3000,
    executionMode: ExecutionMode = ExecutionMode.Default,
    taskTimeout: Option[Duration] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default,
    skills: List[AgentSkill] = Nil,
    executionOverride: Option[(A2AMessage, TaskId, ContextId, A2AEventPublisher) => Task[Unit]] = None,
    pushNotificationStore: Option[A2APushNotificationStore] = None,
    taskStore: Option[A2ATaskStore] = None,
    eventStore: Option[A2AEventStore] = None,
    replayProvider: Option[A2AReplayProvider] = None,
    eventReplayLimit: Int = 1000,
    eventStoreAppendTimeout: Duration = 2.seconds,
    eventStoreLoadTimeout: Duration = 5.seconds,
    maxRequestBodyBytes: Int = 1024 * 1024,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy = PushNotificationUrlPolicy.externalOnly):
    def url: String = s"http://$host:$port"

    def toAgentCard: AgentCard = toAgentCardAt(url)

    def toAgentCardAt(baseUrl: String): AgentCard =
      AgentCard(
        name = name,
        description = description,
        supportedInterfaces = List(
          AgentInterface.jsonRpc(baseUrl),
          AgentInterface.rest(baseUrl),
        ),
        capabilities = capabilities,
        skills = skills,
      )
  end Config

  /** Create and start a JVM A2A server. */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    for
      runtime <- ZIO.runtime[Any]
      server  <- ZIO.acquireRelease(start(config, runtime))(_.stop.ignore)
    yield server

  /** Start a JVM A2A server without scope management. */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    for
      server <- ZIO.attempt(A2AServerLiveImpl(config, runtime))
      _      <- server.start
    yield server

  /** Create a server layer. */
  def live(config: Config): ZLayer[Scope, Throwable, A2AServer] =
    ZLayer.fromZIO(create(config))
end A2AServerLive

private final class A2AEventBus(replayLimit: Int):
  private val subscribers = mutable.Set.empty[Queue[Take[Throwable, A2AResponse.StreamEvent]]]
  private var history     = Vector.empty[A2AResponse.StreamEvent]
  private var closed      = false
  private val lock        = new AnyRef

  def publish(event: A2AResponse.StreamEvent): UIO[Unit] =
    val targets = lock.synchronized {
      if closed then Nil
      else
        history =
          if replayLimit <= 0 then Vector.empty
          else (history :+ event).takeRight(replayLimit)
        subscribers.toList
    }
    ZIO.foreachDiscard(targets)(_.offer(Take.single(event))).unit

  def finish: UIO[Unit] =
    val targets = lock.synchronized {
      if closed then Nil
      else
        closed = true
        subscribers.toList
    }
    ZIO.foreachDiscard(targets)(_.offer(Take.end)).unit

  def stream: ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrapScoped {
      for
        queue <- Queue.unbounded[Take[Throwable, A2AResponse.StreamEvent]]
        state <- ZIO.acquireRelease {
          ZIO.succeed {
            lock.synchronized {
              val replay = history
              if !closed then subscribers += queue
              replay -> closed
            }
          }
        }(_ => ZIO.succeed(lock.synchronized(subscribers -= queue)).unit)
      yield
        val (replay, wasClosed) = state
        val live                =
          if wasClosed then ZStream.empty
          else ZStream.fromQueue(queue).flattenTake
        ZStream.fromIterable(replay) ++ live
    }
end A2AEventBus

private final case class A2ARuntimeEntry(
  bus: A2AEventBus,
  fiber: Option[Fiber.Runtime[Throwable, Unit]] = None,
  canceled: Boolean = false)

private final class A2ARuntimeRegistry private (
  ref: Ref.Synchronized[Map[TaskRuntimeKey, A2ARuntimeEntry]]):

  def reserve(key: TaskRuntimeKey, replayLimit: Int): UIO[Option[A2AEventBus]] =
    ref.modify { entries =>
      entries.get(key) match
        case Some(_) =>
          None -> entries
        case None =>
          val bus = A2AEventBus(replayLimit)
          Some(bus) -> entries.updated(key, A2ARuntimeEntry(bus))
    }

  def attachFiber(key: TaskRuntimeKey, fiber: Fiber.Runtime[Throwable, Unit]): UIO[Unit] =
    ref.update(entries => entries.updatedWith(key)(_.map(_.copy(fiber = Some(fiber))))).unit

  def markCanceled(key: TaskRuntimeKey): UIO[Option[(A2AEventBus, Option[Fiber.Runtime[Throwable, Unit]])]] =
    ref.modify { entries =>
      entries.get(key) match
        case Some(entry) =>
          Some((entry.bus, entry.fiber)) -> entries.updated(key, entry.copy(canceled = true))
        case None =>
          None -> entries
    }

  def isCanceled(key: TaskRuntimeKey): UIO[Boolean] =
    ref.get.map(_.get(key).exists(_.canceled))

  def bus(key: TaskRuntimeKey): UIO[Option[A2AEventBus]] =
    ref.get.map(_.get(key).map(_.bus))

  def remove(key: TaskRuntimeKey): UIO[Unit] =
    ref.update(_ - key).unit

  def interruptAll: UIO[Unit] =
    for
      entries <- ref.getAndSet(Map.empty)
      _       <- ZIO.foreachDiscard(entries.values.flatMap(_.fiber).toList)(_.interrupt).ignore
    yield ()
end A2ARuntimeRegistry

private object A2ARuntimeRegistry:
  def make: UIO[A2ARuntimeRegistry] =
    Ref.Synchronized.make(Map.empty[TaskRuntimeKey, A2ARuntimeEntry]).map(A2ARuntimeRegistry(_))

private final class PushNotificationSender(
  store: A2APushNotificationStore,
  urlPolicy: PushNotificationUrlPolicy):
  private val client         = HttpClient.newHttpClient()
  private val deliveryChains = mutable.Map.empty[(String, String), Promise[Nothing, Unit]]
  private val lock           = new AnyRef

  def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit] =
    val key = (context.tenant.getOrElse(""), event.taskId.value)
    (for
      previous <- ZIO.succeed(lock.synchronized(deliveryChains.get(key)))
      current  <- Promise.make[Nothing, Unit]
      _        <- ZIO.succeed(lock.synchronized(deliveryChains.update(key, current)))
      _        <-
        (previous.fold(ZIO.unit)(_.await) *> sendNow(event, context))
          .catchAll(error => ZIO.logWarning(s"Failed to send A2A push notification: ${error.getMessage}"))
          .ensuring(
            current.succeed(()).unit *>
              ZIO.succeed {
                lock.synchronized {
                  if deliveryChains.get(key).contains(current) then deliveryChains.remove(key)
                }
              }.unit
          )
          .forkDaemon
    yield ()).unit

  private def sendNow(event: A2AResponse.StreamEvent, context: ServerCallContext): Task[Unit] =
    store
      .load(event.taskId, context.tenant)
      .flatMap(configs => ZIO.foreachDiscard(configs)(sendOne(event, _)))

  private def sendOne(event: A2AResponse.StreamEvent, config: TaskPushNotificationConfig): Task[Unit] =
    urlPolicy.validate(config.url) *>
      ZIO.attemptBlocking {
        var builder = HttpRequest
          .newBuilder(URI.create(config.url))
          .POST(HttpRequest.BodyPublishers.ofString(event.toJson, StandardCharsets.UTF_8))
          .header("Content-Type", A2AContentType.A2AJson)
        config.authentication match
          case Some(auth) if auth.scheme.nonEmpty && auth.credentials.nonEmpty =>
            builder = builder.header("Authorization", s"${auth.scheme} ${auth.credentials}")
          case _ =>
            config.token.foreach(token => builder = builder.header("X-A2A-Notification-Token", token))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        if response.statusCode() < 200 || response.statusCode() >= 300 then
          throw RuntimeException(s"Push callback ${config.url} returned HTTP ${response.statusCode()}")
      }
end PushNotificationSender

private final class EventStorePersister(
  store: A2AEventStore,
  appendTimeout: Duration):
  private val chains = mutable.Map.empty[(String, String), Promise[Nothing, Unit]]
  private val lock   = new AnyRef

  def enqueue(event: A2AResponse.StreamEvent, tenant: Option[String]): UIO[UIO[Unit]] =
    val key = (tenant.getOrElse(""), event.taskId.value)
    for
      previous <- ZIO.succeed(lock.synchronized(chains.get(key)))
      current  <- Promise.make[Nothing, Unit]
      _        <- ZIO.succeed(lock.synchronized(chains.update(key, current)))
      _        <-
        (previous.fold(ZIO.unit)(_.await) *> appendOnce(event, tenant))
          .ensuring(
            current.succeed(()).unit *>
              ZIO.succeed {
                lock.synchronized {
                  if chains.get(key).contains(current) then chains.remove(key)
                }
              }.unit
          )
          .forkDaemon
    yield current.await

  private def appendOnce(event: A2AResponse.StreamEvent, tenant: Option[String]): UIO[Unit] =
    store
      .append(event.taskId, tenant, event)
      .timeout(appendTimeout)
      .flatMap {
        case Some(_) => ZIO.unit
        case None    =>
          ZIO.logWarning(s"[a2a-event-store] append timed out task=${event.taskId.value}")
      }
      .catchAllCause(cause =>
        ZIO.logWarning(s"[a2a-event-store] append crashed task=${event.taskId.value}: ${cause.prettyPrint}")
      )
      .unit
end EventStorePersister

private final class ResultManager(
  taskStore: A2ATaskStore,
  eventPersister: Option[EventStorePersister],
  pushSender: PushNotificationSender,
  bus: A2AEventBus,
  runtimeRegistry: A2ARuntimeRegistry,
  context: ServerCallContext,
  userMessage: A2AMessage)
    extends A2AEventPublisher:

  override def publish(event: A2AResponse.StreamEvent): UIO[Unit] =
    runtimeRegistry.isCanceled(taskRuntimeKey(event.taskId, context)).flatMap { canceled =>
      if canceled then ZIO.unit
      else
        for
          _            <- applyEvent(event)
          awaitPersist <- persistEvent(event)
          _            <- ZIO.when(event.isFinal)(awaitPersist)
          _            <- bus.publish(event)
          _            <- pushSender.send(event, context)
        yield ()
    }

  override def finish: UIO[Unit] =
    bus.finish

  private def applyEvent(event: A2AResponse.StreamEvent): UIO[Unit] =
    event match
      case A2AResponse.StreamEvent.TaskSnapshot(task) =>
        taskStore.load(task.id, context.tenant).flatMap {
          case Some(existing)
              if existing.status.state == TaskState.Canceled && task.status.state != TaskState.Canceled =>
            ZIO.unit
          case _ =>
            taskStore.save(ensureHistory(task), context.tenant)
        }
      case A2AResponse.StreamEvent.TaskStatusUpdate(taskId, _, status, _, _) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) if task.status.state == TaskState.Canceled && status.state != TaskState.Canceled =>
            ZIO.unit
          case Some(task) =>
            val history = status.message match
              case Some(message) if !task.history.exists(_.messageId == message.messageId) => task.history :+ message
              case _                                                                       => task.history
            taskStore.save(task.copy(status = status, history = history), context.tenant)
          case None =>
            ZIO.unit
        }
      case A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, _, artifact, append, _, _) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) if task.status.state == TaskState.Canceled =>
            ZIO.unit
          case Some(task) =>
            val existingIndex = task.artifacts.indexWhere(_.artifactId == artifact.artifactId)
            val artifacts     =
              if existingIndex < 0 then task.artifacts :+ artifact
              else if append then
                task.artifacts.updated(
                  existingIndex,
                  task.artifacts(existingIndex).copy(parts = task.artifacts(existingIndex).parts ++ artifact.parts),
                )
              else task.artifacts.updated(existingIndex, artifact)
            taskStore.save(task.copy(artifacts = artifacts), context.tenant)
          case None =>
            ZIO.unit
        }
      case A2AResponse.StreamEvent.TaskMessage(taskId, _, message) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) if task.status.state == TaskState.Canceled =>
            ZIO.unit
          case Some(task) if !task.history.exists(_.messageId == message.messageId) =>
            taskStore.save(task.copy(history = task.history :+ message), context.tenant)
          case _ =>
            ZIO.unit
        }

  private def persistEvent(event: A2AResponse.StreamEvent): UIO[UIO[Unit]] =
    eventPersister.fold[UIO[UIO[Unit]]](ZIO.succeed(ZIO.unit))(_.enqueue(event, context.tenant))

  private def ensureHistory(task: A2ATask): A2ATask =
    if task.history.exists(_.messageId == userMessage.messageId) then task
    else task.copy(history = userMessage :: task.history)
end ResultManager

private final class A2AJvmRequestHandler(
  config: A2AServerLive.Config,
  runtime: Runtime[Any],
  taskStore: A2ATaskStore,
  pushStore: A2APushNotificationStore,
  runtimeRegistry: A2ARuntimeRegistry,
  agentCardProvider: () => AgentCard):

  private val pushSender = PushNotificationSender(pushStore, config.pushNotificationUrlPolicy)
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

  private final case class PreparedRun(
    message: A2AMessage,
    task: A2ATask,
    bus: A2AEventBus)

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
        execute(prepared, manager)
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

  private def execute(prepared: PreparedRun, publisher: A2AEventPublisher): Task[Unit] =
    config.executionOverride match
      case Some(overrideRun) =>
        withTaskTimeout(
          prepared.task.id,
          overrideRun(prepared.message, prepared.task.id, prepared.task.contextId, publisher),
        )
      case None =>
        ZIO.fail(
          A2AError.invalidRequest("This JVM A2AServerLive requires `executionOverride` to be configured")
        )

  private def withTaskTimeout[A](taskId: TaskId, effect: Task[A]): Task[A] =
    config.taskTimeout match
      case Some(timeout) =>
        effect.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} timed out after $timeout"))(timeout)
      case None =>
        effect

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
end A2AJvmRequestHandler

/** Live implementation of A2A Server using zio-http. */
private[a2a] final class A2AServerLiveImpl(
  config: A2AServerLive.Config,
  runtime: Runtime[Any])
    extends A2AServer:

  private val taskStore = config.taskStore.getOrElse(A2ATaskStore.inMemory)
  private val pushStore = config.pushNotificationStore.getOrElse(A2APushNotificationStore.inMemory)
  private val runtimeRegistry: A2ARuntimeRegistry =
    Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(A2ARuntimeRegistry.make).getOrThrow() }
  private val requestHandler =
    A2AJvmRequestHandler(config, runtime, taskStore, pushStore, runtimeRegistry, () => agentCard)

  private val serverFiberRef: Ref.Synchronized[Option[Fiber.Runtime[Throwable, Unit]]] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(Ref.Synchronized.make(Option.empty[Fiber.Runtime[Throwable, Unit]])).getOrThrow()
    }

  def agentCard: AgentCard = config.toAgentCardAt(url)

  def url: String = config.url

  def start: Task[Unit] =
    val server = Server
      .serve(a2aRoutes)
      .provide(
        ZLayer.succeed(Server.Config.default.binding(config.host, config.port)),
        Server.live,
      )
    for
      fiber <- server.fork
      _     <- serverFiberRef.set(Some(fiber))
    yield ()

  def stop: Task[Unit] =
    runtimeRegistry.interruptAll *>
      serverFiberRef.modifyZIO {
        case Some(fiber) => fiber.interruptFork.as(((), None))
        case None        => ZIO.succeed(((), None))
      }

  private def a2aRoutes: Routes[Any, Nothing] =
    Routes.singleton(handler { (_: Path, request: Request) => handleHttp(request) })

  private[a2a] def handleHttp(request: Request): UIO[Response] =
    val pathname = request.path.encode
    if pathname == A2APaths.AgentCard && request.method == Method.GET then ZIO.succeed(jsonResponse(agentCard))
    else if pathname == "/" && request.method == Method.POST then
      readBody(request)
        .foldZIO(
          error => ZIO.succeed(jsonRpcErrorResponse(None, toA2AError(error))),
          body => handleJsonRpc(body, contextFrom(request, None)),
        )
    else
      routeRest(request) match
        case Some(effect) => effect
        case None         => ZIO.succeed(textResponse("Not Found", 404, "text/plain"))

  private[a2a] def dispatchJsonRpc(request: JsonRpcRequest): Task[JsonRpcResponse] =
    dispatchJsonRpcSingle(request, ServerCallContext())

  private def handleJsonRpc(body: String, context: ServerCallContext): UIO[Response] =
    body.fromJson[JsonRpcRequest] match
      case Left(msg) =>
        ZIO.succeed(jsonRpcErrorResponse(None, A2AError.parseError(msg)))
      case Right(request) =>
        val isStreaming = request.method == A2AMethod.MessageStream || request.method == A2AMethod.TasksResubscribe
        val routed      =
          if isStreaming then handleJsonRpcStream(request, context)
          else dispatchJsonRpcSingle(request, context).map(response => jsonResponse(response))
        (validateServiceParameters(context, A2ATransport.JSONRPC) *> routed)
          .catchAll(error => ZIO.succeed(jsonRpcErrorResponse(request.id, toA2AError(error))))

  private def dispatchJsonRpcSingle(request: JsonRpcRequest, context: ServerCallContext): Task[JsonRpcResponse] =
    request.method match
      case A2AMethod.MessageSend =>
        paramsAs[A2ARequest.MessageSend](request)
          .flatMap(requestHandler.sendMessage(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksGet =>
        paramsAs[A2ARequest.TasksGet](request)
          .flatMap(requestHandler.getTask(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksList =>
        paramsAs[A2ARequest.TasksList](request)
          .flatMap(requestHandler.listTasks(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksCancel =>
        paramsAs[A2ARequest.TasksCancel](request)
          .flatMap(requestHandler.cancelTask(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigSet =>
        paramsAs[TaskPushNotificationConfig](request)
          .flatMap(requestHandler.createPushConfig(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigGet =>
        paramsAs[A2ARequest.PushNotificationConfigGet](request)
          .flatMap(requestHandler.getPushConfig(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigList =>
        paramsAs[A2ARequest.PushNotificationConfigList](request)
          .flatMap(requestHandler.listPushConfigs(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigDelete =>
        paramsAs[A2ARequest.PushNotificationConfigDelete](request)
          .flatMap(requestHandler.deletePushConfig(_, withTenantFromParams(context, request.params)))
          .as(JsonRpcResponse.success(request.id, Json.Obj()))
      case A2AMethod.GetAuthenticatedExtendedCard =>
        requestHandler.getExtendedAgentCard(context).map(JsonRpcResponse.success(request.id, _))
      case other =>
        ZIO.fail(A2AError.methodNotFound(other))

  private def handleJsonRpcStream(request: JsonRpcRequest, context: ServerCallContext): Task[Response] =
    val streamTask =
      request.method match
        case A2AMethod.MessageStream =>
          paramsAs[A2ARequest.MessageSend](request)
            .flatMap(requestHandler.sendMessageStream(_, withTenantFromParams(context, request.params)))
        case A2AMethod.TasksResubscribe =>
          paramsAs[A2ARequest.TasksResubscribe](request)
            .flatMap(requestHandler.resubscribe(_, withTenantFromParams(context, request.params)))
        case other =>
          ZIO.fail(A2AError.methodNotFound(other))
    streamTask.map { stream =>
      sseResponse(
        stream.map(event => JsonRpcResponse.success(request.id, event).toJson),
        isJsonRpc = true,
      )
    }

  private def paramsAs[A: JsonDecoder](request: JsonRpcRequest): Task[A] =
    ZIO.fromEither(
      request.params.toRight(A2AError.invalidParams("Missing params")).flatMap(_.as[A].left.map(A2AError.invalidParams))
    )

  private def withTenantFromParams(context: ServerCallContext, params: Option[Json]): ServerCallContext =
    params
      .flatMap(_.asObject)
      .flatMap(_.toMap.get("tenant"))
      .flatMap(_.asString)
      .filter(_.nonEmpty)
      .fold(context)(tenant => context.copy(tenant = Some(tenant)))

  private def routeRest(request: Request): Option[UIO[Response]] =
    val (tenant, path) = splitTenant(request.path.encode)
    val query          = request.url.queryParams

    def queryString(name: String): Option[String] =
      query.getAll(name).headOption

    def queryInt(name: String): Task[Option[Int]] =
      queryString(name) match
        case Some(value) =>
          value.toIntOption match
            case Some(parsed) => ZIO.succeed(Some(parsed))
            case None         => ZIO.fail(A2AError.invalidParams(s"$name must be a valid integer"))
        case None =>
          ZIO.succeed(None)

    def queryBool(name: String): Task[Option[Boolean]] =
      queryString(name) match
        case Some(value) =>
          value.toBooleanOption match
            case Some(parsed) => ZIO.succeed(Some(parsed))
            case None         => ZIO.fail(A2AError.invalidParams(s"$name must be a valid boolean"))
        case None =>
          ZIO.succeed(None)

    def queryStatus(name: String): Task[Option[TaskState]] =
      queryString(name) match
        case Some(value) =>
          ZIO
            .fromEither(
              Json.Str(value).as[TaskState].left.map(error => A2AError.invalidParams(s"Invalid $name: $error"))
            )
            .map(Some(_))
        case None =>
          ZIO.succeed(None)

    def rawSegments(value: String): List[String] =
      val stripped = value.stripPrefix("/")
      if stripped.isEmpty then Nil else stripped.split("/", -1).toList

    def nonEmptyTaskId(raw: String): Task[TaskId] =
      if raw.isEmpty then ZIO.fail(A2AError.invalidParams("Missing task ID"))
      else ZIO.succeed(TaskId(raw))

    def nonEmptyConfigId(raw: String): Task[String] =
      if raw.isEmpty then ZIO.fail(A2AError.invalidParams("Missing push notification config ID"))
      else ZIO.succeed(raw)

    val baseContext = contextFrom(request, tenant)
    val context     = baseContext.copy(
      requestedVersion = baseContext.requestedVersion
        .orElse(queryString(A2AHeader.Version))
        .orElse(queryString("a2aVersion"))
    )

    def bodyAs[A: JsonDecoder]: Task[A] =
      readBody(request).flatMap { body => ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest)) }

    def json[A: JsonEncoder](effect: Task[A], status: Int = 200): UIO[Response] =
      (validateServiceParameters(context, A2ATransport.HTTP_JSON) *> effect)
        .map(value => jsonResponse(value, status, A2AContentType.A2AJson))
        .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))

    val segments = rawSegments(path)

    (request.method, segments) match
      case (Method.POST, List("message:send")) =>
        Some(json(bodyAs[A2ARequest.MessageSend].flatMap(requestHandler.sendMessage(_, context))))
      case (Method.POST, List("message:stream")) =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            bodyAs[A2ARequest.MessageSend]
              .flatMap(requestHandler.sendMessageStream(_, context))
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case (Method.GET, List("tasks")) =>
        val params =
          for
            status           <- queryStatus("status")
            pageSize         <- queryInt("pageSize")
            historyLength    <- queryInt("historyLength")
            includeArtifacts <- queryBool("includeArtifacts")
          yield A2ARequest.TasksList(
            contextId = queryString("contextId").filter(_.nonEmpty).map(ContextId(_)),
            status = status,
            pageSize = pageSize,
            pageToken = queryString("pageToken"),
            historyLength = historyLength,
            statusTimestampAfter = queryString("statusTimestampAfter"),
            includeArtifacts = includeArtifacts,
            tenant = tenant,
          )
        Some(json(params.flatMap(requestHandler.listTasks(_, context))))
      case (Method.GET, List("tasks", rawTaskId)) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap { taskId =>
              queryInt("historyLength").flatMap(historyLength =>
                requestHandler.getTask(A2ARequest.TasksGet(taskId, historyLength, tenant), context)
              )
            }
          )
        )
      case (Method.POST, List("tasks", rawTaskAction)) if rawTaskAction.endsWith(":cancel") =>
        Some(
          json(
            nonEmptyTaskId(rawTaskAction.stripSuffix(":cancel")).flatMap(taskId =>
              requestHandler.cancelTask(A2ARequest.TasksCancel(taskId, tenant = tenant), context)
            ),
            status = 202,
          )
        )
      case (verb, List("tasks", rawTaskAction))
          if (verb == Method.GET || verb == Method.POST) && rawTaskAction.endsWith(":subscribe") =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            nonEmptyTaskId(rawTaskAction.stripSuffix(":subscribe"))
              .flatMap(taskId => requestHandler.resubscribe(A2ARequest.TasksResubscribe(taskId, tenant), context))
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case (Method.POST, List("tasks", rawTaskId, "pushNotificationConfigs")) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap(taskId =>
              bodyAs[TaskPushNotificationConfig]
                .map(_.copy(taskId = Some(taskId), tenant = tenant))
                .flatMap(requestHandler.createPushConfig(_, context))
            ),
            status = 201,
          )
        )
      case (Method.GET, List("tasks", rawTaskId, "pushNotificationConfigs")) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap(taskId =>
              requestHandler.listPushConfigs(A2ARequest.PushNotificationConfigList(taskId, tenant = tenant), context)
            )
          )
        )
      case (Method.GET, List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
        Some(
          json(
            for
              taskId   <- nonEmptyTaskId(rawTaskId)
              configId <- nonEmptyConfigId(rawConfigId)
              config   <- requestHandler
                .getPushConfig(A2ARequest.PushNotificationConfigGet(taskId, configId, tenant), context)
            yield config
          )
        )
      case (Method.DELETE, List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            (for
              taskId   <- nonEmptyTaskId(rawTaskId)
              configId <- nonEmptyConfigId(rawConfigId)
              _        <- requestHandler
                .deletePushConfig(A2ARequest.PushNotificationConfigDelete(taskId, configId, tenant), context)
            yield ())
              .as(emptyResponse(204)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case (Method.GET, List("extendedAgentCard")) =>
        Some(json(requestHandler.getExtendedAgentCard(context)))
      case (_, "tasks" :: "" :: _) =>
        Some(json[A2ATask](ZIO.fail(A2AError.invalidParams("Missing task ID"))))
      case _ =>
        None
    end match
  end routeRest

  private def splitTenant(pathname: String): (Option[String], String) =
    val knownPrefixes = Set("message:send", "message:stream", "tasks", "extendedAgentCard")
    val stripped      = pathname.stripPrefix("/")
    val segments      = if stripped.isEmpty then Nil else stripped.split("/", -1).toList
    segments match
      case first :: rest if first.nonEmpty && !knownPrefixes.contains(first) =>
        Some(first) -> ("/" + rest.mkString("/"))
      case _ =>
        None -> pathname

  private def contextFrom(request: Request, tenant: Option[String]): ServerCallContext =
    def header(name: String): Option[String] =
      request.headers.get(name)
    ServerCallContext(
      tenant = tenant,
      requestedVersion = header(A2AHeader.Version),
      requestedExtensions = header(A2AHeader.StandardExtensions)
        .orElse(header(A2AHeader.Extensions))
        .toList
        .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)),
    )

  private def readBody(request: Request): Task[String] =
    val maxBytes = config.maxRequestBodyBytes
    request.headers.get("content-length").flatMap(_.toLongOption) match
      case Some(length) if maxBytes > 0 && length > maxBytes =>
        ZIO.fail(A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit"))
      case _ =>
        val bytes =
          if maxBytes > 0 then
            request.body.asStream.take(maxBytes.toLong + 1L).runCollect.flatMap { chunk =>
              if chunk.length > maxBytes then
                ZIO.fail(A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit"))
              else ZIO.succeed(chunk)
            }
          else request.body.asChunk
        bytes.map(chunk => String(chunk.toArray, StandardCharsets.UTF_8))

  private def validateServiceParameters(context: ServerCallContext, binding: A2ATransport): Task[Unit] =
    validateVersion(context, binding) *> validateRequiredExtensions(context)

  private def validateVersion(context: ServerCallContext, binding: A2ATransport): Task[Unit] =
    val supported = agentCard.supportedInterfaces.filter(_.protocolBinding == binding).map(_.protocolVersion).toSet
    val version   = context.requestedVersion.getOrElse("0.3")
    if supported.contains(version) then ZIO.unit
    else ZIO.fail(A2AError.versionNotSupported(version))

  private def validateRequiredExtensions(context: ServerCallContext): Task[Unit] =
    val requested = context.requestedExtensions.toSet
    config.capabilities.extensions.find(extension => extension.required && !requested.contains(extension.uri)) match
      case Some(extension) => ZIO.fail(A2AError.extensionSupportRequired(extension.uri))
      case None            => ZIO.unit

  private def jsonRpcErrorResponse(id: Option[JsonRpcId], error: A2AError): Response =
    jsonResponse(JsonRpcResponse.fromA2AError(id, error))

  private def jsonResponse[A: JsonEncoder](
    value: A,
    status: Int = 200,
    contentType: String = A2AContentType.Json,
  ): Response =
    textResponse(value.toJson, status, contentType)

  private def restErrorResponse(error: A2AError): Response =
    val status = error.code match
      case A2AErrorCode.TaskNotFound => 404
      case _                         => 400
    textResponse(restErrorResponseBody(error, status).toJson, status, A2AContentType.A2AJson)

  private def restErrorResponseBody(error: A2AError, status: Int = 400): Json =
    Json.Obj(
      "error" -> Json.Obj(
        "code"    -> Json.Num(java.math.BigDecimal.valueOf(status.toLong)),
        "status"  -> Json.Str(if status == 404 then "NOT_FOUND" else "INVALID_ARGUMENT"),
        "message" -> Json.Str(error.message),
        "details" -> Json.Arr(
          Json.Obj(
            "@type"  -> Json.Str("type.googleapis.com/google.rpc.ErrorInfo"),
            "reason" -> Json.Str(errorReason(error.code)),
            "domain" -> Json.Str("a2a-protocol.org"),
          )
        ),
      )
    )

  private def errorReason(code: Int): String =
    code match
      case A2AErrorCode.TaskNotFound                 => "TASK_NOT_FOUND"
      case A2AErrorCode.TaskNotCancelable            => "TASK_NOT_CANCELABLE"
      case A2AErrorCode.PushNotificationNotSupported => "PUSH_NOTIFICATION_NOT_SUPPORTED"
      case A2AErrorCode.UnsupportedOperation         => "UNSUPPORTED_OPERATION"
      case A2AErrorCode.ContentTypeNotSupported      => "CONTENT_TYPE_NOT_SUPPORTED"
      case A2AErrorCode.InvalidAgentResponse         => "INVALID_AGENT_RESPONSE"
      case A2AErrorCode.VersionNotSupported          => "VERSION_NOT_SUPPORTED"
      case A2AErrorCode.ExtensionSupportRequired     => "EXTENSION_SUPPORT_REQUIRED"
      case _                                         => "INVALID_PARAMS"

  private def emptyResponse(status: Int): Response =
    Response(status = Status.fromInt(status))

  private def textResponse(
    body: String,
    status: Int,
    contentType: String,
  ): Response =
    Response(
      status = Status.fromInt(status),
      headers = Headers("Content-Type", contentType),
      body = Body.fromString(body, StandardCharsets.UTF_8),
    )

  private def sseResponse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
  ): Response =
    val wire =
      stream
        .map(data => s"data: $data\n\n")
        .catchAll(error => ZStream.succeed(s"event: error\ndata: ${streamErrorJson(error, isJsonRpc)}\n\n"))
        .map(data => data: CharSequence)
    Response(
      status = Status.Ok,
      headers = Headers(
        Header.Custom("Content-Type", A2AContentType.Sse),
        Header.Custom("Cache-Control", "no-cache"),
        Header.Custom("Connection", "keep-alive"),
        Header.Custom("X-Accel-Buffering", "no"),
      ),
      body = Body.fromCharSequenceStreamChunked(wire),
    )

  private def streamErrorJson(error: Throwable, isJsonRpc: Boolean): String =
    val a2aError = toA2AError(error)
    if isJsonRpc then JsonRpcResponse.fromA2AError(None, a2aError).toJson
    else restErrorResponseBody(a2aError).toJson

  private def toA2AError(error: Throwable): A2AError =
    error match
      case err: A2AError => err
      case other         => A2AError.internalError(Option(other.getMessage).getOrElse(other.getClass.getName))
end A2AServerLiveImpl
