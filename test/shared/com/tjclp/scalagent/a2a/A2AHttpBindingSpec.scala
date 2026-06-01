package com.tjclp.scalagent.a2a

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import munit.FunSuite

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

class A2AHttpBindingSpec extends FunSuite:
  private val runtime = Runtime.default

  private final case class TestConfig(
    capabilities: AgentCapabilities = AgentCapabilities.default,
    extendedAgentCard: Option[AgentCard] = None,
    pushNotificationStore: Option[A2APushNotificationStore] = None,
    taskStore: Option[A2ATaskStore] = None,
    eventStore: Option[A2AEventStore] = None,
    replayProvider: Option[A2AReplayProvider] = None,
    eventReplayLimit: Int = A2AServerDefaults.EventReplayLimit,
    eventStoreAppendTimeout: Duration = A2AServerDefaults.EventStoreAppendTimeout,
    eventStoreLoadTimeout: Duration = A2AServerDefaults.EventStoreLoadTimeout,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy = A2AServerDefaults.PushUrlPolicy,
    override val agentCardAuth: A2AAgentCardAuth = A2AAgentCardAuth.permitAll,
    extendedAgentCardAuth: A2AExtendedAgentCardAuth = A2AExtendedAgentCardAuth.requireAuthorizationHeader,
    requestAuth: A2ARequestAuth = A2ARequestAuth.requireAuthorizationWhenAdvertised)
      extends A2AServerCoreConfig

  private final case class TestRequest(
    headers: Map[String, String] = Map.empty,
    query: Map[String, String] = Map.empty,
    body: String = "",
    methodName: String = "GET",
    path: String = "/tasks/task-1")
      extends A2AHttpRequestView:
    def header(name: String): Option[String]     = headers.get(name)
    override def headerEntries: Iterable[(String, String)] = headers
    def queryParam(name: String): Option[String] = query.get(name)
    override def queryParams: Iterable[(String, String)] = query
    def readBody: Task[String]                   = ZIO.succeed(body)

  private final case class LimitedTestRequest(
    headers: Map[String, String],
    maxRequestBodyBytes: Int,
    body: Task[String])
      extends A2ALimitedHttpRequestView:
    def methodName: String                        = "POST"
    def path: String                              = "/"
    def header(name: String): Option[String]      = headers.get(name)
    override def headerEntries: Iterable[(String, String)] = headers
    def queryParam(name: String): Option[String]  = None
    protected def readBodyAfterContentLength(maxBytes: Int): Task[String] = body

  private object NoopPushPoster extends A2APushNotificationPoster:
    def post(
      event: A2AResponse.StreamEvent,
      config: TaskPushNotificationConfig,
      headers: List[(String, String)],
    ): Task[Unit] =
      ZIO.unit

  private enum TestResponse:
    case Empty(status: Int, headers: List[(String, String)])
    case Text(body: String, status: Int, headers: List[(String, String)])
    case Sse(wireStream: ZStream[Any, Nothing, String], headers: List[(String, String)])

  private object TestRenderer extends A2AHttpResponseRenderer[TestResponse]:
    def render(plan: A2AHttpResponsePlan): TestResponse =
      renderHttpResponse(plan)

    protected def emptyResponse(status: Int, headers: List[(String, String)]): TestResponse =
      TestResponse.Empty(status, headers)

    protected def textResponse(
      body: String,
      status: Int,
      headers: List[(String, String)],
    ): TestResponse =
      TestResponse.Text(body, status, headers)

    protected def sseResponse(
      wireStream: ZStream[Any, Nothing, String],
      headers: List[(String, String)],
    ): TestResponse =
      TestResponse.Sse(wireStream, headers)

  private def runUIO[A](effect: UIO[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
    }

  private def runTask[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
    }

  private def testCard(capabilities: AgentCapabilities = AgentCapabilities.default): AgentCard =
    A2AServerAgentCard(
      name = "HttpBinding",
      description = "Shared HTTP binding",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = capabilities,
      skills = Nil,
      tenant = None,
    )

  test("content type validation is shared across HTTP bindings"):
    assertEquals(A2AHttpBinding.mediaType(" Application/A2A+JSON ; charset=utf-8 "), A2AContentType.A2AJson)
    assertEquals(A2AHttpBinding.validateContentType(Some("application/a2a+json; charset=utf-8"), A2AContentType.A2AJson), Right(()))
    assertEquals(
      A2AHttpBinding.requestContentType(TestRequest(headers = Map("CONTENT-TYPE" -> "application/a2a+json"))),
      Some("application/a2a+json"),
    )
    assertEquals(
      A2AHttpBinding.requestContentType(TestRequest(headers = Map("CoNtEnT-TyPe" -> "application/a2a+json"))),
      Some("application/a2a+json"),
    )
    assertEquals(
      A2AHttpBinding.validateContentType(Some("application/json"), A2AContentType.A2AJson).left.map(_.code),
      Left(A2AErrorCode.ContentTypeNotSupported),
    )
    assertEquals(
      A2AHttpBinding.validateContentType(None, A2AContentType.Json).left.map(_.message),
      Left("Content type not supported: <missing>"),
    )

  test("limited request view checks Content-Length before platform body reads"):
    var didRead = false
    val request = LimitedTestRequest(
      headers = Map("Content-Length" -> "9"),
      maxRequestBodyBytes = 8,
      body = ZIO.attempt {
        didRead = true
        "too large"
      },
    )

    runTask(request.readBody.either).map { result =>
      assertEquals(result.left.toOption.collect { case error: A2AError => error.code }, Some(A2AErrorCode.InvalidRequest))
      assertEquals(didRead, false)
    }

  test("request context extraction is shared across HTTP bindings"):
    val context = A2AHttpBinding.contextFrom(
      TestRequest(
        headers = Map(
          "A2a-VeRsIoN"    -> "1.0",
          "A2a-ExTeNsIoNs" -> "urn:a, urn:b,,",
          "aUtHoRiZaTiOn"  -> "Bearer shared-token",
        )
      ),
      Some("tenant-a"),
    )

    assertEquals(context.tenant, Some("tenant-a"))
    assertEquals(context.requestedVersion, Some("1.0"))
    assertEquals(context.requestedExtensions, List("urn:a", "urn:b"))
    assertEquals(context.authorization, Some("Bearer shared-token"))

    val parameterContext = A2AHttpBinding.contextFromRequestParameters(
      TestRequest(
        headers = Map(
          "A2a-ExTeNsIoNs" -> "urn:header"
        ),
        query = Map(
          "A2a-VeRsIoN"    -> "1.0",
          "a2aExtensions"  -> "urn:query, urn:header",
        ),
      ),
      None,
    )

    assertEquals(parameterContext.requestedVersion, Some("1.0"))
    assertEquals(parameterContext.requestedExtensions, List("urn:header", "urn:query"))

  test("REST service parameter query names are fully case-insensitive"):
    val query = A2APathRouting.query(
      Map(
        "A2a-VeRsIoN" -> "1.0",
        "PaGeSiZe"    -> "25",
      ).toList
    )

    assertEquals(A2APathRouting.requestedVersion(query), Some("1.0"))
    assertEquals(query.int("pageSize"), Right(Some(25)))

  test("message/send execution mode defaults match A2A while async remains opt-in"):
    val missingConfiguration =
      """{"jsonrpc":"2.0","method":"SendMessage","params":{"message":{"messageId":"m1","role":"ROLE_USER","parts":[{"text":"hello"}]}},"id":1}"""
    val explicitReturnImmediately =
      """{"jsonrpc":"2.0","method":"SendMessage","params":{"message":{"messageId":"m1","role":"ROLE_USER","parts":[{"text":"hello"}]},"configuration":{"returnImmediately":false}},"id":1}"""
    val restBody =
      """{"message":{"messageId":"m1","role":"ROLE_USER","parts":[{"text":"hello"}]}}"""

    def jsonRpcConfig(body: String, mode: ExecutionMode): Option[MessageSendConfiguration] =
      A2AMessageSendDefaults
        .normalizeJsonRpcBodyForMode(body, mode)
        .fromJson[Json]
        .toOption
        .flatMap(_.asObject)
        .flatMap(_.toMap("params").asObject)
        .flatMap(_.toMap("configuration").as[MessageSendConfiguration].toOption)

    def restConfig(body: String, mode: ExecutionMode): Option[MessageSendConfiguration] =
      A2AMessageSendDefaults
        .normalizeMessageSendBodyForMode(body, mode)
        .fromJson[A2ARequest.MessageSend]
        .toOption
        .flatMap(_.configuration)

    val defaultMissing = jsonRpcConfig(missingConfiguration, ExecutionMode.Default)
    val asyncMissing = jsonRpcConfig(missingConfiguration, ExecutionMode.Asynchronous)
    val normalizedExplicit = jsonRpcConfig(explicitReturnImmediately, ExecutionMode.Asynchronous)
    val defaultRest = restConfig(restBody, ExecutionMode.Default)
    val asyncRest = restConfig(restBody, ExecutionMode.Asynchronous)

    assertEquals(ExecutionMode.Default, ExecutionMode.Synchronous)
    assertEquals(defaultMissing.map(config => (config.blocking, config.returnImmediately)), Some((Some(true), false)))
    assertEquals(asyncMissing.map(config => (config.blocking, config.returnImmediately)), Some((Some(false), true)))
    assertEquals(normalizedExplicit.map(config => (config.blocking, config.returnImmediately)), Some((Some(true), false)))
    assertEquals(defaultRest.map(config => (config.blocking, config.returnImmediately)), Some((Some(true), false)))
    assertEquals(asyncRest.map(config => (config.blocking, config.returnImmediately)), Some((Some(false), true)))

  test("body size limit checks use the same error across HTTP bindings"):
    assertEquals(A2AHttpBinding.validateContentLength(Some(10L), maxBytes = 10), Right(()))
    assertEquals(
      A2AHttpBinding.validateContentLength(Some(11L), maxBytes = 10).left.map(_.message),
      Left("Invalid request: Request body exceeds 10 byte limit"),
    )
    assertEquals(
      A2AHttpBinding.validateContentLength(Some(-1L), maxBytes = 10).left.map(_.message),
      Left("Invalid request: Content-Length must be a valid non-negative integer"),
    )
    assertEquals(
      A2AHttpBinding.validateContentLengthHeader(Some("not-a-number"), maxBytes = 10).left.map(_.message),
      Left("Invalid request: Content-Length must be a valid non-negative integer"),
    )
    assertEquals(A2AHttpBinding.validateBodyLength(11L, maxBytes = 0), Right(()))
    runTask(
      A2AHttpBinding
        .validateRequestContentLength(TestRequest(headers = Map("CoNtEnT-LeNgTh" -> "11")), maxBytes = 10)
        .either
    ).map { result =>
      result match
        case Left(error: A2AError) =>
          assertEquals(error.message, "Invalid request: Request body exceeds 10 byte limit")
        case other =>
          fail(s"expected A2AError, got $other")
    }

  test("limited request view rejects malformed Content-Length before body reads"):
    var didRead = false
    val request = LimitedTestRequest(
      headers = Map("content-length" -> "nope"),
      maxRequestBodyBytes = 8,
      body = ZIO.attempt {
        didRead = true
        "ignored"
      },
    )

    runTask(request.readBody.either).map { result =>
      assertEquals(result.left.toOption.collect { case error: A2AError => error.message }, Some("Invalid request: Content-Length must be a valid non-negative integer"))
      assertEquals(didRead, false)
    }

  test("REST error body follows google.rpc Status JSON representation"):
    val body   = A2AHttpBinding.restErrorBody(A2AError.taskNotFound(TaskId("task-1")))
    val top    = body.asObject.get.toMap
    val fields = top("error").asObject.get.toMap
    val detail = fields("details").asArray.get.head.asObject.get.toMap
    val failedPrecondition =
      A2AHttpBinding
        .restErrorBody(A2AError.taskNotCancelable(TaskId("task-1")))
        .asObject
        .get
        .toMap("error")
        .asObject
        .get
        .toMap

    assertEquals(top("type"), Json.Str("https://a2a-protocol.org/errors/task-not-found"))
    assertEquals(top("title"), Json.Str("Not Found"))
    assertEquals(top("status"), Json.Num(java.math.BigDecimal.valueOf(404L)))
    assertEquals(fields("code"), Json.Num(java.math.BigDecimal.valueOf(404L)))
    assertEquals(fields("status"), Json.Str("NOT_FOUND"))
    assertEquals(failedPrecondition("code"), Json.Num(java.math.BigDecimal.valueOf(400L)))
    assertEquals(failedPrecondition("status"), Json.Str("FAILED_PRECONDITION"))
    assertEquals(detail("@type"), Json.Str(A2AError.ErrorInfoType))
    assertEquals(detail("reason"), Json.Str("TASK_NOT_FOUND"))
    assertEquals(detail("domain"), Json.Str(A2AError.ErrorInfoDomain))

  test("shared REST dispatch satisfies ACTS missing-task problem-details shape"):
    val card = testCard()
    val effect =
      for
        registry <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(),
          runtime,
          registry,
          NoopPushPoster,
          () => card,
          (_, _) => ZIO.unit,
        )
        plan <- A2AHttpBinding.dispatchHttp(
          TestRequest(
            headers = Map(A2AHeader.Version -> A2AProtocol.Version),
            methodName = "GET",
            path = "/tasks/nonexistent-id",
          ),
          card,
          AgentCapabilities.default,
          core.requestHandler,
        )
      yield plan

    runUIO(effect).map {
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        val headerMap = headers.toMap
        val fields    = body.fromJson[Json].toOption.flatMap(_.asObject).map(_.toMap).getOrElse(Map.empty)
        assertEquals(status, 404)
        assertEquals(headerMap("Content-Type"), A2AContentType.A2AJson)
        assertEquals(fields.get("type"), Some(Json.Str("https://a2a-protocol.org/errors/task-not-found")))
        assertEquals(fields.get("title"), Some(Json.Str("Not Found")))
        assertEquals(fields.get("status"), Some(Json.Num(java.math.BigDecimal.valueOf(404L))))
        assert(fields.get("error").exists(_.asObject.exists(_.toMap.contains("details"))))
      case other => fail(s"expected REST missing-task text plan, got $other")
    }

  test("stream error serialization is shared for REST and JSON-RPC SSE"):
    val error   = A2AError.unsupportedOperation("Streaming not supported")
    val id      = Some(JsonRpcId.Num(17))
    val rest    = A2AHttpBinding.streamErrorJson(error, isJsonRpc = false)
    val jsonRpc = A2AHttpBinding.streamErrorJson(error, isJsonRpc = true, id)

    assert(rest.fromJson[Json].exists(_.asObject.exists(_.toMap.contains("error"))))
    assert(jsonRpc.fromJson[JsonRpcResponse].exists(_.error.exists(_.code == A2AErrorCode.UnsupportedOperation)))
    assertEquals(jsonRpc.fromJson[JsonRpcResponse].toOption.flatMap(_.id), id)

  test("SSE wire stream framing is shared across platform renderers"):
    val body   = """{"ok":true}"""
    val stream = ZStream.succeed(body) ++ ZStream.fail(A2AError.taskNotFound(TaskId("task-1")))

    runTask(A2AHttpBinding.sseWireStream(stream, isJsonRpc = false).runCollect.map(_.toList)).map { frames =>
      assertEquals(frames.head, A2AHttpBinding.sseDataFrame(body))
      assert(frames(1).startsWith("event: error\ndata: "))
      assert(frames(1).endsWith("\n\n"))

      val errorJson = frames(1).stripPrefix("event: error\ndata: ").stripSuffix("\n\n")
      assert(errorJson.fromJson[Json].exists(_.asObject.exists(_.toMap.contains("error"))))
    }

  test("JSON-RPC SSE error frames preserve the request id"):
    val id     = Some(JsonRpcId.Num(42))
    val stream = ZStream.fail(A2AError.taskNotFound(TaskId("task-1")))

    runTask(
      A2AHttpBinding
        .sseWireStream(stream, isJsonRpc = true, errorId = id, keepAliveInterval = Duration.Zero)
        .runCollect
        .map(_.toList)
    ).map { frames =>
      val errorJson = frames.head.stripPrefix("event: error\ndata: ").stripSuffix("\n\n")
      val response  = errorJson.fromJson[JsonRpcResponse].toOption.get
      assertEquals(response.id, id)
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.TaskNotFound))
    }

  test("shared HTTP response renderer gives platform adapters pre-framed SSE"):
    val body = """{"ok":true}"""
    val plan = A2AHttpResponsePlan.Sse(
      ZStream.succeed(body) ++ ZStream.fail(A2AError.taskNotFound(TaskId("task-1"))),
      isJsonRpc = false,
      headers = List("Content-Type" -> A2AContentType.Sse),
    )

    TestRenderer.render(plan) match
      case TestResponse.Sse(wireStream, headers) =>
        assertEquals(headers.toMap.get("Content-Type"), Some(A2AContentType.Sse))
        runTask(wireStream.runCollect.map(_.toList)).map { frames =>
          assertEquals(frames.head, A2AHttpBinding.sseDataFrame(body))
          assert(frames(1).startsWith("event: error\ndata: "))
          assert(frames(1).endsWith("\n\n"))
        }
      case other =>
        fail(s"expected rendered SSE response, got $other")

  test("extension response header value is emitted only when active extensions exist"):
    assertEquals(A2AHttpBinding.extensionsHeader(Nil), None)
    assertEquals(A2AHttpBinding.extensionsHeader(List("urn:a", "urn:b")), Some("urn:a,urn:b"))

  test("agent card response plan includes shared cache validators and honors If-None-Match"):
    val card = testCard()
    val etag = A2AHttpBinding.agentCardEtag(card)

    A2AHttpBinding.agentCardPlan(TestRequest(path = A2APaths.AgentCard), card) match
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        val headerMap = headers.toMap
        assertEquals(status, 200)
        assertEquals(headerMap("Content-Type"), A2AContentType.Json)
        assertEquals(headerMap("Cache-Control"), "public, max-age=60")
        assertEquals(headerMap("ETag"), etag)
        assertEquals(body.fromJson[AgentCard].toOption.map(_.name), Some(card.name))
      case other => fail(s"expected agent card text plan, got $other")

    A2AHttpBinding.agentCardPlan(
      TestRequest(path = A2APaths.AgentCard, headers = Map("If-None-Match" -> s"""W/$etag, "older"""")),
      card,
    ) match
      case A2AHttpResponsePlan.Empty(status, headers) =>
        val headerMap = headers.toMap
        assertEquals(status, 304)
        assertEquals(headerMap("Cache-Control"), "public, max-age=60")
        assertEquals(headerMap("ETag"), etag)
        assert(!headerMap.contains("Content-Type"))
      case other => fail(s"expected not-modified empty plan, got $other")

  test("top-level HTTP dispatch can require auth for public Agent Card discovery"):
    val card = testCard().copy(
      securitySchemes = Map("bearer" -> SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth")),
      securityRequirements = List(SecurityRequirement(Map("bearer" -> List("read:card")))),
    )
    val effect =
      for
        registry <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(agentCardAuth = A2AAgentCardAuth.requireAuthorizationHeader),
          runtime,
          registry,
          NoopPushPoster,
          () => card,
          (_, _) => ZIO.unit,
        )
        rejected <- A2AHttpBinding.dispatchHttp(
          TestRequest(methodName = "GET", path = A2APaths.AgentCard),
          card,
          AgentCapabilities.default,
          core.requestHandler,
        )
        accepted <- A2AHttpBinding.dispatchHttp(
          TestRequest(methodName = "GET", path = A2APaths.AgentCard, headers = Map("authorization" -> "Bearer token")),
          card,
          AgentCapabilities.default,
          core.requestHandler,
        )
      yield rejected -> accepted

    runUIO(effect).map { case (rejected, accepted) =>
      rejected match
        case A2AHttpResponsePlan.Text(body, status, headers) =>
          val fields = body.fromJson[Json].toOption.flatMap(_.asObject).map(_.toMap).getOrElse(Map.empty)
          assertEquals(status, 401)
          assertEquals(headers.toMap.get("Content-Type"), Some(A2AContentType.A2AJson))
          assertEquals(fields.get("title"), Some(Json.Str("Unauthenticated")))
          assert(fields.get("error").exists(_.asObject.nonEmpty))
        case other => fail(s"expected unauthorized problem-details plan, got $other")

      accepted match
        case A2AHttpResponsePlan.Text(body, status, headers) =>
          assertEquals(status, 200)
          assertEquals(headers.toMap.get("Content-Type"), Some(A2AContentType.Json))
          assertEquals(body.fromJson[AgentCard].toOption.map(_.name), Some(card.name))
        case other => fail(s"expected authorized agent card plan, got $other")
    }

  test("top-level HTTP dispatch is shared across platform servers"):
    val capabilities = AgentCapabilities.default
    val card         = testCard(capabilities)
    val agentCardRequest = TestRequest(
      methodName = "GET",
      path = A2APaths.AgentCard,
    )
    val jsonRpcRequest = TestRequest(
      headers = Map("content-type" -> A2AContentType.Json, A2AHeader.Version -> A2AProtocol.Version),
      methodName = "POST",
      path = "/",
      body = """{"jsonrpc":"2.0","method":"unknown/method","id":1}""",
    )
    val restRequest = TestRequest(
      headers = Map("content-type" -> A2AContentType.A2AJson),
      methodName = "GET",
      path = "/tasks//events",
    )
    val missingRequest = TestRequest(
      methodName = "GET",
      path = "/missing",
    )

    val effect =
      for
        registry <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(),
          runtime,
          registry,
          NoopPushPoster,
          () => card,
          (_, _) => ZIO.unit,
        )
        agentCardPlan <- A2AHttpBinding.dispatchHttp(agentCardRequest, card, capabilities, core.requestHandler)
        jsonRpcPlan   <- A2AHttpBinding.dispatchHttp(jsonRpcRequest, card, capabilities, core.requestHandler)
        restPlan      <- A2AHttpBinding.dispatchHttp(restRequest, card, capabilities, core.requestHandler)
        missingPlan   <- A2AHttpBinding.dispatchHttp(missingRequest, card, capabilities, core.requestHandler)
      yield (agentCardPlan, jsonRpcPlan, restPlan, missingPlan)

    runUIO(effect).map { case (agentCardPlan, jsonRpcPlan, restPlan, missingPlan) =>
      agentCardPlan match
        case A2AHttpResponsePlan.Text(body, status, headers) =>
          val headerMap = headers.toMap
          assertEquals(status, 200)
          assertEquals(headerMap("Content-Type"), A2AContentType.Json)
          assertEquals(headerMap("Cache-Control"), "public, max-age=60")
          assertEquals(headerMap("ETag"), A2AHttpBinding.agentCardEtag(card))
          assertEquals(body.fromJson[AgentCard].toOption.map(_.name), Some(card.name))
        case other => fail(s"expected agent card text plan, got $other")

      jsonRpcPlan match
        case A2AHttpResponsePlan.Text(body, status, headers) =>
          val headerMap = headers.toMap
          assertEquals(status, 200)
          assertEquals(headerMap("Content-Type"), A2AContentType.Json)
          assertEquals(body.fromJson[JsonRpcResponse].toOption.flatMap(_.error.map(_.code)), Some(A2AErrorCode.MethodNotFound))
        case other => fail(s"expected JSON-RPC text plan, got $other")

      restPlan match
        case A2AHttpResponsePlan.Text(body, status, headers) =>
          val headerMap = headers.toMap
          assertEquals(status, 400)
          assertEquals(headerMap("Content-Type"), A2AContentType.A2AJson)
          assert(body.fromJson[Json].exists(_.asObject.exists(_.toMap.contains("error"))))
        case other => fail(s"expected REST error text plan, got $other")

      missingPlan match
        case A2AHttpResponsePlan.Text(body, status, headers) =>
          val headerMap = headers.toMap
          assertEquals(status, 404)
          assertEquals(headerMap("Content-Type"), "text/plain")
          assertEquals(body, "Not Found")
        case other => fail(s"expected not found text plan, got $other")
    }

  test("shared HTTP response plans cover JSON-RPC, REST, and SSE wire decisions"):
    val id = Some(JsonRpcId(7L))
    val jsonRpcPlan = A2AHttpBinding.jsonRpcResponse(
      A2AJsonRpcDispatch.Single(
        JsonRpcResponse.success(id, Json.Obj("ok" -> Json.Bool(true))),
        List("urn:active"),
      )
    )
    jsonRpcPlan match
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        val headerMap = headers.toMap
        assertEquals(status, 200)
        assertEquals(headerMap("Content-Type"), A2AContentType.Json)
        assertEquals(headerMap(A2AHeader.StandardExtensions), "urn:active")
        assertEquals(body.fromJson[JsonRpcResponse].toOption.flatMap(_.id), id)
      case other => fail(s"expected JSON-RPC text response plan, got $other")

    val restPlan = A2AHttpBinding.restResponse(
      A2ARestDispatch.Error(A2AError.taskNotFound(TaskId("task-1")), List("urn:active"))
    )
    restPlan match
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        val headerMap = headers.toMap
        assertEquals(status, 404)
        assertEquals(headerMap("Content-Type"), A2AContentType.A2AJson)
        assert(body.fromJson[Json].exists(_.asObject.exists(_.toMap.contains("error"))))
      case other => fail(s"expected REST text response plan, got $other")

    val event = A2AResponse.StreamEvent.TaskStatusUpdate(
      TaskId("task-1"),
      ContextId("ctx-1"),
      TaskStatus.working(),
      `final` = false,
    )
    val streamPlan = A2AHttpBinding.jsonRpcResponse(A2AJsonRpcDispatch.Stream(id, ZStream.succeed(event), Nil))
    streamPlan match
      case A2AHttpResponsePlan.Sse(stream, isJsonRpc, headers, errorId) =>
        val headerMap = headers.toMap
        assert(isJsonRpc)
        assertEquals(errorId, id)
        assertEquals(headerMap("Content-Type"), A2AContentType.Sse)
        assertEquals(headerMap("Cache-Control"), "no-cache")
        runTask(stream.runCollect.map(_.toList)).map { frames =>
          val response = frames.head.fromJson[JsonRpcResponse].toOption.get
          assertEquals(response.id, id)
          assert(response.result.nonEmpty)
        }
      case other => fail(s"expected SSE response plan, got $other")

  test("SSE wire stream emits keep-alive comments while source is idle and then terminates"):
    val first  = """{"one":true}"""
    val second = """{"two":true}"""
    val stream =
      ZStream.succeed(first) ++
        ZStream.fromZIO(ZIO.sleep(35.millis).as(second))

    runTask(
      A2AHttpBinding
        .sseWireStream(stream, isJsonRpc = false, keepAliveInterval = 10.millis)
        .runCollect
        .timeoutFail(new RuntimeException("SSE keep-alive stream did not terminate"))(500.millis)
        .map(_.toList)
    ).map { frames =>
      val dataFrames = frames.filter(_.startsWith("data:"))
      assertEquals(dataFrames, List(A2AHttpBinding.sseDataFrame(first), A2AHttpBinding.sseDataFrame(second)))
      assert(frames.exists(_ == A2AHttpBinding.sseKeepAliveFrame))
    }

  test("JSON-RPC HTTP dispatch maps early transport errors in shared code"):
    val card = A2AServerAgentCard(
      name = "JsonRpcDispatch",
      description = "Shared JSON-RPC dispatch",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = AgentCapabilities.default,
      skills = Nil,
      tenant = None,
    )

    runUIO(
      A2AHttpBinding.jsonRpcDispatch(
        TestRequest(methodName = "POST", path = "/", body = "{}"),
        card,
        AgentCapabilities.default,
        null,
      )
    ).map {
      case A2AJsonRpcDispatch.Single(response, extensions) =>
        assertEquals(response.error.map(_.code), Some(A2AErrorCode.ContentTypeNotSupported))
        assertEquals(response.id, JsonRpcId.Unknown)
        assertEquals(extensions, Nil)
      case other => fail(s"expected single error dispatch, got $other")
    }

  test("JSON-RPC HTTP dispatch uses null id for malformed envelopes"):
    val card = A2AServerAgentCard(
      name = "JsonRpcDispatch",
      description = "Shared JSON-RPC dispatch",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = AgentCapabilities.default,
      skills = Nil,
      tenant = None,
    )
    val request = TestRequest(
      headers = Map("content-type" -> A2AContentType.Json),
      methodName = "POST",
      path = "/",
      body = """{"jsonrpc":"2.0","method":"GetTask"}""",
    )

    runUIO(A2AHttpBinding.jsonRpcDispatch(request, card, AgentCapabilities.default, null)).map {
      case A2AJsonRpcDispatch.Single(response, _) =>
        assertEquals(response.error.map(_.code), Some(A2AErrorCode.InvalidRequest))
        assertEquals(response.id, JsonRpcId.Unknown)
        assert(response.toJson.contains(""""id":null"""))
      case other => fail(s"expected single error dispatch, got $other")
    }

  test("JSON-RPC HTTP dispatch parses and routes with shared request context"):
    val extension    = AgentExtension("urn:optional")
    val capabilities = AgentCapabilities(extensions = List(extension))
    val card = A2AServerAgentCard(
      name = "JsonRpcDispatch",
      description = "Shared JSON-RPC dispatch",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = capabilities,
      skills = Nil,
      tenant = None,
    )
    val request = TestRequest(
      headers = Map(
        "content-type" -> A2AContentType.Json,
      ),
      query = Map(
        A2AHeader.Version            -> A2AProtocol.Version,
        A2AHeader.StandardExtensions -> "urn:optional, urn:unsupported",
      ),
      methodName = "POST",
      path = "/",
      body = """{"jsonrpc":"2.0","method":"unknown/method","id":1}""",
    )

    runUIO(A2AHttpBinding.jsonRpcDispatch(request, card, capabilities, null)).map {
      case A2AJsonRpcDispatch.Single(response, extensions) =>
        assertEquals(response.error.map(_.code), Some(A2AErrorCode.MethodNotFound))
        assertEquals(extensions, List("urn:optional"))
      case other => fail(s"expected single error dispatch, got $other")
    }

  test("JSON-RPC HTTP dispatch accepts and echoes null request ids"):
    val card = A2AServerAgentCard(
      name = "JsonRpcDispatch",
      description = "Shared JSON-RPC dispatch",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = AgentCapabilities.default,
      skills = Nil,
      tenant = None,
    )
    val request = TestRequest(
      headers = Map(
        "content-type" -> A2AContentType.Json,
      ),
      query = Map(
        A2AHeader.Version -> A2AProtocol.Version,
      ),
      methodName = "POST",
      path = "/",
      body = """{"jsonrpc":"2.0","method":"unknown/method","id":null}""",
    )

    runUIO(A2AHttpBinding.jsonRpcDispatch(request, card, AgentCapabilities.default, null)).map {
      case A2AJsonRpcDispatch.Single(response, _) =>
        assertEquals(response.id, Some(JsonRpcId.Null))
        assertEquals(response.error.map(_.code), Some(A2AErrorCode.MethodNotFound))
        assert(response.toJson.contains(""""id":null"""))
      case other => fail(s"expected single error dispatch, got $other")
    }

  test("JSON-RPC HTTP dispatch accepts and echoes arbitrary numeric request ids"):
    val card = A2AServerAgentCard(
      name = "JsonRpcDispatch",
      description = "Shared JSON-RPC dispatch",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = AgentCapabilities.default,
      skills = Nil,
      tenant = None,
    )
    val request = TestRequest(
      headers = Map(
        "content-type" -> A2AContentType.Json,
      ),
      query = Map(
        A2AHeader.Version -> A2AProtocol.Version,
      ),
      methodName = "POST",
      path = "/",
      body = """{"jsonrpc":"2.0","method":"unknown/method","id":1.5}""",
    )

    runUIO(A2AHttpBinding.jsonRpcDispatch(request, card, AgentCapabilities.default, null)).map {
      case A2AJsonRpcDispatch.Single(response, _) =>
        assertEquals(response.id, Some(JsonRpcId.RawNum(new java.math.BigDecimal("1.5"))))
        assertEquals(response.error.map(_.code), Some(A2AErrorCode.MethodNotFound))
        assert(response.toJson.contains(""""id":1.5"""))
      case other => fail(s"expected single error dispatch, got $other")
    }

  test("REST HTTP dispatch parses query service extension parameters"):
    val extension    = AgentExtension("urn:required", required = true)
    val capabilities = AgentCapabilities(extensions = List(extension))
    val card         = testCard(capabilities)
    val effect =
      for
        registry <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(capabilities = capabilities),
          runtime,
          registry,
          NoopPushPoster,
          () => card,
          (_, _) => ZIO.unit,
        )
        plan <- A2AHttpBinding.dispatchHttp(
          TestRequest(
            query = Map(
              A2AHeader.Version            -> A2AProtocol.Version,
              A2AHeader.StandardExtensions -> extension.uri,
            ),
            methodName = "GET",
            path = "/tasks",
          ),
          card,
          capabilities,
          core.requestHandler,
        )
      yield plan

    runUIO(effect).map {
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        val headerMap = headers.toMap
        assertEquals(status, 200)
        assertEquals(headerMap("Content-Type"), A2AContentType.A2AJson)
        assertEquals(headerMap(A2AHeader.StandardExtensions), extension.uri)
        assertEquals(body.fromJson[A2AResponse.ListTasksResult].toOption.map(_.tasks), Some(Nil))
      case other => fail(s"expected REST list response plan, got $other")
    }
