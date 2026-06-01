package com.tjclp.scalagent.a2a

import java.util.concurrent.TimeoutException

import scala.collection.mutable

import zio.*
import zio.stream.*

private[a2a] final class A2AEventBus(replayLimit: Int):
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

private[a2a] final case class A2ARuntimeEntry(
  bus: A2AEventBus,
  fiber: Option[Fiber.Runtime[Throwable, Unit]] = None,
  canceled: Boolean = false)

private[a2a] final class A2ARuntimeRegistry private (
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

private[a2a] object A2ARuntimeRegistry:
  def make: UIO[A2ARuntimeRegistry] =
    Ref.Synchronized.make(Map.empty[TaskRuntimeKey, A2ARuntimeEntry]).map(A2ARuntimeRegistry(_))

private[a2a] object A2AServerLifecycle:
  def create(startWithRuntime: Runtime[Any] => Task[A2AServer]): ZIO[Scope, Throwable, A2AServer] =
    for
      runtime <- ZIO.runtime[Any]
      server  <- ZIO.acquireRelease(startWithRuntime(runtime))(_.stop.ignore)
    yield server

  def start(makeServer: A2ARuntimeRegistry => Task[A2AServer]): Task[A2AServer] =
    for
      registry <- A2ARuntimeRegistry.make
      server   <- makeServer(registry)
      _        <- server.start
    yield server

  def live(createServer: ZIO[Scope, Throwable, A2AServer]): ZLayer[Scope, Throwable, A2AServer] =
    ZLayer.fromZIO(createServer)

  def startOnce[A](resourceRef: Ref.Synchronized[Option[A]])(acquire: Task[A]): Task[Unit] =
    resourceRef.modifyZIO {
      case Some(resource) => ZIO.succeed(((), Some(resource)))
      case None           => acquire.map(resource => ((), Some(resource)))
    }

  def stopOnce[A](resourceRef: Ref.Synchronized[Option[A]])(release: A => Task[Unit]): Task[Unit] =
    resourceRef.modifyZIO {
      case Some(resource) => release(resource).as(((), None))
      case None           => ZIO.succeed(((), None))
    }
end A2AServerLifecycle

private[a2a] trait A2APushNotificationSender:
  def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit]

private[a2a] trait A2APushNotificationPoster:
  def post(
    event: A2AResponse.StreamEvent,
    config: TaskPushNotificationConfig,
    headers: List[(String, String)],
  ): Task[Unit]

private[a2a] object A2APushNotificationSender:
  private val ContentTypeHeader  = "Content-Type"
  private val Authorization      = "Authorization"
  private val NotificationToken  = "X-A2A-Notification-Token"
  private val DeliveryLogMessage = "Failed to send A2A push notification"

  def live(
    store: A2APushNotificationStore,
    urlPolicy: PushNotificationUrlPolicy,
    poster: A2APushNotificationPoster,
    postTimeout: Duration = A2AServerDefaults.PushNotificationPostTimeout,
    retrySchedule: Schedule[Any, Throwable, Any] = defaultRetrySchedule,
  ): A2APushNotificationSender =
    OrderedA2APushNotificationSender(store, urlPolicy, poster, postTimeout, retrySchedule)

  private def defaultRetrySchedule: Schedule[Any, Throwable, Any] =
    Schedule.recurs(A2AServerDefaults.PushNotificationMaxRetries) &&
      Schedule.exponential(A2AServerDefaults.PushNotificationRetryBaseDelay)

  def callbackHeaders(config: TaskPushNotificationConfig): List[(String, String)] =
    val authHeader =
      config.authentication match
        case Some(auth) if auth.scheme.nonEmpty && auth.credentials.nonEmpty =>
          Some(Authorization -> s"${auth.scheme} ${auth.credentials}")
        case _ =>
          config.token.map(NotificationToken -> _)
    (ContentTypeHeader -> A2AContentType.A2AJson) :: authHeader.toList

  private final class OrderedA2APushNotificationSender(
    store: A2APushNotificationStore,
    urlPolicy: PushNotificationUrlPolicy,
    poster: A2APushNotificationPoster,
    postTimeout: Duration,
    retrySchedule: Schedule[Any, Throwable, Any])
      extends A2APushNotificationSender:
    private val chains = mutable.Map.empty[(String, String), Promise[Nothing, Unit]]
    private val lock   = new AnyRef

    def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit] =
      val key = (context.tenant.getOrElse(""), event.taskId.value)
      (for
        previous <- ZIO.succeed(lock.synchronized(chains.get(key)))
        current  <- Promise.make[Nothing, Unit]
        _        <- ZIO.succeed(lock.synchronized(chains.update(key, current)))
        _        <-
          (previous.fold(ZIO.unit)(_.await) *> sendNow(event, context))
            .catchAll(error => ZIO.logWarning(s"$DeliveryLogMessage: ${error.getMessage}"))
            .ensuring(
              current.succeed(()).unit *>
                ZIO.succeed {
                  lock.synchronized {
                    if chains.get(key).contains(current) then chains.remove(key)
                  }
                }.unit
            )
            .forkDaemon
      yield ()).unit

    private def sendNow(event: A2AResponse.StreamEvent, context: ServerCallContext): Task[Unit] =
      store
        .load(event.taskId, context.tenant)
        .flatMap(configs => ZIO.foreachDiscard(configs)(deliver(event, _)))

    private def deliver(event: A2AResponse.StreamEvent, config: TaskPushNotificationConfig): Task[Unit] =
      urlPolicy.validate(config.url) *>
        poster
          .post(event, config, callbackHeaders(config))
          .timeoutFail(
            TimeoutException(
              s"A2A push notification callback ${config.url} timed out after $postTimeout"
            )
          )(postTimeout)
          .retry(retrySchedule)
  end OrderedA2APushNotificationSender
end A2APushNotificationSender

private[a2a] final class EventStorePersister(
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

private[a2a] final class ResultManager(
  taskStore: A2ATaskStore,
  eventPersister: Option[EventStorePersister],
  pushSender: A2APushNotificationSender,
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
          _            <- ZIO.when(event.closesStream)(bus.finish)
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
          case Some(task) if task.status.state.isTerminal =>
            ZIO.unit
          case Some(task) =>
            taskStore.save(task.copy(status = status), context.tenant)
          case None =>
            ZIO.unit
        }
      case A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, _, artifact, append, _, _) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) if task.status.state.isTerminal =>
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
      case A2AResponse.StreamEvent.Message(_) =>
        ZIO.unit
      case A2AResponse.StreamEvent.TaskMessage(taskId, _, message) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) if task.status.state.isTerminal =>
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
