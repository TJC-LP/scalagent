package com.tjclp.scalagent.interop.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import zio.*
import zio.stream.*
import com.tjclp.scalagent.a2a.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.errors.AgentError

class A2AInterpreterSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private final class CountingA2AClient(events: List[A2AResponse.StreamEvent]) extends A2AClient:
    var streamCalls: Int = 0

    override def agentCard: Task[AgentCard] =
      ZIO.succeed(AgentCard.minimal("Remote", "Remote test agent", "http://example.test"))

    override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
      ZIO.dieMessage("unused in test")

    override def stream(message: A2AMessage, config: Option[MessageSendConfiguration]): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      streamCalls += 1
      ZStream.fromIterable(events)

    override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
      ZIO.dieMessage("unused in test")

    override def listTasks(params: A2ARequest.TasksList): Task[A2AResponse.ListTasksResult] =
      ZIO.dieMessage("unused in test")

    override def cancelTask(taskId: TaskId): Task[A2ATask] =
      ZIO.dieMessage("unused in test")

    override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.empty

    override def getAgentCard: Task[AgentCard] =
      agentCard

    override def createTaskPushNotificationConfig(
      taskId: TaskId,
      config: TaskPushNotificationConfig,
    ): Task[TaskPushNotificationConfig] =
      ZIO.dieMessage("unused in test")

    override def getTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[TaskPushNotificationConfig] =
      ZIO.dieMessage("unused in test")

    override def listTaskPushNotificationConfigs(taskId: TaskId): Task[List[TaskPushNotificationConfig]] =
      ZIO.dieMessage("unused in test")

    override def deleteTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
      ZIO.dieMessage("unused in test")

  private final class InterruptibleA2AClient(
      started: Promise[Nothing, Unit],
      interrupted: Ref[Boolean]
  ) extends A2AClient:
    override def agentCard: Task[AgentCard] =
      ZIO.succeed(AgentCard.minimal("Remote", "Remote test agent", "http://example.test"))

    override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
      ZIO.dieMessage("unused in test")

    override def stream(message: A2AMessage, config: Option[MessageSendConfiguration]): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.unwrap {
        started.succeed(()).as(
          ZStream.acquireReleaseWith(ZIO.unit)(_ => interrupted.set(true)).flatMap(_ => ZStream.never)
        )
      }

    override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
      ZIO.dieMessage("unused in test")

    override def listTasks(params: A2ARequest.TasksList): Task[A2AResponse.ListTasksResult] =
      ZIO.dieMessage("unused in test")

    override def cancelTask(taskId: TaskId): Task[A2ATask] =
      ZIO.dieMessage("unused in test")

    override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.empty

    override def getAgentCard: Task[AgentCard] =
      agentCard

    override def createTaskPushNotificationConfig(
      taskId: TaskId,
      config: TaskPushNotificationConfig,
    ): Task[TaskPushNotificationConfig] =
      ZIO.dieMessage("unused in test")

    override def getTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[TaskPushNotificationConfig] =
      ZIO.dieMessage("unused in test")

    override def listTaskPushNotificationConfigs(taskId: TaskId): Task[List[TaskPushNotificationConfig]] =
      ZIO.dieMessage("unused in test")

    override def deleteTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
      ZIO.dieMessage("unused in test")

  test("events and result share one underlying remote stream"):
    val taskId = TaskId("task-123")
    val contextId = ContextId("ctx-456")
    val client = new CountingA2AClient(
      List(
        A2AResponse.StreamEvent.TaskStatusUpdate(
          taskId,
          contextId,
          TaskStatus.working(Some(A2AMessage.agentText("Working", Some(contextId)))),
          `final` = false
        ),
        A2AResponse.StreamEvent.TaskStatusUpdate(
          taskId,
          contextId,
          TaskStatus.completed(A2AMessage.agentText("Done", Some(contextId))),
          `final` = true
        )
      )
    )

    val program =
      for
        remote <- A2AInterpreter.fromClient(client)
        run = remote.run((), "hello", ExecutionPolicy.unbounded)
        result <- ZIO.scoped {
          for
            events <- run.events.runCollect.map(_.toList)
            output <- run.result
          yield (events, output)
        }
      yield result

    runTask(program).map { case (events, output) =>
      assertEquals(output, "Done")
      assert(events.exists {
        case AgentEvent.Status(text) => text.contains("Working")
        case _                       => false
      })
      assert(events.exists {
        case AgentEvent.Completed(summary) => summary.resultText.contains("Done")
        case _                             => false
      })
      assertEquals(client.streamCalls, 1)
    }

  test("missing final result text fails the run"):
    val taskId = TaskId("task-124")
    val contextId = ContextId("ctx-457")
    val client = new CountingA2AClient(
      List(
        A2AResponse.StreamEvent.TaskStatusUpdate(
          taskId,
          contextId,
          TaskStatus.completed(A2AMessage.multi(A2ARole.Agent)),
          `final` = true
        )
      )
    )

    val program =
      for
        remote <- A2AInterpreter.fromClient(client)
        run = remote.run((), "hello", ExecutionPolicy.unbounded)
        result <- ZIO.scoped(run.result.either)
      yield result

    runTask(program).map { result =>
      assert(result.left.exists(_.isInstanceOf[AgentError.Unknown]))
      assertEquals(client.streamCalls, 1)
    }

  test("closing the scope interrupts the underlying A2A stream"):
    val program =
      for
        started <- Promise.make[Nothing, Unit]
        interrupted <- Ref.make(false)
        remote <- A2AInterpreter.fromClient(new InterruptibleA2AClient(started, interrupted))
        _ <- ZIO.scoped {
          for
            fiber <- remote.run((), "hello", ExecutionPolicy.unbounded).result.fork
            _ <- started.await
            _ <- fiber.interrupt
          yield ()
        }
        wasInterrupted <- interrupted.get
      yield wasInterrupted

    runTask(program).map { wasInterrupted =>
      assert(wasInterrupted)
    }
