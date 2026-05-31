package com.tjclp.scalagent.a2a

import scala.util.hashing.MurmurHash3

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

private[a2a] trait A2AHttpRequestView:
  def methodName: String
  def path: String
  def header(name: String): Option[String]
  def queryParam(name: String): Option[String]
  def readBody: Task[String]

private[a2a] enum A2AHttpResponsePlan:
  case Text(
    body: String,
    status: Int,
    headers: List[(String, String)])
  case Empty(status: Int, headers: List[(String, String)])
  case Sse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
    headers: List[(String, String)])

private[a2a] object A2AHttpBinding:
  private val AgentCardCacheControl = "public, max-age=60"

  private val SseHeaders = List(
    "Cache-Control"     -> "no-cache",
    "Connection"        -> "keep-alive",
    "X-Accel-Buffering" -> "no",
  )

  def mediaType(contentType: String): String =
    contentType.takeWhile(_ != ';').trim.toLowerCase

  def requestContentType(request: A2AHttpRequestView): Option[String] =
    request.header("content-type").orElse(request.header("Content-Type"))

  def validateRequestContentType(request: A2AHttpRequestView, expected: String): Task[Unit] =
    ZIO.fromEither(validateContentType(requestContentType(request), expected))

  def validateContentType(actual: Option[String], expected: String): Either[A2AError, Unit] =
    actual match
      case Some(value) if mediaType(value) == expected =>
        Right(())
      case Some(value) =>
        Left(A2AError.contentTypeNotSupported(value))
      case None =>
        Left(A2AError.contentTypeNotSupported("<missing>"))

  def validateContentLength(contentLength: Option[Long], maxBytes: Int): Either[A2AError, Unit] =
    contentLength match
      case Some(length) if maxBytes > 0 && length > maxBytes =>
        Left(bodySizeExceeded(maxBytes))
      case _ =>
        Right(())

  def validateBodyLength(length: Long, maxBytes: Int): Either[A2AError, Unit] =
    if maxBytes > 0 && length > maxBytes then Left(bodySizeExceeded(maxBytes))
    else Right(())

  def bodySizeExceeded(maxBytes: Int): A2AError =
    A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit")

  def extensionsHeader(extensions: List[String]): Option[String] =
    Option.when(extensions.nonEmpty)(extensions.mkString(","))

  def responseHeaders(
    contentType: Option[String],
    extensions: List[String],
    extra: (String, String)*
  ): List[(String, String)] =
    contentType.map("Content-Type" -> _).toList ++
      extra.toList ++
      extensionsHeader(extensions).map(A2AHeader.StandardExtensions -> _).toList

  def textPlan(
    body: String,
    status: Int,
    contentType: String,
    extensions: List[String] = Nil,
    extra: (String, String)*
  ): A2AHttpResponsePlan =
    A2AHttpResponsePlan.Text(body, status, responseHeaders(Some(contentType), extensions, extra*))

  def emptyPlan(status: Int, extensions: List[String] = Nil): A2AHttpResponsePlan =
    A2AHttpResponsePlan.Empty(status, responseHeaders(None, extensions))

  def jsonPlan[A: JsonEncoder](
    value: A,
    status: Int = 200,
    contentType: String = A2AContentType.Json,
    extensions: List[String] = Nil,
  ): A2AHttpResponsePlan =
    textPlan(value.toJson, status, contentType, extensions)

  def ssePlan(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
    extensions: List[String],
  ): A2AHttpResponsePlan =
    A2AHttpResponsePlan.Sse(
      stream,
      isJsonRpc,
      responseHeaders(Some(A2AContentType.Sse), extensions, SseHeaders*),
    )

  def agentCardPlan(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
  ): A2AHttpResponsePlan =
    val etag    = agentCardEtag(agentCard)
    val headers = agentCardHeaders(etag)
    if ifNoneMatch(request).exists(matchesEtag(_, etag)) then A2AHttpResponsePlan.Empty(304, headers)
    else A2AHttpResponsePlan.Text(agentCard.toJson, 200, responseHeaders(Some(A2AContentType.Json), Nil, headers*))

  def agentCardEtag(agentCard: AgentCard): String =
    val hash = MurmurHash3.stringHash(agentCard.toJson).toHexString
    s""""a2a-card-$hash""""

  private def agentCardHeaders(etag: String): List[(String, String)] =
    List(
      "Cache-Control" -> AgentCardCacheControl,
      "ETag"          -> etag,
    )

  private def ifNoneMatch(request: A2AHttpRequestView): Option[String] =
    request.header("If-None-Match").orElse(request.header("if-none-match"))

  private def matchesEtag(headerValue: String, etag: String): Boolean =
    headerValue.split(",").iterator.map(normalizeEtag).exists(value => value == "*" || value == normalizeEtag(etag))

  private def normalizeEtag(value: String): String =
    value.trim.stripPrefix("W/").trim

  def sseDataFrame(data: String): String =
    s"data: $data\n\n"

  def sseErrorFrame(errorJson: String): String =
    s"event: error\ndata: $errorJson\n\n"

  def sseWireStream(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
  ): ZStream[Any, Nothing, String] =
    stream
      .map(sseDataFrame)
      .catchAll(error => ZStream.succeed(sseErrorFrame(streamErrorJson(error, isJsonRpc))))

  def jsonRpcResponse(dispatch: A2AJsonRpcDispatch): A2AHttpResponsePlan =
    dispatch match
      case A2AJsonRpcDispatch.Single(response, extensions) =>
        jsonPlan(response, extensions = extensions)
      case A2AJsonRpcDispatch.Stream(id, events, extensions) =>
        ssePlan(
          events.map(event => JsonRpcResponse.success(id, event).toJson),
          isJsonRpc = true,
          extensions,
        )

  def restResponse(dispatch: A2ARestDispatch): A2AHttpResponsePlan =
    dispatch match
      case A2ARestDispatch.Json(body, status, extensions) =>
        textPlan(body, status, A2AContentType.A2AJson, extensions)
      case A2ARestDispatch.Stream(body, extensions) =>
        ssePlan(body, isJsonRpc = false, extensions)
      case A2ARestDispatch.Empty(status, extensions) =>
        emptyPlan(status, extensions)
      case A2ARestDispatch.Error(error, extensions) =>
        restErrorPlan(error, extensions)

  def restErrorPlan(error: A2AError, extensions: List[String] = Nil): A2AHttpResponsePlan =
    textPlan(restErrorBody(error).toJson, A2AError.httpStatus(error), A2AContentType.A2AJson, extensions)

  def dispatchHttp(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): UIO[A2AHttpResponsePlan] =
    if request.path == A2APaths.AgentCard && request.methodName == "GET" then
      ZIO.succeed(agentCardPlan(request, agentCard))
    else if request.path == "/" && request.methodName == "POST" then
      jsonRpcDispatch(request, agentCard, capabilities, requestHandler).map(jsonRpcResponse)
    else
      restDispatch(request, agentCard, capabilities, requestHandler) match
        case Some(effect) => effect.map(restResponse)
        case None         => ZIO.succeed(textPlan("Not Found", 404, "text/plain"))

  def contextFrom(request: A2AHttpRequestView, tenant: Option[String]): ServerCallContext =
    contextFromHeaders(request.header, tenant)

  def contextFromHeaders(header: String => Option[String], tenant: Option[String]): ServerCallContext =
    ServerCallContext(
      tenant = tenant,
      requestedVersion = header(A2AHeader.Version),
      requestedExtensions = header(A2AHeader.StandardExtensions)
        .orElse(header(A2AHeader.Extensions))
        .toList
        .flatMap(parseExtensionsHeader),
    )

  def parseExtensionsHeader(value: String): List[String] =
    value.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList

  def restDispatch(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): Option[UIO[A2ARestDispatch]] =
    val routed      = A2APathRouting.route(request.methodName, request.path)
    val pathTenant  = routed.pathTenant
    val query       = A2APathRouting.query(request.queryParam)
    val queryTenant = A2APathRouting.queryTenant(query)

    def contextFor(requestTenant: Option[String] = None): Task[ServerCallContext] =
      ZIO.fromEither(A2APathRouting.resolveTenant(pathTenant, queryTenant, requestTenant)).map { tenant =>
        val baseContext = contextFrom(request, tenant)
        baseContext.copy(
          requestedVersion = baseContext.requestedVersion.orElse(A2APathRouting.requestedVersion(query))
        )
      }

    def restBodyAs[A: JsonDecoder]: Task[A] =
      validateRequestContentType(request, A2AContentType.A2AJson) *>
        request.readBody.flatMap { body => ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest)) }

    def restOptionalBodyAs[A: JsonDecoder]: Task[Option[A]] =
      request.readBody.flatMap { body =>
        if body.trim.isEmpty then ZIO.none
        else
          validateRequestContentType(request, A2AContentType.A2AJson) *>
            ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest)).map(Some(_))
      }

    val bodyReader = new A2ARestBodyReader:
      def bodyAs[A: JsonDecoder]: Task[A]                 = restBodyAs[A]
      def optionalBodyAs[A: JsonDecoder]: Task[Option[A]] = restOptionalBodyAs[A]

    A2ARestRouting.dispatch(routed, query, contextFor, bodyReader, agentCard, capabilities, requestHandler)
  end restDispatch

  def jsonRpcDispatch(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): UIO[A2AJsonRpcDispatch] =
    (validateRequestContentType(request, A2AContentType.Json) *> request.readBody)
      .foldZIO(
        error => ZIO.succeed(jsonRpcErrorDispatch(None, A2AError.fromThrowable(error))),
        body => jsonRpcDispatch(body, contextFrom(request, None), agentCard, capabilities, requestHandler),
      )

  def jsonRpcDispatch(
    body: String,
    context: ServerCallContext,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): UIO[A2AJsonRpcDispatch] =
    JsonRpcRequest.parse(body) match
      case Left(error)   => ZIO.succeed(jsonRpcErrorDispatch(None, error))
      case Right(parsed) =>
        A2AJsonRpcRouting.dispatch(parsed, context, agentCard, capabilities, requestHandler)

  def restErrorBody(error: A2AError): Json =
    val status = A2AError.httpStatus(error)
    Json.Obj(
      "error" -> Json.Obj(
        "code"    -> Json.Num(java.math.BigDecimal.valueOf(status.toLong)),
        "status"  -> Json.Str(A2AError.httpStatusName(status)),
        "message" -> Json.Str(error.message),
        "details" -> Json.Arr(
          Json.Obj(
            "@type"  -> Json.Str(A2AError.ErrorInfoType),
            "reason" -> Json.Str(A2AError.errorInfoReason(error.code).getOrElse("INVALID_PARAMS")),
            "domain" -> Json.Str(A2AError.ErrorInfoDomain),
          )
        ),
      )
    )

  def streamErrorJson(error: A2AError, isJsonRpc: Boolean): String =
    if isJsonRpc then JsonRpcResponse.fromA2AError(None, error).toJson
    else restErrorBody(error).toJson

  def streamErrorJson(error: Throwable, isJsonRpc: Boolean): String =
    streamErrorJson(A2AError.fromThrowable(error), isJsonRpc)

  private def jsonRpcErrorDispatch(id: Option[JsonRpcId], error: A2AError): A2AJsonRpcDispatch =
    A2AJsonRpcDispatch.Single(JsonRpcResponse.fromA2AError(id, error), Nil)
end A2AHttpBinding
