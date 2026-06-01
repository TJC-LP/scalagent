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
  def headerEntries: Iterable[(String, String)] = Iterable.empty
  def queryParam(name: String): Option[String]
  def queryParams: Iterable[(String, String)] = Iterable.empty
  def readBody: Task[String]

private[a2a] trait A2ALimitedHttpRequestView extends A2AHttpRequestView:
  def maxRequestBodyBytes: Int

  final def readBody: Task[String] =
    A2AHttpBinding.validateRequestContentLength(this, maxRequestBodyBytes) *>
      readBodyAfterContentLength(maxRequestBodyBytes)

  protected def readBodyAfterContentLength(maxBytes: Int): Task[String]

private[a2a] enum A2AHttpResponsePlan:
  case Text(
    body: String,
    status: Int,
    headers: List[(String, String)])
  case Empty(status: Int, headers: List[(String, String)])
  case Sse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
    headers: List[(String, String)],
    errorId: Option[JsonRpcId] = None)

private[a2a] object A2AHttpBinding:
  private val AgentCardCacheControl        = "public, max-age=60"
  private val AgentCardPrivateCacheControl = "private, max-age=60"
  private[a2a] val SseKeepAliveInterval    = 5.seconds

  private val SseHeaders = List(
    "Cache-Control"     -> "no-cache",
    "Connection"        -> "keep-alive",
    "X-Accel-Buffering" -> "no",
  )

  def mediaType(contentType: String): String =
    contentType.takeWhile(_ != ';').trim.toLowerCase(java.util.Locale.ROOT)

  private def headerValue(
    request: A2AHttpRequestView,
    name: String,
    aliases: String*
  ): Option[String] =
    headerValue(request.header, request.headerEntries, name, aliases*)

  private def headerValue(
    valueOf: String => Option[String],
    entries: Iterable[(String, String)],
    name: String,
    aliases: String*
  ): Option[String] =
    A2AJson
      .caseInsensitiveEntryLookup(entries, name, aliases*)
      .orElse(A2AJson.caseInsensitiveLookup(valueOf, name, aliases*))

  def requestContentType(request: A2AHttpRequestView): Option[String] =
    headerValue(request, "Content-Type")

  def validateRequestContentType(request: A2AHttpRequestView, expected: String): Task[Unit] =
    ZIO.fromEither(validateContentType(requestContentType(request), expected))

  def validateRequestContentLength(request: A2AHttpRequestView, maxBytes: Int): Task[Unit] =
    ZIO.fromEither(validateContentLengthHeader(headerValue(request, "Content-Length"), maxBytes))

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
      case Some(length) if length < 0 =>
        Left(invalidContentLength)
      case Some(length) if maxBytes > 0 && length > maxBytes =>
        Left(bodySizeExceeded(maxBytes))
      case _ =>
        Right(())

  def validateContentLengthHeader(value: Option[String], maxBytes: Int): Either[A2AError, Unit] =
    value match
      case Some(raw) =>
        raw.trim.toLongOption match
          case Some(length) => validateContentLength(Some(length), maxBytes)
          case None         => Left(invalidContentLength)
      case None =>
        Right(())

  def validateBodyLength(length: Long, maxBytes: Int): Either[A2AError, Unit] =
    if maxBytes > 0 && length > maxBytes then Left(bodySizeExceeded(maxBytes))
    else Right(())

  def bodySizeExceeded(maxBytes: Int): A2AError =
    A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit")

  private def invalidContentLength: A2AError =
    A2AError.invalidRequest("Content-Length must be a valid non-negative integer")

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
    errorId: Option[JsonRpcId] = None,
  ): A2AHttpResponsePlan =
    A2AHttpResponsePlan.Sse(
      stream,
      isJsonRpc,
      responseHeaders(Some(A2AContentType.Sse), extensions, SseHeaders*),
      errorId,
    )

  def agentCardPlan(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
    publiclyCacheable: Boolean = true,
  ): A2AHttpResponsePlan =
    val etag    = agentCardEtag(agentCard)
    val headers = agentCardHeaders(etag, publiclyCacheable)
    if ifNoneMatch(request).exists(matchesEtag(_, etag)) then A2AHttpResponsePlan.Empty(304, headers)
    else A2AHttpResponsePlan.Text(agentCard.toJson, 200, responseHeaders(Some(A2AContentType.Json), Nil, headers*))

  def agentCardEtag(agentCard: AgentCard): String =
    val hash = MurmurHash3.stringHash(agentCard.toJson).toHexString
    s""""a2a-card-$hash""""

  private def agentCardHeaders(etag: String, publiclyCacheable: Boolean): List[(String, String)] =
    List(
      // Auth-gated/tenant-scoped cards are per-caller — never let a shared cache
      // serve them to someone else within the TTL.
      "Cache-Control" -> (if publiclyCacheable then AgentCardCacheControl else AgentCardPrivateCacheControl),
      "ETag"          -> etag,
    )

  private def ifNoneMatch(request: A2AHttpRequestView): Option[String] =
    headerValue(request, "If-None-Match")

  private def matchesEtag(headerValue: String, etag: String): Boolean =
    headerValue.split(",").iterator.map(normalizeEtag).exists(value => value == "*" || value == normalizeEtag(etag))

  private def normalizeEtag(value: String): String =
    value.trim.stripPrefix("W/").trim

  def sseDataFrame(data: String): String =
    s"data: $data\n\n"

  def sseErrorFrame(errorJson: String): String =
    s"event: error\ndata: $errorJson\n\n"

  def sseKeepAliveFrame: String =
    ": keep-alive\n\n"

  def sseWireStream(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
    errorId: Option[JsonRpcId] = None,
    keepAliveInterval: Duration = SseKeepAliveInterval,
  ): ZStream[Any, Nothing, String] =
    val dataFrames = stream
      .map(sseDataFrame)
      .catchAll(error => ZStream.succeed(sseErrorFrame(streamErrorJson(error, isJsonRpc, errorId))))
    if keepAliveInterval <= Duration.Zero then dataFrames
    else
      dataFrames.mergeHaltLeft(
        ZStream.fromZIO(ZIO.sleep(keepAliveInterval)).drain ++
          ZStream.repeatWithSchedule(sseKeepAliveFrame, Schedule.spaced(keepAliveInterval))
      )

  def jsonRpcResponse(dispatch: A2AJsonRpcDispatch): A2AHttpResponsePlan =
    dispatch match
      case A2AJsonRpcDispatch.Single(response, extensions) =>
        jsonPlan(response, extensions = extensions)
      case A2AJsonRpcDispatch.Stream(id, events, extensions) =>
        ssePlan(
          events.map(event => JsonRpcResponse.success(id, event).toJson),
          isJsonRpc = true,
          extensions,
          errorId = id,
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
    executionMode: ExecutionMode = ExecutionMode.Default,
  ): UIO[A2AHttpResponsePlan] =
    if request.path == A2APaths.AgentCard && request.methodName == "GET" then
      requestHandler
        .getAgentCard(contextFrom(request, None))
        .map(card => agentCardPlan(request, card, requestHandler.agentCardPubliclyCacheable))
        .catchAll(error => ZIO.succeed(restErrorPlan(A2AError.fromThrowable(error))))
    else if request.path == "/" && request.methodName == "POST" then
      jsonRpcDispatch(request, agentCard, capabilities, requestHandler, executionMode).map(jsonRpcResponse)
    else
      restDispatch(request, agentCard, capabilities, requestHandler, executionMode) match
        case Some(effect) => effect.map(restResponse)
        case None         => ZIO.succeed(textPlan("Not Found", 404, "text/plain"))

  def contextFrom(request: A2AHttpRequestView, tenant: Option[String]): ServerCallContext =
    contextFromHeaders(request.header, tenant, request.headerEntries)

  def contextFromRequestParameters(request: A2AHttpRequestView, tenant: Option[String]): ServerCallContext =
    val base  = contextFrom(request, tenant)
    val query = A2APathRouting.query(request.queryParam, request.queryParams)
    contextWithRequestParameters(base, query)

  private def contextWithRequestParameters(
    base: ServerCallContext,
    query: A2APathRouting.Query,
  ): ServerCallContext =
    base.copy(
      requestedVersion = base.requestedVersion.orElse(A2APathRouting.requestedVersion(query)),
      requestedExtensions = (base.requestedExtensions ++ A2APathRouting.requestedExtensions(query)).distinct,
    )

  def contextFromHeaders(
    header: String => Option[String],
    tenant: Option[String],
    entries: Iterable[(String, String)] = Iterable.empty,
  ): ServerCallContext =
    ServerCallContext(
      tenant = tenant,
      requestedVersion = headerValue(header, entries, A2AHeader.Version),
      requestedExtensions = headerValue(header, entries, A2AHeader.StandardExtensions, A2AHeader.Extensions).toList
        .flatMap(parseExtensionsHeader),
      authorization = headerValue(header, entries, "Authorization"),
    )

  def parseExtensionsHeader(value: String): List[String] =
    // Strip CR/LF and other control chars defensively: these values are echoed
    // back in the `Standard-Extensions` response header, so unsanitized CR/LF
    // would be a response-splitting vector (belt-and-suspenders alongside the
    // HTTP adapter's own header validation).
    value
      .split(",")
      .iterator
      .map(_.filterNot(ch => ch.isControl).trim)
      .filter(_.nonEmpty)
      .toList

  def restDispatch(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
    executionMode: ExecutionMode = ExecutionMode.Default,
  ): Option[UIO[A2ARestDispatch]] =
    val routed      = A2APathRouting.route(request.methodName, request.path)
    val pathTenant  = routed.pathTenant
    val query       = A2APathRouting.query(request.queryParam, request.queryParams)
    val queryTenant = A2APathRouting.queryTenant(query)

    def contextFor(requestTenant: Option[String] = None): Task[ServerCallContext] =
      ZIO.fromEither(A2APathRouting.resolveTenant(pathTenant, queryTenant, requestTenant)).map { tenant =>
        val baseContext = contextFrom(request, tenant)
        contextWithRequestParameters(baseContext, query)
      }

    def restBodyString: Task[String] =
      validateRequestContentType(request, A2AContentType.A2AJson) *>
        request.readBody

    def restBodyAs[A: JsonDecoder]: Task[A] =
      restBodyString.flatMap { body => ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest)) }

    def restMessageSendBodyAs(mode: ExecutionMode): Task[A2ARequest.MessageSend] =
      restBodyString.flatMap { body =>
        val normalized = A2AMessageSendDefaults.normalizeMessageSendBodyForMode(body, mode)
        ZIO.fromEither(normalized.fromJson[A2ARequest.MessageSend].left.map(A2AError.invalidRequest))
      }

    def restOptionalBodyAs[A: JsonDecoder]: Task[Option[A]] =
      request.readBody.flatMap { body =>
        if body.trim.isEmpty then ZIO.none
        else
          validateRequestContentType(request, A2AContentType.A2AJson) *>
            ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest)).map(Some(_))
      }

    val bodyReader = new A2ARestBodyReader:
      def bodyAs[A: JsonDecoder]: Task[A]                                               = restBodyAs[A]
      def messageSendBodyAs(executionMode: ExecutionMode): Task[A2ARequest.MessageSend] =
        restMessageSendBodyAs(executionMode)
      def optionalBodyAs[A: JsonDecoder]: Task[Option[A]] = restOptionalBodyAs[A]

    A2ARestRouting.dispatch(
      routed,
      query,
      contextFor,
      bodyReader,
      agentCard,
      capabilities,
      executionMode,
      requestHandler,
    )
  end restDispatch

  def jsonRpcDispatch(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
    executionMode: ExecutionMode = ExecutionMode.Default,
  ): UIO[A2AJsonRpcDispatch] =
    (validateRequestContentType(request, A2AContentType.Json) *> request.readBody)
      .foldZIO(
        error => ZIO.succeed(jsonRpcErrorDispatch(JsonRpcId.Unknown, A2AError.fromThrowable(error))),
        body =>
          jsonRpcDispatch(
            body,
            contextFromRequestParameters(request, None),
            agentCard,
            capabilities,
            requestHandler,
            executionMode,
          ),
      )

  def jsonRpcDispatch(
    body: String,
    context: ServerCallContext,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
    executionMode: ExecutionMode,
  ): UIO[A2AJsonRpcDispatch] =
    val normalizedBody = A2AMessageSendDefaults.normalizeJsonRpcBodyForMode(body, executionMode)
    JsonRpcRequest.parse(normalizedBody) match
      case Left(error)   => ZIO.succeed(jsonRpcErrorDispatch(JsonRpcId.Unknown, error))
      case Right(parsed) =>
        A2AJsonRpcRouting.dispatch(parsed, context, agentCard, capabilities, requestHandler)

  def restErrorBody(error: A2AError): Json =
    val status     = A2AError.httpStatus(error)
    val statusName = A2AError.grpcStatus(error).wireName
    val reason     = A2AError.errorInfoReason(error.code).getOrElse("INVALID_PARAMS")
    Json.Obj(
      "type"   -> Json.Str(problemType(reason)),
      "title"  -> Json.Str(problemTitle(statusName)),
      "status" -> Json.Num(java.math.BigDecimal.valueOf(status.toLong)),
      "detail" -> Json.Str(error.message),
      "error"  -> Json.Obj(
        "code"    -> Json.Num(java.math.BigDecimal.valueOf(status.toLong)),
        "status"  -> Json.Str(statusName),
        "message" -> Json.Str(error.message),
        "details" -> Json.Arr(
          Json.Obj(
            "@type"  -> Json.Str(A2AError.ErrorInfoType),
            "reason" -> Json.Str(reason),
            "domain" -> Json.Str(A2AError.ErrorInfoDomain),
          )
        ),
      ),
    )
  end restErrorBody

  private def problemType(reason: String): String =
    s"https://${A2AError.ErrorInfoDomain}/errors/${reason.toLowerCase.replace('_', '-')}"

  private def problemTitle(statusName: String): String =
    statusName.split("_").iterator.map(_.toLowerCase.capitalize).mkString(" ")

  def streamErrorJson(
    error: A2AError,
    isJsonRpc: Boolean,
    id: Option[JsonRpcId] = None,
  ): String =
    if isJsonRpc then JsonRpcResponse.fromA2AError(id, error).toJson
    else restErrorBody(error).toJson

  def streamErrorJson(error: Throwable, isJsonRpc: Boolean): String =
    streamErrorJson(A2AError.fromThrowable(error), isJsonRpc, None)

  def streamErrorJson(
    error: Throwable,
    isJsonRpc: Boolean,
    id: Option[JsonRpcId],
  ): String =
    streamErrorJson(A2AError.fromThrowable(error), isJsonRpc, id)

  private def jsonRpcErrorDispatch(id: Option[JsonRpcId], error: A2AError): A2AJsonRpcDispatch =
    A2AJsonRpcDispatch.Single(JsonRpcResponse.fromA2AError(id, error), Nil)
end A2AHttpBinding
