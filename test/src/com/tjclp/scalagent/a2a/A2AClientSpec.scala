package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.util.Random
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

    override def listTasks(params: A2ARequest.TasksList): Task[A2AResponse.ListTasksResult] =
      ZIO.succeed(A2AResponse.ListTasksResult(Nil))

    override def cancelTask(taskId: TaskId): Task[A2ATask] =
      ZIO.succeed(completedTask(taskId))

    override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.empty

    override def getAgentCard: Task[AgentCard] =
      agentCard

    override def createTaskPushNotificationConfig(
      taskId: TaskId,
      config: TaskPushNotificationConfig,
    ): Task[TaskPushNotificationConfig] =
      ZIO.succeed(config)

    override def getTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[TaskPushNotificationConfig] =
      ZIO.dieMessage("unused in test")

    override def listTaskPushNotificationConfigs(taskId: TaskId): Task[List[TaskPushNotificationConfig]] =
      ZIO.succeed(Nil)

    override def deleteTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
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

  test("JSON-RPC response id mismatch fails the client"):
    val port = 45000 + Random.nextInt(1000)
    val responseBody =
      """{"jsonrpc":"2.0","id":999,"result":{"tasks":[],"pageSize":0,"totalSize":0}}"""

    val program =
      ZIO.scoped {
        for
          server <- ZIO.acquireRelease(
            ZIO.attempt {
              val Response = js.Dynamic.global.Response
              TestBun.serve(
                js.Dynamic.literal(
                  hostname = "127.0.0.1",
                  port = port,
                  fetch = { (_: js.Dynamic) =>
                    js.Promise.resolve(
                      js.Dynamic.newInstance(Response)(
                        responseBody,
                        js.Dynamic.literal(status = 200, headers = js.Dynamic.literal(`Content-Type` = A2AContentType.Json)),
                      )
                    )
                  }: js.Function1[js.Dynamic, js.Promise[js.Dynamic]],
                )
              )
            }
          )(server => ZIO.attempt(server.stop()).ignore)
          client <- A2AClient.fromCard(AgentCard.minimal("Mismatch", "Mismatch test", s"http://127.0.0.1:$port"))
          result <- client.listTasks().either
        yield result
      }

    runTask(program).map { result =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidAgentResponse && error.message.contains("id mismatch")
        case _               => false
      })
    }
end A2AClientSpec

@js.native
@JSGlobal("Bun")
private object TestBun extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
