package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import java.net.{InetAddress, ServerSocket}

import munit.FunSuite
import zio.*
import zio.http.*
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

  private def headers(values: (String, String)*): Headers =
    Headers.fromIterable(values.map { case (name, value) => Header.Custom(name, value) })

  private def versionedJsonHeaders(contentType: String = A2AContentType.Json): Headers =
    headers(
      "Content-Type" -> contentType,
      A2AHeader.Version -> A2AProtocol.Version,
    )

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

  test("JVM agent card only advertises implemented v1 interfaces"):
    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "CardJvmTest",
            description = "Card JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
      yield server.agentCard

    runTask(program).map { card =>
      assertEquals(card.supportedInterfaces.map(_.protocolBinding), List(A2ATransport.JSONRPC, A2ATransport.HTTP_JSON))
      assertEquals(card.supportedInterfaces.map(_.protocolVersion), List(A2AProtocol.Version, A2AProtocol.Version))
      assertEquals(card.capabilities.streaming, true)
    }

  test("JVM HTTP JSON-RPC enforces maxRequestBodyBytes without content-length"):
    val body = JsonRpcRequest(
      method = A2AMethod.TasksList,
      params = A2ARequest.TasksList().toJsonAST.toOption,
      id = Some(JsonRpcId.Num(20)),
    ).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "BodyLimitJvmTest",
            description = "Body limit JVM test server",
            maxRequestBodyBytes = 8,
          )
        )
        response <- server.handleHttp(
          Request.post("/", Body.fromString(body)).copy(headers = versionedJsonHeaders())
        )
        responseBody <- response.body.asString
        rpcResponse  <- ZIO.fromEither(responseBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
      yield rpcResponse

    runTask(program).map { response =>
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.InvalidRequest))
      assert(response.error.exists(_.message.contains("Request body exceeds 8 byte limit")))
    }

  test("JVM start fails when the configured port is already bound"):
    val program =
      ZIO.scoped {
        ZIO
          .acquireRelease(
            ZIO.attempt(new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")))
          )(socket => ZIO.attempt(socket.close()).ignore)
          .flatMap { socket =>
            val config = A2AServerLive.Config(
              name = "BindFailureJvmTest",
              description = "Bind failure JVM test server",
              host = "127.0.0.1",
              port = socket.getLocalPort,
              executionOverride = Some(completedExecution),
            )
            A2AServerLive.start(config, runtime).foldZIO(
              _ => ZIO.succeed(true),
              server => server.stop.as(false),
            )
          }
      }

    runTask(program).map { failed =>
      assert(failed, "start should fail when the configured port is already bound")
    }

  test("JVM taskTimeout fails a hung executionOverride"):
    val timedOut =
      (_: A2AMessage, _: TaskId, _: ContextId, _: A2AEventPublisher) => ZIO.never

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "TimeoutJvmTest",
            description = "Timeout JVM test server",
            taskTimeout = Some(50.millis),
            executionOverride = Some(timedOut),
          )
        )
        task <- dispatch(
          server,
          rpc(
            A2AMethod.MessageSend,
            A2ARequest.MessageSend(A2AMessage.userText("timeout")).toJsonAST.toOption.get,
            21,
          ),
        ).flatMap(sendTask)
      yield task

    runTask(program).map { task =>
      assertEquals(task.status.state, TaskState.Failed)
      assert(task.status.message.exists(_.text.contains("timed out")))
    }

  test("JVM REST message send is tenant-scoped"):
    val request = A2ARequest.MessageSend(A2AMessage.userText("rest hello")).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "RestJvmTest",
            description = "REST JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
        sentResponse <- server.handleHttp(
          Request
            .post("/tenant-a/message:send", Body.fromString(request))
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        sentBody <- sentResponse.body.asString
        result   <- ZIO.fromEither(sentBody.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
        task <- result match
          case A2AResponse.SendMessageResult.TaskResult(task) => ZIO.succeed(task)
          case _ => ZIO.fail(new RuntimeException("expected task result"))
        listAResponse <- server.handleHttp(
          Request.get("/tenant-a/tasks").copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        listBResponse <- server.handleHttp(
          Request.get("/tenant-b/tasks").copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        listABody <- listAResponse.body.asString
        listBBody <- listBResponse.body.asString
        listA     <- ZIO.fromEither(listABody.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        listB     <- ZIO.fromEither(listBBody.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
      yield (sentResponse, task, listA, listB)

    runTask(program).map { case (sentResponse, task, listA, listB) =>
      assertEquals(sentResponse.status, Status.Ok)
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(listA.tasks.map(_.id), List(task.id))
      assertEquals(listB.tasks, Nil)
    }

  test("JVM JSON-RPC streaming emits SSE JSON-RPC stream events"):
    val body = JsonRpcRequest(
      method = A2AMethod.MessageStream,
      params = A2ARequest.MessageSend(A2AMessage.userText("stream hello")).toJsonAST.toOption,
      id = Some(JsonRpcId.Num(22)),
    ).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "StreamJvmTest",
            description = "Stream JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
        response <- server.handleHttp(
          Request.post("/", Body.fromString(body)).copy(headers = versionedJsonHeaders())
        )
        responseBody <- response.body.asString.timeoutFail(new RuntimeException("stream did not complete"))(2.seconds)
      yield (response, responseBody)

    runTask(program).map { case (response, body) =>
      assertEquals(response.status, Status.Ok)
      assert(response.headers.get("content-type").exists(_.startsWith(A2AContentType.Sse)))
      assert(body.contains("data:"))
      assert(body.contains(""""jsonrpc":"2.0""""))
      assert(body.contains(""""task""""))
      assert(body.contains(""""statusUpdate""""))
    }
end A2AServerLiveSpec
