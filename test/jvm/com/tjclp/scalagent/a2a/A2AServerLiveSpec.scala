package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import java.io.File
import java.net.{InetAddress, ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.time.{Duration as JavaDuration}

import com.google.protobuf.util.JsonFormat
import com.google.lf.a2a.v1.{
  SendMessageRequest as ProtoSendMessageRequest,
  StreamResponse as ProtoStreamResponse,
}
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyChannelBuilder}
import io.grpc.stub.ClientCalls
import io.grpc.CallOptions
import munit.FunSuite
import zio.*
import zio.http.*
import zio.json.*

class A2AServerLiveSpec extends FunSuite:
  private val runtime = Runtime.default
  private val client  = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(2)).build()

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def testServer(config: A2AServerLive.Config): Task[A2AServerLiveImpl] =
    for
      registry      <- A2ARuntimeRegistry.make
      scopeRef      <- Ref.Synchronized.make(Option.empty[Scope.Closeable])
      grpcServerRef <- Ref.Synchronized.make(Option.empty[io.grpc.Server])
      server        <- ZIO.attempt(A2AServerLiveImpl(config, runtime, registry, scopeRef, grpcServerRef))
    yield server

  private def dispatch(server: A2AServerLiveImpl, request: JsonRpcRequest): Task[JsonRpcResponse] =
    server.dispatchJsonRpc(request)

  private def freeLocalPort: Task[Int] =
    ZIO.attempt {
      val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
      try socket.getLocalPort
      finally socket.close()
    }

  private def get(url: String): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(JavaDuration.ofSeconds(2))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() -> response.body()
    }

  private def post(url: String, body: String, requestHeaders: Map[String, String]): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(JavaDuration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(body))
      requestHeaders.foreach { case (name, value) => builder.header(name, value) }
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      response.statusCode() -> response.body()
    }

  private def grpcStreamingResponse(
    port: Int,
    message: A2AMessage,
    trustCert: Option[File] = None,
    authority: Option[String] = None,
  ): Task[ProtoStreamResponse] =
    ZIO.attemptBlocking {
      val builder = NettyChannelBuilder.forAddress("127.0.0.1", port)
      trustCert match
        case Some(cert) =>
          builder.sslContext(GrpcSslContexts.forClient().trustManager(cert).build())
          authority.foreach(builder.overrideAuthority)
        case None =>
          builder.usePlaintext()
      val channel = builder.build()
      try
        val iterator = ClientCalls.blockingServerStreamingCall(
          channel,
          A2AGrpcJavaService.SendStreamingMessageMethod,
          CallOptions.DEFAULT,
          sendMessageRequest(message),
        )
        if !iterator.hasNext then throw new RuntimeException("gRPC stream returned no responses")
        val first = iterator.next()
        if iterator.hasNext then throw new RuntimeException("gRPC stream returned more than one response")
        first
      finally channel.shutdownNow()
    }

  private def sendMessageRequest(message: A2AMessage): ProtoSendMessageRequest =
    val builder = ProtoSendMessageRequest.newBuilder()
    JsonFormat.parser().merge(A2ARequest.MessageSend(message).toJson, builder)
    builder.build()

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
    val store    = A2ATaskStore.inMemory

    val program =
      for
        _ <- store.save(A2ATask(sharedId, ContextId("tenant-a-context"), TaskStatus.working()), Some("tenant-a"))
        _ <- store.save(A2ATask(sharedId, ContextId("tenant-b-context"), TaskStatus.working()), Some("tenant-b"))
        server <- testServer(
          A2AServerLive.Config(
            name = "TenantJvmTest",
            description = "Tenant JVM test server",
            taskStore = Some(store),
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

  test("JSON-RPC SendMessage rejects unknown client taskId on JVM"):
    val unknown = TaskId("missing-client-task")

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "UnknownTaskJvmTest",
            description = "Unknown task JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
        result <- dispatch(
          server,
          rpc(
            A2AMethod.MessageSend,
            A2ARequest.MessageSend(A2AMessage.userText("missing").copy(taskId = Some(unknown))).toJsonAST.toOption.get,
            7,
          ),
        ).either
      yield result

    runTask(program).map { result =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.TaskNotFound
        case _               => false
      })
    }

  test("JSON-RPC SendMessage validates inbound message shape on JVM"):
    val emptyParts = A2AMessage(role = A2ARole.User, parts = Nil)
    val agentRole  = A2AMessage.agentText("agent-originated")

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "MessageValidationJvmTest",
            description = "Message validation JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
        emptyResult <- dispatch(
          server,
          rpc(A2AMethod.MessageSend, A2ARequest.MessageSend(emptyParts).toJsonAST.toOption.get, 8),
        ).either
        roleResult <- dispatch(
          server,
          rpc(A2AMethod.MessageSend, A2ARequest.MessageSend(agentRole).toJsonAST.toOption.get, 9),
        ).either
      yield (emptyResult, roleResult)

    runTask(program).map { case (emptyResult, roleResult) =>
      assert(emptyResult.left.exists {
        case error: A2AError =>
          error.code == A2AErrorCode.InvalidParams &&
          (error.message.contains("message.parts") || error.message.contains("parts must contain at least one part"))
        case _               => false
      })
      assert(roleResult.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidParams && error.message.contains("message.role")
        case _               => false
      })
    }

  test("JSON-RPC SendMessage rejects inline push notification task ids on JVM"):
    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "InlinePushTaskIdJvmTest",
            description = "Inline push task id JVM test server",
            capabilities = AgentCapabilities.default.copy(pushNotifications = true),
            executionOverride = Some(completedExecution),
            pushNotificationUrlPolicy = PushNotificationUrlPolicy.allowAll,
          )
        )
        result <- dispatch(
          server,
          rpc(
            A2AMethod.MessageSend,
            A2ARequest
              .MessageSend(
                A2AMessage.userText("do not bind this config"),
                configuration = Some(
                  MessageSendConfiguration(
                    taskPushNotificationConfig = Some(
                      TaskPushNotificationConfig(
                        url = "http://callback.test/inline",
                        taskId = Some(TaskId("foreign-task")),
                      )
                    )
                  )
                ),
              )
              .toJsonAST
              .toOption
              .get,
            10,
          ),
        ).either
        listed <- dispatch(
          server,
          rpc(A2AMethod.TasksList, A2ARequest.TasksList().toJsonAST.toOption.get, 11),
        ).flatMap(resultAs[A2AResponse.ListTasksResult])
      yield (result, listed)

    runTask(program).map { case (result, listed) =>
      assert(result.left.exists {
        case error: A2AError =>
          error.code == A2AErrorCode.InvalidParams &&
            error.message.contains("taskPushNotificationConfig.taskId must be empty")
        case _ =>
          false
      })
      assertEquals(listed.tasks, Nil)
    }

  test("JSON-RPC CancelTask cancels an active JVM task"):
    val sharedId = TaskId("cancel-shared")
    val store    = A2ATaskStore.inMemory

    val program =
      for
        _       <- store.save(A2ATask(sharedId, ContextId("cancel-context"), TaskStatus.working()), Some("tenant-cancel"))
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        runOverride =
          (message: A2AMessage, taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher) =>
            started.succeed(()).unit *> release.await *> completedExecution(message, taskId, contextId, publisher)
        server <- testServer(
          A2AServerLive.Config(
            name = "CancelJvmTest",
            description = "Cancel JVM test server",
            taskStore = Some(store),
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
      assert(card.skills.nonEmpty)
      assert(card.skills.forall(_.tags.nonEmpty))
    }

  test("JVM GetExtendedAgentCard is unsupported when capability is disabled"):
    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "ExtendedCardDisabledJvmTest",
            description = "Extended card disabled JVM test server",
          )
        )
        result <- dispatch(
          server,
          JsonRpcRequest(
            method = A2AMethod.GetAuthenticatedExtendedCard,
            id = Some(JsonRpcId.Num(19)),
          ),
        ).either
      yield result

    runTask(program).map { result =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.UnsupportedOperation
        case _               => false
      })
    }

  test("JVM GetExtendedAgentCard returns not configured when advertised without card"):
    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "MissingExtendedCardJvmTest",
            description = "Missing extended card JVM test server",
            capabilities = AgentCapabilities.default.copy(extendedAgentCard = true),
          )
        )
        result <- dispatch(
          server,
          JsonRpcRequest(
            method = A2AMethod.GetAuthenticatedExtendedCard,
            id = Some(JsonRpcId.Num(32)),
          ),
        ).either
      yield result

    runTask(program).map { result =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.AuthenticatedExtendedCardNotConfigured
        case _               => false
      })
    }

  test("JVM GetExtendedAgentCard returns configured extended card"):
    val extendedCard = AgentCard(
      name = "JvmExtendedCard",
      description = "Private JVM extended card",
      supportedInterfaces = List(AgentInterface.jsonRpc("https://extended.example.test/a2a")),
    )

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "ConfiguredExtendedCardJvmTest",
            description = "Configured extended card JVM test server",
            capabilities = AgentCapabilities.default.copy(extendedAgentCard = true),
            extendedAgentCard = Some(extendedCard),
            extendedAgentCardAuth = A2AExtendedAgentCardAuth.permitAll,
          )
        )
        card <- dispatch(
          server,
          JsonRpcRequest(
            method = A2AMethod.GetAuthenticatedExtendedCard,
            id = Some(JsonRpcId.Num(33)),
          ),
        ).flatMap(resultAs[AgentCard])
      yield card

    runTask(program).map { card =>
      assertEquals(card.name, "JvmExtendedCard")
      assertEquals(card.description, "Private JVM extended card")
    }

  test("JVM JSON-RPC rejects unsupported content type"):
    val body = JsonRpcRequest(
      method = A2AMethod.TasksList,
      params = A2ARequest.TasksList().toJsonAST.toOption,
      id = Some(JsonRpcId.Num(30)),
    ).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "JsonRpcContentTypeJvmTest",
            description = "JSON-RPC content type JVM test server",
          )
        )
        response <- server.handleHttp(
          Request
            .post("/", Body.fromString(body))
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        responseBody <- response.body.asString
        rpcResponse  <- ZIO.fromEither(responseBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
      yield rpcResponse

    runTask(program).map { response =>
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.ContentTypeNotSupported))
    }

  test("JVM JSON-RPC version negotiation ignores patch versions"):
    val body = JsonRpcRequest(
      method = A2AMethod.TasksList,
      params = A2ARequest.TasksList().toJsonAST.toOption,
      id = Some(JsonRpcId.Num(34)),
    ).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "VersionPatchJvmTest",
            description = "Version patch JVM test server",
          )
        )
        response <- server.handleHttp(
          Request
            .post("/", Body.fromString(body))
            .copy(headers =
              headers(
                "Content-Type" -> A2AContentType.Json,
                A2AHeader.Version -> s"${A2AProtocol.Version}.99",
              )
            )
        )
        responseBody <- response.body.asString
        rpcResponse  <- ZIO.fromEither(responseBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        tasks        <- resultAs[A2AResponse.ListTasksResult](rpcResponse)
      yield rpcResponse -> tasks

    runTask(program).map { case (response, tasks) =>
      assertEquals(response.id, Some(JsonRpcId.Num(34)))
      assertEquals(response.error, None)
      assertEquals(tasks.tasks, Nil)
    }

  test("JVM JSON-RPC validates advertised interface tenant"):
    def listBody(id: Long, tenant: Option[String]): String =
      JsonRpcRequest(
        method = A2AMethod.TasksList,
        params = A2ARequest.TasksList(tenant = tenant).toJsonAST.toOption,
        id = Some(JsonRpcId.Num(id)),
      ).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "TenantValidationJvmTest",
            description = "Tenant validation JVM test server",
            tenant = Some("tenant-a"),
          )
        )
        acceptedResponse <- server.handleHttp(
          Request.post("/", Body.fromString(listBody(35, Some("tenant-a")))).copy(headers = versionedJsonHeaders())
        )
        missingResponse <- server.handleHttp(
          Request.post("/", Body.fromString(listBody(36, None))).copy(headers = versionedJsonHeaders())
        )
        wrongResponse <- server.handleHttp(
          Request.post("/", Body.fromString(listBody(37, Some("tenant-b")))).copy(headers = versionedJsonHeaders())
        )
        acceptedBody <- acceptedResponse.body.asString
        missingBody  <- missingResponse.body.asString
        wrongBody    <- wrongResponse.body.asString
        accepted     <- ZIO.fromEither(acceptedBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        missing      <- ZIO.fromEither(missingBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        wrong        <- ZIO.fromEither(wrongBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
      yield (accepted, missing, wrong)

    runTask(program).map { case (accepted, missing, wrong) =>
      assertEquals(accepted.id, Some(JsonRpcId.Num(35)))
      assertEquals(accepted.error, None)
      assertEquals(missing.id, Some(JsonRpcId.Num(36)))
      assertEquals(missing.error.map(_.code), Some(A2AErrorCode.InvalidParams))
      assert(missing.error.exists(_.message.contains("tenant is required")))
      assertEquals(wrong.id, Some(JsonRpcId.Num(37)))
      assertEquals(wrong.error.map(_.code), Some(A2AErrorCode.InvalidParams))
      assert(wrong.error.exists(_.message.contains("selected AgentInterface tenant")))
    }

  test("JVM JSON-RPC distinguishes malformed JSON from invalid request envelopes"):
    val invalidEnvelope =
      """{
        |  "jsonrpc": "1.0",
        |  "method": "GetTask",
        |  "id": 1
        |}""".stripMargin
    val missingId =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "ListTasks",
        |  "params": {}
        |}""".stripMargin

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "JsonRpcEnvelopeErrorJvmTest",
            description = "JSON-RPC envelope error JVM test server",
          )
        )
        malformedResponse <- server.handleHttp(
          Request.post("/", Body.fromString("""{"jsonrpc":""")).copy(headers = versionedJsonHeaders())
        )
        malformedBody <- malformedResponse.body.asString
        malformed     <- ZIO.fromEither(malformedBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        invalidResponse <- server.handleHttp(
          Request.post("/", Body.fromString(invalidEnvelope)).copy(headers = versionedJsonHeaders())
        )
        invalidBody <- invalidResponse.body.asString
        invalid     <- ZIO.fromEither(invalidBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        missingResponse <- server.handleHttp(
          Request.post("/", Body.fromString(missingId)).copy(headers = versionedJsonHeaders())
        )
        missingBody <- missingResponse.body.asString
        missing     <- ZIO.fromEither(missingBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
      yield (malformed, invalid, missing)

    runTask(program).map { case (malformed, invalid, missing) =>
      assertEquals(malformed.error.map(_.code), Some(A2AErrorCode.ParseError))
      assertEquals(invalid.error.map(_.code), Some(A2AErrorCode.InvalidRequest))
      assertEquals(missing.error.map(_.code), Some(A2AErrorCode.InvalidRequest))
      assertEquals(malformed.id, JsonRpcId.Unknown)
      assertEquals(invalid.id, JsonRpcId.Unknown)
      assertEquals(missing.id, JsonRpcId.Unknown)
    }

  test("JVM REST rejects unsupported request body content type"):
    val request = A2ARequest.MessageSend(A2AMessage.userText("bad content type")).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "RestContentTypeJvmTest",
            description = "REST content type JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
        response <- server.handleHttp(
          Request.post("/message:send", Body.fromString(request)).copy(headers = versionedJsonHeaders())
        )
        body <- response.body.asString
      yield (response, body)

    runTask(program).map { case (response, body) =>
      assertEquals(response.status, Status.BadRequest)
      assert(body.contains("CONTENT_TYPE_NOT_SUPPORTED"))
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

  // Regression for #58: zio-http's Server.Config.default caps request streaming at
  // 100 KiB, so unless config.maxRequestBodyBytes is wired into the server the bound
  // server 413s on larger bodies (e.g. base64 file uploads) before the handler runs.
  // The handleHttp-based body-limit test above bypasses the Server.Config cap, so this
  // exercises a real bound zio-http server end to end.
  test("JVM bound server accepts JSON-RPC bodies larger than zio-http's default 100 KiB cap"):
    // ~200 KiB: above zio-http's 100 KiB default, well below the 64 MiB configured limit.
    val largeText = "x" * (200 * 1024)
    val body = rpc(
      A2AMethod.MessageSend,
      A2ARequest.MessageSend(A2AMessage.userText(largeText)).toJsonAST.toOption.get,
      58,
    ).toJson

    val program =
      ZIO.scoped {
        for
          port <- freeLocalPort
          config = A2AServerLive.Config(
            name = "LargeBodyJvmTest",
            description = "Large body JVM test server",
            host = "127.0.0.1",
            port = port,
            executionOverride = Some(completedExecution),
          )
          _ <- A2AServerLive
                 .create(config)
                 .timeoutFail(new RuntimeException("server did not become ready"))(10.seconds)
          result <- post(
                      s"http://127.0.0.1:$port/",
                      body,
                      Map("Content-Type" -> A2AContentType.Json, A2AHeader.Version -> A2AProtocol.Version),
                    )
          response <- ZIO.fromEither(result._2.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
          task     <- sendTask(response)
        yield (result._1, task)
      }

    runTask(program).map { case (status, task) =>
      assertEquals(status, 200)
      assertEquals(task.status.state, TaskState.Completed)
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

  test("JVM create starts zio-http server and serves agent card"):
    val program =
      ZIO.scoped {
        for
          port <- freeLocalPort
          config = A2AServerLive.Config(
            name = "HttpStartupJvmTest",
            description = "HTTP startup JVM test server",
            host = "127.0.0.1",
            port = port,
            executionOverride = Some(completedExecution),
          )
          server <- A2AServerLive
                      .create(config)
                      .timeoutFail(new RuntimeException("server did not become ready"))(10.seconds)
          result <- get(s"http://127.0.0.1:$port${A2APaths.AgentCard}")
          card   <- ZIO.fromEither(result._2.fromJson[AgentCard].left.map(new RuntimeException(_)))
        yield (config.url, server.url, result._1, card)
      }

    runTask(program).map { case (expectedUrl, actualUrl, status, card) =>
      assertEquals(status, 200)
      assertEquals(actualUrl, expectedUrl)
      assertEquals(card.name, "HttpStartupJvmTest")
      assertEquals(card.description, "HTTP startup JVM test server")
      assertEquals(card.supportedInterfaces.map(_.url), List(expectedUrl, expectedUrl))
      assert(card.skills.nonEmpty)
      assert(card.skills.forall(_.tags.nonEmpty))
    }

  test("JVM create can start and advertise an A2A gRPC service"):
    val responder: A2ARequest.MessageSend => Task[A2AMessage] =
      _ => ZIO.succeed(A2AMessage.agentText("pong"))
    val program =
      ZIO.scoped {
        for
          httpPort <- freeLocalPort
          grpcPort <- freeLocalPort
          config = A2AServerLive.Config(
            name = "GrpcStartupJvmTest",
            description = "gRPC startup JVM test server",
            host = "127.0.0.1",
            port = httpPort,
            grpcPort = Some(grpcPort),
            messageResponseOverride = Some(responder),
          )
          _ <- A2AServerLive
                 .create(config)
                 .timeoutFail(new RuntimeException("server did not become ready"))(10.seconds)
          result   <- get(s"http://127.0.0.1:$httpPort${A2APaths.AgentCard}")
          card     <- ZIO.fromEither(result._2.fromJson[AgentCard].left.map(new RuntimeException(_)))
          response <- grpcStreamingResponse(grpcPort, A2AMessage.userText("ping"))
        yield (card, grpcPort, response)
      }

    runTask(program).map { case (card, grpcPort, response) =>
      assertEquals(card.supportedInterfaces.map(_.protocolBinding), List(A2ATransport.JSONRPC, A2ATransport.HTTP_JSON, A2ATransport.GRPC))
      assertEquals(card.supportedInterfaces.last.url, s"http://127.0.0.1:$grpcPort")
      assert(response.hasMessage)
      assertEquals(response.getMessage.getParts(0).getText, "pong")
    }

  test("JVM create can start and advertise a TLS A2A gRPC service"):
    val responder: A2ARequest.MessageSend => Task[A2AMessage] =
      _ => ZIO.succeed(A2AMessage.agentText("pong"))
    val program =
      ZIO.scoped {
        for
          httpPort <- freeLocalPort
          grpcPort <- freeLocalPort
          tls <- testTlsFiles
          config = A2AServerLive.Config(
            name = "GrpcTlsStartupJvmTest",
            description = "gRPC TLS startup JVM test server",
            host = "127.0.0.1",
            port = httpPort,
            grpcPort = Some(grpcPort),
            grpcTls = Some(
              A2AServerLive.GrpcTlsConfig(
                tls.cert.getAbsolutePath,
                tls.key.getAbsolutePath,
              )
            ),
            messageResponseOverride = Some(responder),
          )
          _ <- A2AServerLive
                 .create(config)
                 .timeoutFail(new RuntimeException("TLS gRPC server did not become ready"))(10.seconds)
          result <- get(s"http://127.0.0.1:$httpPort${A2APaths.AgentCard}")
          card   <- ZIO.fromEither(result._2.fromJson[AgentCard].left.map(new RuntimeException(_)))
          response <- grpcStreamingResponse(
                        grpcPort,
                        A2AMessage.userText("ping"),
                        trustCert = Some(tls.cert),
                      )
        yield (card, grpcPort, response)
      }

    runTask(program).map { case (card, grpcPort, response) =>
      assertEquals(card.supportedInterfaces.last.url, s"https://127.0.0.1:$grpcPort")
      assert(response.hasMessage)
      assertEquals(response.getMessage.getParts(0).getText, "pong")
    }

  test("JVM start is idempotent for the same server instance"):
    val program =
      for
        port <- freeLocalPort
        config = A2AServerLive.Config(
          name = "IdempotentStartJvmTest",
          description = "Idempotent start JVM test server",
          host = "127.0.0.1",
          port = port,
          executionOverride = Some(completedExecution),
        )
        server <- testServer(config)
        result <- (for
                    _      <- server.start.timeoutFail(new RuntimeException("first start did not complete"))(10.seconds)
                    _      <- server.start.timeoutFail(new RuntimeException("second start did not complete"))(10.seconds)
                    result <- get(s"http://127.0.0.1:$port${A2APaths.AgentCard}")
                  yield result).ensuring(server.stop.ignore)
      yield result._1

    runTask(program).map(status => assertEquals(status, 200))

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
    val request = A2ARequest
      .MessageSend(A2AMessage.userText("rest hello"), configuration = Some(MessageSendConfiguration.default))
      .toJson

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

  test("JVM REST honors tenant request fields outside path bindings"):
    val request = A2ARequest
      .MessageSend(
        A2AMessage.userText("body tenant"),
        configuration = Some(MessageSendConfiguration.default),
        tenant = Some("tenant-body"),
      )
      .toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "RestTenantFieldJvmTest",
            description = "REST tenant field JVM test server",
            executionOverride = Some(completedExecution),
          )
        )
        sentResponse <- server.handleHttp(
          Request
            .post("/message:send", Body.fromString(request))
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        sentBody <- sentResponse.body.asString
        result   <- ZIO.fromEither(sentBody.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
        task <- result match
          case A2AResponse.SendMessageResult.TaskResult(task) => ZIO.succeed(task)
          case _ => ZIO.fail(new RuntimeException("expected task result"))
        listResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?tenant=tenant-body").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        otherResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?tenant=tenant-other").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        conflictResponse <- server.handleHttp(
          Request
            .post("/tenant-path/message:send", Body.fromString(request))
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        listBody     <- listResponse.body.asString
        otherBody    <- otherResponse.body.asString
        conflictBody <- conflictResponse.body.asString
        listed       <- ZIO.fromEither(listBody.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        other        <- ZIO.fromEither(otherBody.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
      yield (sentResponse, task, listed, other, conflictResponse, conflictBody)

    runTask(program).map { case (sentResponse, task, listed, other, conflictResponse, conflictBody) =>
      assertEquals(sentResponse.status, Status.Ok)
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(listed.tasks.map(_.id), List(task.id))
      assertEquals(other.tasks, Nil)
      assertEquals(conflictResponse.status, Status.BadRequest)
      assert(conflictBody.contains("Conflicting tenant values"))
    }

  test("JVM REST CancelTask honors request body tenant"):
    val program =
      for
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        runOverride =
          (_: A2AMessage, _: TaskId, _: ContextId, _: A2AEventPublisher) =>
            started.succeed(()).unit *> release.await
        server <- testServer(
          A2AServerLive.Config(
            name = "RestCancelTenantBodyJvmTest",
            description = "REST cancel tenant body JVM test server",
            executionOverride = Some(runOverride),
          )
        )
        request = A2ARequest
          .MessageSend(
            A2AMessage.userText("cancel body tenant"),
            configuration = Some(MessageSendConfiguration(returnImmediately = true)),
            tenant = Some("tenant-cancel"),
          )
          .toJson
        sentResponse <- server.handleHttp(
          Request
            .post("/message:send", Body.fromString(request))
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        sentBody <- sentResponse.body.asString
        result   <- ZIO.fromEither(sentBody.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
        task <- result match
          case A2AResponse.SendMessageResult.TaskResult(task) => ZIO.succeed(task)
          case _ => ZIO.fail(new RuntimeException("expected task result"))
        _ <- started.await.timeoutFail(new RuntimeException("execution did not start"))(2.seconds)
        cancelResponse <- server.handleHttp(
          Request
            .post(
              s"/tasks/${task.id.value}:cancel",
              Body.fromString("""{"tenant":"tenant-cancel","metadata":{"source":"body"}}"""),
            )
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        conflictResponse <- server.handleHttp(
          Request
            .post(
              s"/tasks/${task.id.value}:cancel",
              Body.fromString(s"""{"id":"${task.id.value}-other","tenant":"tenant-cancel"}"""),
            )
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        listResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?tenant=tenant-cancel").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        defaultResponse <- server.handleHttp(
          Request.get("/tasks").copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        cancelBody   <- cancelResponse.body.asString
        conflictBody <- conflictResponse.body.asString
        listBody     <- listResponse.body.asString
        defaultBody  <- defaultResponse.body.asString
        canceled     <- ZIO.fromEither(cancelBody.fromJson[A2ATask].left.map(new RuntimeException(_)))
        listed       <- ZIO.fromEither(listBody.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        defaultList  <- ZIO.fromEither(defaultBody.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
      yield (cancelResponse, canceled, listed, defaultList, conflictResponse, conflictBody)

    runTask(program).map { case (cancelResponse, canceled, listed, defaultList, conflictResponse, conflictBody) =>
      assertEquals(cancelResponse.status, Status.Accepted)
      assertEquals(canceled.status.state, TaskState.Canceled)
      assertEquals(listed.tasks.map(_.id), List(canceled.id))
      assertEquals(defaultList.tasks, Nil)
      assertEquals(conflictResponse.status, Status.BadRequest)
      assert(conflictBody.contains("CancelTaskRequest.id does not match path id"))
    }

  test("JVM REST push notification config list honors pagination"):
    val request = A2ARequest.MessageSend(A2AMessage.userText("rest push pages")).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "RestPushPaginationJvmTest",
            description = "REST push pagination JVM test server",
            capabilities = AgentCapabilities.default.copy(pushNotifications = true),
            executionOverride = Some(completedExecution),
          )
        )
        sentResponse <- server.handleHttp(
          Request
            .post("/message:send", Body.fromString(request))
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        sentBody <- sentResponse.body.asString
        result   <- ZIO.fromEither(sentBody.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
        task <- result match
          case A2AResponse.SendMessageResult.TaskResult(task) => ZIO.succeed(task)
          case _ => ZIO.fail(new RuntimeException("expected task result"))
        _ <- server.handleHttp(
          Request
            .post(
              s"/tasks/${task.id.value}/pushNotificationConfigs",
              Body.fromString(TaskPushNotificationConfig(url = "http://callback.test/1", id = Some("cfg-1")).toJson),
            )
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        _ <- server.handleHttp(
          Request
            .post(
              s"/tasks/${task.id.value}/pushNotificationConfigs",
              Body.fromString(TaskPushNotificationConfig(url = "http://callback.test/2", id = Some("cfg-2")).toJson),
            )
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        _ <- server.handleHttp(
          Request
            .post(
              s"/tasks/${task.id.value}/pushNotificationConfigs",
              Body.fromString(TaskPushNotificationConfig(url = "http://callback.test/3", id = Some("cfg-3")).toJson),
            )
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        mismatchResponse <- server.handleHttp(
          Request
            .post(
              s"/tasks/${task.id.value}/pushNotificationConfigs",
              Body.fromString(
                TaskPushNotificationConfig(
                  url = "http://callback.test/mismatch",
                  id = Some("cfg-mismatch"),
                  taskId = Some(TaskId(s"${task.id.value}-other")),
                ).toJson
              ),
            )
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        mismatchBody <- mismatchResponse.body.asString
        page1Response <- server.handleHttp(
          Request
            .get(URL.decode(s"/tasks/${task.id.value}/pushNotificationConfigs?pageSize=2").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        page1Body <- page1Response.body.asString
        page1     <- ZIO.fromEither(page1Body.fromJson[A2AResponse.PushNotificationConfigListResult].left.map(new RuntimeException(_)))
        page2Response <- server.handleHttp(
          Request
            .get(
              URL.decode(
                s"/tasks/${task.id.value}/pushNotificationConfigs?page_size=2&page_token=${page1.nextPageToken.getOrElse("")}"
              ).toOption.get
            )
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        page2Body <- page2Response.body.asString
        page2     <- ZIO.fromEither(page2Body.fromJson[A2AResponse.PushNotificationConfigListResult].left.map(new RuntimeException(_)))
        invalidResponse <- server.handleHttp(
          Request
            .get(URL.decode(s"/tasks/${task.id.value}/pushNotificationConfigs?page_token=-1").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        invalidBody <- invalidResponse.body.asString
        missingResponse <- server.handleHttp(
          Request
            .get(URL.decode(s"/tasks/${task.id.value}/pushNotificationConfigs/cfg-missing").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        missingBody <- missingResponse.body.asString
      yield (page1Response, page1, page2Response, page2, invalidResponse, invalidBody, missingResponse, missingBody, mismatchResponse, mismatchBody)

    runTask(program).map {
      case (
            page1Response,
            page1,
            page2Response,
            page2,
            invalidResponse,
            invalidBody,
            missingResponse,
            missingBody,
            mismatchResponse,
            mismatchBody,
          ) =>
      assertEquals(page1Response.status, Status.Ok)
      assertEquals(page1.configs.flatMap(_.id), List("cfg-1", "cfg-2"))
      assert(page1.nextPageToken.exists(_.startsWith("v1:")))
      assertEquals(page2Response.status, Status.Ok)
      assertEquals(page2.configs.flatMap(_.id), List("cfg-3"))
      assertEquals(page2.nextPageToken, None)
      assertEquals(invalidResponse.status, Status.BadRequest)
      assert(invalidBody.contains("Invalid pageToken"))
      assertEquals(missingResponse.status, Status.NotFound)
      assert(missingBody.contains("TASK_NOT_FOUND"))
      assert(missingBody.contains("Push notification config not found: cfg-missing"))
      assertEquals(mismatchResponse.status, Status.BadRequest)
      assert(mismatchBody.contains("TaskPushNotificationConfig.taskId does not match path taskId"))
    }

  test("JVM REST task list accepts proto snake-case query aliases"):
    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "RestSnakeQueryJvmTest",
            description = "REST snake query JVM test server",
          )
        )
        pageSizeResponse <- server.handleHttp(
          Request.get(URL.decode("/tasks?page_size=0").toOption.get).copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        pageSizeBody <- pageSizeResponse.body.asString
        historyResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?history_length=-1").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        historyBody <- historyResponse.body.asString
        tokenResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?page_token=not-a-number").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        tokenBody <- tokenResponse.body.asString
        includeResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?include_artifacts=maybe").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        includeBody <- includeResponse.body.asString
        timestampResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?status_timestamp_after=not-a-timestamp").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        timestampBody <- timestampResponse.body.asString
        unspecifiedStatusResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks?status=TASK_STATE_UNSPECIFIED").toOption.get)
            .copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        unspecifiedStatusBody <- unspecifiedStatusResponse.body.asString
      yield (
        pageSizeResponse,
        pageSizeBody,
        historyResponse,
        historyBody,
        tokenResponse,
        tokenBody,
        includeResponse,
        includeBody,
        timestampResponse,
        timestampBody,
        unspecifiedStatusResponse,
        unspecifiedStatusBody,
      )

    runTask(program).map {
      case (
            pageSizeResponse,
            pageSizeBody,
            historyResponse,
            historyBody,
            tokenResponse,
            tokenBody,
            includeResponse,
            includeBody,
            timestampResponse,
            timestampBody,
            unspecifiedStatusResponse,
            unspecifiedStatusBody,
          ) =>
        assertEquals(pageSizeResponse.status, Status.BadRequest)
        assert(pageSizeBody.contains("pageSize must be between 1 and 100"))
        assertEquals(historyResponse.status, Status.BadRequest)
        assert(historyBody.contains("historyLength must be non-negative"))
        assertEquals(tokenResponse.status, Status.BadRequest)
        assert(tokenBody.contains("Invalid pageToken"))
        assertEquals(includeResponse.status, Status.BadRequest)
        assert(includeBody.contains("includeArtifacts must be a valid boolean"))
        assertEquals(timestampResponse.status, Status.BadRequest)
        assert(timestampBody.contains("statusTimestampAfter must be an ISO 8601 UTC timestamp"))
        assertEquals(unspecifiedStatusResponse.status, Status.BadRequest)
        assert(unspecifiedStatusBody.contains("status must be one of"))
    }

  test("JVM REST SubscribeToTask accepts proto GET and documented POST bindings"):
    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "RestSubscribeVerbJvmTest",
            description = "REST subscribe verb JVM test server",
          )
        )
        getResponse <- server.handleHttp(
          Request.get("/tasks/task-1:subscribe").copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        getBody <- getResponse.body.asString
        postResponse <- server.handleHttp(
          Request.post("/tasks/task-1:subscribe", Body.empty).copy(headers = versionedJsonHeaders(A2AContentType.A2AJson))
        )
        postBody <- postResponse.body.asString
      yield (getResponse, getBody, postResponse, postBody)

    runTask(program).map { case (getResponse, getBody, postResponse, postBody) =>
      assertEquals(getResponse.status, Status.NotFound)
      assert(getResponse.headers.get("content-type").exists(_.startsWith(A2AContentType.A2AJson)))
      assert(getBody.contains("TASK_NOT_FOUND"))
      assertEquals(postResponse.status, Status.NotFound)
      assert(postResponse.headers.get("content-type").exists(_.startsWith(A2AContentType.A2AJson)))
      assert(postBody.contains("TASK_NOT_FOUND"))
    }

  test("JVM REST treats empty A2A-Version parameter as protocol 0.3"):
    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "EmptyVersionJvmTest",
            description = "REST empty version JVM test server",
          )
        )
        response <- server.handleHttp(Request.get(URL.decode("/tasks?A2A-Version=").toOption.get))
        body     <- response.body.asString
      yield (response, body)

    runTask(program).map { case (response, body) =>
      assertEquals(response.status, Status.BadRequest)
      assert(body.contains("VERSION_NOT_SUPPORTED"))
      assert(body.contains("Version not supported: 0.3"))
    }

  test("JVM HTTP bindings echo activated standard extension headers"):
    val supportedExtension   = "https://example.test/extensions/jvm-supported/v1"
    val unsupportedExtension = "https://example.test/extensions/jvm-unsupported/v1"
    val requestedExtensions  = s"$unsupportedExtension,$supportedExtension,$supportedExtension"
    val body = JsonRpcRequest(
      method = A2AMethod.TasksList,
      params = A2ARequest.TasksList().toJsonAST.toOption,
      id = Some(JsonRpcId.Num(31)),
    ).toJson

    val program =
      for
        server <- testServer(
          A2AServerLive.Config(
            name = "ActivatedExtensionJvmTest",
            description = "Activated extension JVM test server",
            capabilities = AgentCapabilities.default.copy(
              extensions = List(AgentExtension(uri = supportedExtension))
            ),
          )
        )
        jsonRpcResponse <- server.handleHttp(
          Request
            .post("/", Body.fromString(body))
            .copy(headers =
              headers(
                "Content-Type" -> A2AContentType.Json,
                A2AHeader.Version -> A2AProtocol.Version,
                A2AHeader.StandardExtensions -> requestedExtensions,
              )
            )
        )
        jsonRpcBody <- jsonRpcResponse.body.asString
        jsonRpc     <- ZIO.fromEither(jsonRpcBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        restResponse <- server.handleHttp(
          Request
            .get(URL.decode("/tasks").toOption.get)
            .copy(headers =
              headers(
                A2AHeader.Version -> A2AProtocol.Version,
                A2AHeader.StandardExtensions -> requestedExtensions,
              )
            )
        )
        restBody <- restResponse.body.asString
        rest     <- ZIO.fromEither(restBody.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
      yield (jsonRpcResponse, jsonRpc, restResponse, rest)

    runTask(program).map { case (jsonRpcResponse, jsonRpc, restResponse, rest) =>
      assertEquals(jsonRpc.error, None)
      assertEquals(jsonRpcResponse.headers.get(A2AHeader.StandardExtensions), Some(supportedExtension))
      assertEquals(rest.tasks, Nil)
      assertEquals(restResponse.headers.get(A2AHeader.StandardExtensions), Some(supportedExtension))
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
      assert(!body.contains(""""final""""))
    }

  private final case class TestTlsFiles(cert: File, key: File)

  private def testTlsFiles: ZIO[Scope, Throwable, TestTlsFiles] =
    ZIO.acquireRelease {
      ZIO.attempt {
        val cert = Files.createTempFile("scalagent-a2a-test-", ".cert.pem")
        val key  = Files.createTempFile("scalagent-a2a-test-", ".key.pem")
        Files.writeString(cert, TestTlsCertPem)
        Files.writeString(key, TestTlsKeyPem)
        TestTlsFiles(cert.toFile, key.toFile)
      }
    } { files =>
      ZIO
        .attempt {
          Files.deleteIfExists(files.cert.toPath)
          Files.deleteIfExists(files.key.toPath)
        }
        .ignore
    }

  private val TestTlsCertPem =
    """-----BEGIN CERTIFICATE-----
      |MIIDJTCCAg2gAwIBAgIUZaYnYx5IJbkwfibIB+0Ibjgv2XowDQYJKoZIhvcNAQEL
      |BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDYwMTEwMjEyNloXDTM2MDUy
      |OTEwMjEyNlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
      |AAOCAQ8AMIIBCgKCAQEAuhxP3IKnszz3BBb8OuFoYlIrJZcZsEoLqpWRL3a04+P7
      |I6p5bZ9ghwzDD1Enicdd1Z/vVE9AtzDPeFk5ghfDSosqjK6wTdQGXAQ1rMD5+OCN
      |ClDD9VlGpbtaUYP3CTJ7BJtKqmabQbFSQXJLKsOlacyhPe9o9w+m36UajVLZcoFo
      |F/RcRNq9l1IaH482HoWqu7MvJKcfEXH68YLQifyscG+wcFhkDKqp+vY8F/o/QcWB
      |wNAyMou5BX96OqyovciEeOekMDtctsfE5mvGbTkNgBjG+43yXvZbhXYj/UbBrQz0
      |3gLFrFfl/LBc08gN+RExxBYKpDR34J3+Oz/pUDNfBwIDAQABo28wbTAdBgNVHQ4E
      |FgQU2BO5TKsvA6oMZ7JTkOMSTzEPb14wHwYDVR0jBBgwFoAU2BO5TKsvA6oMZ7JT
      |kOMSTzEPb14wDwYDVR0TAQH/BAUwAwEB/zAaBgNVHREEEzARgglsb2NhbGhvc3SH
      |BH8AAAEwDQYJKoZIhvcNAQELBQADggEBAF2wQTwOqWFlqCGaEC97/ZB99ATvTTiF
      |GF9TmiOb/86cDtzBAMWdAeZRdefbCfQcRljLSIcXfGk+NpnUK+ZMWiEH9tDKK/eu
      |hcJg2Q5jYLnxKDrd/3oloJwPKls1NYi3qHw31CG2C+em6mMWSE3oF3iWRvJVoeRJ
      |fHaaCV1rTTvvc8jwdv88p0YRNLgtZzjcFT9eMGcp6x1o0s0ZeT/syzEGiLVq7Twf
      |psvQNi/ylPqhfSALLms+cyzA54zkDcCHOlFaDlTI/fni8sCn4yM5LXvNF+TMvKJw
      |h9pyoMNYZgp4aV1qJVfF2Hlc9Rb/CCggWhxtXUeuRaVHxs8Ov7SzdY8=
      |-----END CERTIFICATE-----
      |""".stripMargin

  private val TestTlsKeyPem =
    """-----BEGIN PRIVATE KEY-----
      |MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQC6HE/cgqezPPcE
      |Fvw64WhiUisllxmwSguqlZEvdrTj4/sjqnltn2CHDMMPUSeJx13Vn+9UT0C3MM94
      |WTmCF8NKiyqMrrBN1AZcBDWswPn44I0KUMP1WUalu1pRg/cJMnsEm0qqZptBsVJB
      |cksqw6VpzKE972j3D6bfpRqNUtlygWgX9FxE2r2XUhofjzYehaq7sy8kpx8Rcfrx
      |gtCJ/Kxwb7BwWGQMqqn69jwX+j9BxYHA0DIyi7kFf3o6rKi9yIR456QwO1y2x8Tm
      |a8ZtOQ2AGMb7jfJe9luFdiP9RsGtDPTeAsWsV+X8sFzTyA35ETHEFgqkNHfgnf47
      |P+lQM18HAgMBAAECggEAJbttuZBHvcAjeJHMa4edqSltk/5xd9tbSCdwuwW/IODs
      |3stGOSJx6I9+0JEsifOAo7n8RMSYo0tjFMxKK4Tz1B4o70LPfcf5zhgQZcjuJTYp
      |gijjwc9q0lkMs7AkmpnAdSui1K9e1M/FlH0+nhnyZGPXYP4z8rsaowcPPg3JBjy7
      |wuMhIbHYfzGHItIVC85pDbCX4SzLkwQw1m0XG8Go9GvKHcG/txFInlWPgJQXxOt3
      |dLZQHYc8ZA2XRPvrCA/zi5IJDH2bAwwc0zSQxLOZ172MVo80qxWFXuhQl4ATXQZi
      |mKVFWh/28k0TRJw2SM1R2x+I+bNfBaHW0p+KRmIGmQKBgQDao/TOzxNk/iKTL4Ww
      |XqLgY8zysOtBMmM7AWF4nQg7e7E2wbccjxwegCLxEnlJdx16oLImJlLaMei9hewd
      |tEaVWZSz4TigDXWR4vMpkiFRUIvhc1UrfMLUUDHWtG4bGGF4AWPXEYcV0dlBih1Y
      |yaEcbdWMbanxPsyz11NeAuvQZQKBgQDZ6WTWrYmmTR6G/R2BPkSwQQRb0rnoAmXR
      |5vXny34b8txeBgcJyC2A1SBJdUxkp0cWq6JCnWRRpui7J1Q+RymBnkNDQxJkLMvf
      |Xp2WJVI4Fb6Qt4fr0BFDQ7kbmUDyAkjPSQTubHj5RySbSMCFHS4tfTiwIRIwmudD
      |8j3y8Lcc+wKBgQCHa6XlsjzBAOdJYtXbN8KKWUZHy2zrJNpxYZmNqzW+Ig7Ra4qP
      |FdTEz3jU+CxHZI/NtFqjZnlKzD7rpFdqzo4pUyLXh1gbSjrX8UnLJcedJdZ9/YFz
      |PgMunb1AzuCjx6YXPkUooKKa0S9PeMxUgg5YHW93WzU3Rz5i5autPwHwQQKBgQDX
      |XxLYDtpeMBh86EwyAd4XqZrqOiKdyUjjJXdjaj8w1l6w2xo3s84tZ/eqQrGHRcFA
      |CdCsMC0HeoTI/L0JdIH0ZvwpzW+u7ItvMG9mB2r0naEkHRDMo298YMHiIh0LU/Cs
      |Von2L+V80rC+fTAyID4UnY/anEUDHwZ1pEVQCFOi4wKBgQCPUtiseh92aYxgb6/c
      |IInNyJN5HL7fz7ToJqv9RFgTQK1GJIklGGXp4fgW2qKKAUDVcJ4mrJa9PsaqyJ3z
      |IaCybn/aDzqVP3y/rMUoPN67MwL7VNp8VsVbuN49W01Lk8es2xkil21oJ5Xpvf6C
      |O21Pk46ptm5eCd1GLyPFiNHVgQ==
      |-----END PRIVATE KEY-----
      |""".stripMargin
end A2AServerLiveSpec
