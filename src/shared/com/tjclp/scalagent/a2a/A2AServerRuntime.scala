package com.tjclp.scalagent.a2a

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

private[a2a] trait A2APushNotificationSender:
  def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit]

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
