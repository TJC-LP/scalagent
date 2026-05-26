package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import munit.FunSuite
import zio.*
import zio.json.*

class A2AServerLiveSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def testServer(config: A2AServerLive.Config): Task[A2AServerLiveImpl] =
    ZIO.attempt(A2AServerLiveImpl(config, runtime))

  private def dispatch(server: A2AServerLiveImpl, request: JsonRpcRequest): Task[JsonRpcResponse] =
    server.dispatchJsonRpc(request)

  private def resultAs[A: JsonDecoder](response: JsonRpcResponse): Task[A] =
    ZIO.fromEither(
      response.getResult
        .left.map(error => new RuntimeException(error.message))
        .flatMap(_.as[A].left.map(new RuntimeException(_)))
    )

  private def sendTask(response: JsonRpcResponse): Task[A2ATask] =
    resultAs[A2AResponse.SendMessageResult](response).flatMap {
      case A2AResponse.SendMessageResult.TaskResult(task) => ZIO.succeed(task)
      case A2AResponse.SendMessageResult.MessageResult(_) => ZIO.fail(new RuntimeException("expected task result"))
    }

  private def completedExecution(
    message: A2AMessage,
    taskId: TaskId,
    contextId: ContextId,
    publisher: A2AEventPublisher,
  ): Task[Unit] =
    val response = A2AMessage.agentText(s"done: ${message.text}", Some(contextId)).copy(taskId = Some(taskId))
    publisher.publish(
      A2AResponse.StreamEvent.TaskStatusUpdate(
        taskId,
        contextId,
        TaskStatus.completed(response),
        `final` = true,
      )
    )

  private def rpc(method: String, params: zio.json.ast.Json, id: Long): JsonRpcRequest =
    JsonRpcRequest(method = method, params = Some(params), id = Some(JsonRpcId.Num(id)))

  test("JSON-RPC task operations are tenant-scoped on JVM"):
    val sharedId = TaskId("tenant-shared")

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "TenantJvmTest",
            description = "Tenant JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
        taskA <- dispatch(
          server,
          rpc(
            A2AMethod.MessageSend,
            A2ARequest
              .MessageSend(A2AMessage.userText("tenant-a").copy(taskId = Some(sharedId)), tenant = Some("tenant-a"))
              .toJsonAST
              .toOption
              .get,
            1,
          ),
        ).flatMap(sendTask)
        taskB <- dispatch(
          server,
          rpc(
            A2AMethod.MessageSend,
            A2ARequest
              .MessageSend(A2AMessage.userText("tenant-b").copy(taskId = Some(sharedId)), tenant = Some("tenant-b"))
              .toJsonAST
              .toOption
              .get,
            2,
          ),
        ).flatMap(sendTask)
        getA <- dispatch(
          server,
          rpc(A2AMethod.TasksGet, A2ARequest.TasksGet(sharedId, tenant = Some("tenant-a")).toJsonAST.toOption.get, 3),
        ).flatMap(resultAs[A2ATask])
        getB <- dispatch(
          server,
          rpc(A2AMethod.TasksGet, A2ARequest.TasksGet(sharedId, tenant = Some("tenant-b")).toJsonAST.toOption.get, 4),
        ).flatMap(resultAs[A2ATask])
        listA <- dispatch(
          server,
          rpc(A2AMethod.TasksList, A2ARequest.TasksList(tenant = Some("tenant-a")).toJsonAST.toOption.get, 5),
        ).flatMap(resultAs[A2AResponse.ListTasksResult])
        listB <- dispatch(
          server,
          rpc(A2AMethod.TasksList, A2ARequest.TasksList(tenant = Some("tenant-b")).toJsonAST.toOption.get, 6),
        ).flatMap(resultAs[A2AResponse.ListTasksResult])
      yield (taskA, taskB, getA, getB, listA, listB)

    runTask(program).map { case (taskA, taskB, getA, getB, listA, listB) =>
      assertEquals(taskA.status.message.map(_.text), Some("done: tenant-a"))
      assertEquals(taskB.status.message.map(_.text), Some("done: tenant-b"))
      assertEquals(getA.status.message.map(_.text), Some("done: tenant-a"))
      assertEquals(getB.status.message.map(_.text), Some("done: tenant-b"))
      assertEquals(listA.tasks.map(_.status.message.map(_.text)), List(Some("done: tenant-a")))
      assertEquals(listB.tasks.map(_.status.message.map(_.text)), List(Some("done: tenant-b")))
    }

  test("JSON-RPC CancelTask cancels an active JVM task"):
    val sharedId = TaskId("cancel-shared")

    val program =
      for
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        runOverride =
          (message: A2AMessage, taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher) =>
            started.succeed(()).unit *> release.await *> completedExecution(message, taskId, contextId, publisher)
        server <- testServer(
          A2AServerLive.Config(
            name = "CancelJvmTest",
            description = "Cancel JVM test server",
            executionOverride = Some(runOverride),
          )
        )
        sendFiber <- dispatch(
          server,
          rpc(
            A2AMethod.MessageSend,
            A2ARequest
              .MessageSend(
                A2AMessage.userText("cancel me").copy(taskId = Some(sharedId)),
                tenant = Some("tenant-cancel"),
              )
              .toJsonAST
              .toOption
              .get,
            10,
          ),
        ).flatMap(sendTask).fork
        _ <- started.await.timeoutFail(new RuntimeException("execution did not start"))(2.seconds)
        canceled <- dispatch(
          server,
          rpc(
            A2AMethod.TasksCancel,
            A2ARequest.TasksCancel(sharedId, tenant = Some("tenant-cancel")).toJsonAST.toOption.get,
            11,
          ),
        ).flatMap(resultAs[A2ATask])
        _      <- release.succeed(()).unit
        sent   <- sendFiber.join.timeoutFail(new RuntimeException("send did not finish after cancel"))(2.seconds)
        loaded <- dispatch(
          server,
          rpc(A2AMethod.TasksGet, A2ARequest.TasksGet(sharedId, tenant = Some("tenant-cancel")).toJsonAST.toOption.get, 12),
        ).flatMap(resultAs[A2ATask])
      yield (canceled, sent, loaded)

    runTask(program).map { case (canceled, sent, loaded) =>
      assertEquals(canceled.status.state, TaskState.Canceled)
      assertEquals(sent.status.state, TaskState.Canceled)
      assertEquals(loaded.status.state, TaskState.Canceled)
    }
end A2AServerLiveSpec
