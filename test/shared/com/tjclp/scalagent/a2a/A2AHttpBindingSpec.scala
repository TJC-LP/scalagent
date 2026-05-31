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

  private final case class TestRequest(
    headers: Map[String, String] = Map.empty,
    query: Map[String, String] = Map.empty,
    body: String = "",
    methodName: String = "GET",
    path: String = "/tasks/task-1")
      extends A2AHttpRequestView:
    def header(name: String): Option[String]     = headers.get(name)
    def queryParam(name: String): Option[String] = query.get(name)
    def readBody: Task[String]                   = ZIO.succeed(body)

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
      A2AHttpBinding.validateContentType(Some("application/json"), A2AContentType.A2AJson).left.map(_.code),
      Left(A2AErrorCode.ContentTypeNotSupported),
    )
    assertEquals(
      A2AHttpBinding.validateContentType(None, A2AContentType.Json).left.map(_.message),
      Left("Content type not supported: <missing>"),
    )

  test("request context extraction is shared across HTTP bindings"):
    val context = A2AHttpBinding.contextFrom(
      TestRequest(
        headers = Map(
          A2AHeader.Version            -> "1.0",
          A2AHeader.StandardExtensions -> "urn:a, urn:b,,",
        )
      ),
      Some("tenant-a"),
    )

    assertEquals(context.tenant, Some("tenant-a"))
    assertEquals(context.requestedVersion, Some("1.0"))
    assertEquals(context.requestedExtensions, List("urn:a", "urn:b"))

  test("body size limit checks use the same error across HTTP bindings"):
    assertEquals(A2AHttpBinding.validateContentLength(Some(10L), maxBytes = 10), Right(()))
    assertEquals(
      A2AHttpBinding.validateContentLength(Some(11L), maxBytes = 10).left.map(_.message),
      Left("Invalid request: Request body exceeds 10 byte limit"),
    )
    assertEquals(A2AHttpBinding.validateBodyLength(11L, maxBytes = 0), Right(()))

  test("REST error body follows google.rpc Status JSON representation"):
    val body   = A2AHttpBinding.restErrorBody(A2AError.taskNotFound(TaskId("task-1")))
    val fields = body.asObject.get.toMap("error").asObject.get.toMap
    val detail = fields("details").asArray.get.head.asObject.get.toMap

    assertEquals(fields("code"), Json.Num(java.math.BigDecimal.valueOf(404L)))
    assertEquals(fields("status"), Json.Str("NOT_FOUND"))
    assertEquals(detail("@type"), Json.Str(A2AError.ErrorInfoType))
    assertEquals(detail("reason"), Json.Str("TASK_NOT_FOUND"))
    assertEquals(detail("domain"), Json.Str(A2AError.ErrorInfoDomain))

  test("stream error serialization is shared for REST and JSON-RPC SSE"):
    val error   = A2AError.unsupportedOperation("Streaming not supported")
    val rest    = A2AHttpBinding.streamErrorJson(error, isJsonRpc = false)
    val jsonRpc = A2AHttpBinding.streamErrorJson(error, isJsonRpc = true)

    assert(rest.fromJson[Json].exists(_.asObject.exists(_.toMap.contains("error"))))
    assert(jsonRpc.fromJson[JsonRpcResponse].exists(_.error.exists(_.code == A2AErrorCode.UnsupportedOperation)))

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

    for
      agentCardPlan <- runUIO(A2AHttpBinding.dispatchHttp(agentCardRequest, card, capabilities, null))
      jsonRpcPlan   <- runUIO(A2AHttpBinding.dispatchHttp(jsonRpcRequest, card, capabilities, null))
      restPlan      <- runUIO(A2AHttpBinding.dispatchHttp(restRequest, card, capabilities, null))
      missingPlan   <- runUIO(A2AHttpBinding.dispatchHttp(missingRequest, card, capabilities, null))
    yield
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
      case A2AHttpResponsePlan.Sse(stream, isJsonRpc, headers) =>
        val headerMap = headers.toMap
        assert(isJsonRpc)
        assertEquals(headerMap("Content-Type"), A2AContentType.Sse)
        assertEquals(headerMap("Cache-Control"), "no-cache")
        runTask(stream.runCollect.map(_.toList)).map { frames =>
          val response = frames.head.fromJson[JsonRpcResponse].toOption.get
          assertEquals(response.id, id)
          assert(response.result.nonEmpty)
        }
      case other => fail(s"expected SSE response plan, got $other")

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
        assertEquals(extensions, Nil)
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
        A2AHeader.Version            -> A2AProtocol.Version,
        "content-type"               -> A2AContentType.Json,
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
