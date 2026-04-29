package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import munit.FunSuite
import zio.*
import zio.stream.*

class A2AClientSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def workingTask(taskId: TaskId = TaskId("task-1")): A2ATask =
    A2ATask(
      id = taskId,
      contextId = ContextId("ctx-1"),
      status = TaskStatus.working(),
    )

  private def completedTask(taskId: TaskId = TaskId("task-1")): A2ATask =
    val response = A2AMessage.agentText("done", Some(ContextId("ctx-1"))).copy(taskId = Some(taskId))
    A2ATask(
      id = taskId,
      contextId = ContextId("ctx-1"),
      status = TaskStatus.completed(response),
    )

  private class RecordingClient extends A2AClient:
    var seenConfig: Option[MessageSendConfiguration] = None

    override def agentCard: Task[AgentCard] =
      ZIO.succeed(AgentCard.minimal("Remote", "Remote test agent", "http://example.test"))

    override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
      seenConfig = config
      ZIO.succeed(workingTask())

    override def stream(
      message: A2AMessage,
      config: Option[MessageSendConfiguration],
    ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.empty

    override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
      ZIO.succeed(completedTask(taskId))

    override def cancelTask(taskId: TaskId): Task[A2ATask] =
      ZIO.succeed(completedTask(taskId))

    override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.empty

    override def getAgentCard: Task[AgentCard] =
      agentCard

    override def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig] =
      ZIO.succeed(config)

    override def getPushNotificationConfig(taskId: TaskId, configId: Option[String]): Task[PushNotificationConfig] =
      ZIO.dieMessage("unused in test")

    override def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]] =
      ZIO.succeed(Nil)

    override def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
      ZIO.unit

  test("submit forces non-blocking message/send"):
    val client = new RecordingClient

    runTask(client.submit(A2AMessage.userText("hello"))).map { _ =>
      assertEquals(client.seenConfig.flatMap(_.blocking), Some(false))
    }

  test("sendAndPoll submits then polls until terminal state"):
    val client = new RecordingClient

    runTask(client.sendAndPoll(A2AMessage.userText("hello"), pollEvery = 1.millis)).map { task =>
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(client.seenConfig.flatMap(_.blocking), Some(false))
    }
end A2AClientSpec
