package com.tjclp.scalagent.a2a

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

import scala.collection.mutable

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/**
 * JVM-side server configuration. Mirror of the JS `A2AServerLive.Config`
 * minus Claude-Agent-SDK-specific fields (`agentOptions`,
 * `invocationPreparer`) since the JVM scalagent build doesn't include the
 * Claude Agent SDK adapters.
 *
 * For CMA-backed agents, the common fields are `name`, `description`, `port`,
 * `executionOverride`, `skills`, and optionally the task/event stores.
 */
object A2AServerLive:

  final case class Config(
    name: String,
    description: String,
    host: String = "0.0.0.0",
    port: Int = 3000,
    executionMode: ExecutionMode = ExecutionMode.Default,
    taskTimeout: Option[Duration] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default,
    skills: List[AgentSkill] = Nil,
    executionOverride: Option[(A2AMessage, TaskId, ContextId, A2AEventPublisher) => Task[Unit]] = None,
    pushNotificationStore: Option[A2APushNotificationStore] = None,
    taskStore: Option[A2ATaskStore] = None,
    eventStore: Option[A2AEventStore] = None,
    replayProvider: Option[A2AReplayProvider] = None,
    eventReplayLimit: Int = 1000,
    eventStoreAppendTimeout: Duration = 2.seconds,
    eventStoreLoadTimeout: Duration = 5.seconds,
    maxRequestBodyBytes: Int = 1024 * 1024,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy = PushNotificationUrlPolicy.externalOnly):
    def url: String = s"http://$host:$port"

    def toAgentCard: AgentCard = toAgentCardAt(url)

    def toAgentCardAt(baseUrl: String): AgentCard =
      AgentCard(
        name = name,
        description = description,
        supportedInterfaces = List(
          AgentInterface.jsonRpc(baseUrl),
          AgentInterface.rest(baseUrl),
        ),
        capabilities = capabilities,
        skills = skills,
      )
  end Config

  /** Create and start a JVM A2A server. */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    for
      runtime <- ZIO.runtime[Any]
      server  <- ZIO.acquireRelease(start(config, runtime))(_.stop.ignore)
    yield server

  /** Start a JVM A2A server without scope management. */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    for
      server <- ZIO.attempt(A2AServerLiveImpl(config, runtime))
      _      <- server.start
    yield server

  /** Create a server layer. */
  def live(config: Config): ZLayer[Scope, Throwable, A2AServer] =
    ZLayer.fromZIO(create(config))
end A2AServerLive

private final class PushNotificationSender(
  store: A2APushNotificationStore,
  urlPolicy: PushNotificationUrlPolicy)
    extends A2APushNotificationSender:
  private val client         = HttpClient.newHttpClient()
  private val deliveryChains = mutable.Map.empty[(String, String), Promise[Nothing, Unit]]
  private val lock           = new AnyRef

  def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit] =
    val key = (context.tenant.getOrElse(""), event.taskId.value)
    (for
      previous <- ZIO.succeed(lock.synchronized(deliveryChains.get(key)))
      current  <- Promise.make[Nothing, Unit]
      _        <- ZIO.succeed(lock.synchronized(deliveryChains.update(key, current)))
      _        <-
        (previous.fold(ZIO.unit)(_.await) *> sendNow(event, context))
          .catchAll(error => ZIO.logWarning(s"Failed to send A2A push notification: ${error.getMessage}"))
          .ensuring(
            current.succeed(()).unit *>
              ZIO.succeed {
                lock.synchronized {
                  if deliveryChains.get(key).contains(current) then deliveryChains.remove(key)
                }
              }.unit
          )
          .forkDaemon
    yield ()).unit

  private def sendNow(event: A2AResponse.StreamEvent, context: ServerCallContext): Task[Unit] =
    store
      .load(event.taskId, context.tenant)
      .flatMap(configs => ZIO.foreachDiscard(configs)(sendOne(event, _)))

  private def sendOne(event: A2AResponse.StreamEvent, config: TaskPushNotificationConfig): Task[Unit] =
    urlPolicy.validate(config.url) *>
      ZIO.attemptBlocking {
        var builder = HttpRequest
          .newBuilder(URI.create(config.url))
          .POST(HttpRequest.BodyPublishers.ofString(event.toJson, StandardCharsets.UTF_8))
          .header("Content-Type", A2AContentType.A2AJson)
        config.authentication match
          case Some(auth) if auth.scheme.nonEmpty && auth.credentials.nonEmpty =>
            builder = builder.header("Authorization", s"${auth.scheme} ${auth.credentials}")
          case _ =>
            config.token.foreach(token => builder = builder.header("X-A2A-Notification-Token", token))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
        if response.statusCode() < 200 || response.statusCode() >= 300 then
          throw RuntimeException(s"Push callback ${config.url} returned HTTP ${response.statusCode()}")
      }
end PushNotificationSender

/** Live implementation of A2A Server using zio-http. */
private[a2a] final class A2AServerLiveImpl(
  config: A2AServerLive.Config,
  runtime: Runtime[Any])
    extends A2AServer:

  private val taskStore = config.taskStore.getOrElse(A2ATaskStore.inMemory)
  private val pushStore = config.pushNotificationStore.getOrElse(A2APushNotificationStore.inMemory)
  private val runtimeRegistry: A2ARuntimeRegistry =
    Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(A2ARuntimeRegistry.make).getOrThrow() }
  private val pushSender    = PushNotificationSender(pushStore, config.pushNotificationUrlPolicy)
  private val requestConfig = A2ARequestHandler.Config(
    capabilities = config.capabilities,
    eventStore = config.eventStore,
    replayProvider = config.replayProvider,
    eventReplayLimit = config.eventReplayLimit,
    eventStoreAppendTimeout = config.eventStoreAppendTimeout,
    eventStoreLoadTimeout = config.eventStoreLoadTimeout,
    pushNotificationUrlPolicy = config.pushNotificationUrlPolicy,
  )
  private val requestHandler =
    A2ARequestHandler(
      requestConfig,
      runtime,
      taskStore,
      pushStore,
      runtimeRegistry,
      pushSender,
      () => agentCard,
      execute,
    )

  private val serverFiberRef: Ref.Synchronized[Option[Fiber.Runtime[Throwable, Unit]]] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(Ref.Synchronized.make(Option.empty[Fiber.Runtime[Throwable, Unit]])).getOrThrow()
    }

  def agentCard: AgentCard = config.toAgentCardAt(url)

  def url: String = config.url

  def start: Task[Unit] =
    val server = Server
      .serve(a2aRoutes)
      .provide(
        ZLayer.succeed(Server.Config.default.binding(config.host, config.port)),
        Server.live,
      )
    for
      fiber <- server.fork
      _     <- serverFiberRef.set(Some(fiber))
    yield ()

  def stop: Task[Unit] =
    runtimeRegistry.interruptAll *>
      serverFiberRef.modifyZIO {
        case Some(fiber) => fiber.interruptFork.as(((), None))
        case None        => ZIO.succeed(((), None))
      }

  private def execute(prepared: A2ARequestHandler.PreparedRun, publisher: A2AEventPublisher): Task[Unit] =
    config.executionOverride match
      case Some(overrideRun) =>
        withTaskTimeout(
          prepared.task.id,
          overrideRun(prepared.message, prepared.task.id, prepared.task.contextId, publisher),
        )
      case None =>
        ZIO.fail(
          A2AError.invalidRequest("This JVM A2AServerLive requires `executionOverride` to be configured")
        )

  private def withTaskTimeout[A](taskId: TaskId, effect: Task[A]): Task[A] =
    config.taskTimeout match
      case Some(timeout) =>
        effect.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} timed out after $timeout"))(timeout)
      case None =>
        effect

  private def a2aRoutes: Routes[Any, Nothing] =
    Routes.singleton(handler { (_: Path, request: Request) => handleHttp(request) })

  private[a2a] def handleHttp(request: Request): UIO[Response] =
    val pathname = request.path.encode
    if pathname == A2APaths.AgentCard && request.method == Method.GET then ZIO.succeed(jsonResponse(agentCard))
    else if pathname == "/" && request.method == Method.POST then
      readBody(request)
        .foldZIO(
          error => ZIO.succeed(jsonRpcErrorResponse(None, toA2AError(error))),
          body => handleJsonRpc(body, contextFrom(request, None)),
        )
    else
      routeRest(request) match
        case Some(effect) => effect
        case None         => ZIO.succeed(textResponse("Not Found", 404, "text/plain"))

  private[a2a] def dispatchJsonRpc(request: JsonRpcRequest): Task[JsonRpcResponse] =
    dispatchJsonRpcSingle(request, ServerCallContext())

  private def handleJsonRpc(body: String, context: ServerCallContext): UIO[Response] =
    body.fromJson[JsonRpcRequest] match
      case Left(msg) =>
        ZIO.succeed(jsonRpcErrorResponse(None, A2AError.parseError(msg)))
      case Right(request) =>
        val isStreaming = request.method == A2AMethod.MessageStream || request.method == A2AMethod.TasksResubscribe
        val routed      =
          if isStreaming then handleJsonRpcStream(request, context)
          else dispatchJsonRpcSingle(request, context).map(response => jsonResponse(response))
        (validateServiceParameters(context, A2ATransport.JSONRPC) *> routed)
          .catchAll(error => ZIO.succeed(jsonRpcErrorResponse(request.id, toA2AError(error))))

  private def dispatchJsonRpcSingle(request: JsonRpcRequest, context: ServerCallContext): Task[JsonRpcResponse] =
    request.method match
      case A2AMethod.MessageSend =>
        paramsAs[A2ARequest.MessageSend](request)
          .flatMap(requestHandler.sendMessage(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksGet =>
        paramsAs[A2ARequest.TasksGet](request)
          .flatMap(requestHandler.getTask(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksList =>
        paramsAs[A2ARequest.TasksList](request)
          .flatMap(requestHandler.listTasks(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksCancel =>
        paramsAs[A2ARequest.TasksCancel](request)
          .flatMap(requestHandler.cancelTask(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigSet =>
        paramsAs[TaskPushNotificationConfig](request)
          .flatMap(requestHandler.createPushConfig(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigGet =>
        paramsAs[A2ARequest.PushNotificationConfigGet](request)
          .flatMap(requestHandler.getPushConfig(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigList =>
        paramsAs[A2ARequest.PushNotificationConfigList](request)
          .flatMap(requestHandler.listPushConfigs(_, withTenantFromParams(context, request.params)))
          .map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigDelete =>
        paramsAs[A2ARequest.PushNotificationConfigDelete](request)
          .flatMap(requestHandler.deletePushConfig(_, withTenantFromParams(context, request.params)))
          .as(JsonRpcResponse.success(request.id, Json.Obj()))
      case A2AMethod.GetAuthenticatedExtendedCard =>
        requestHandler.getExtendedAgentCard(context).map(JsonRpcResponse.success(request.id, _))
      case other =>
        ZIO.fail(A2AError.methodNotFound(other))

  private def handleJsonRpcStream(request: JsonRpcRequest, context: ServerCallContext): Task[Response] =
    val streamTask =
      request.method match
        case A2AMethod.MessageStream =>
          paramsAs[A2ARequest.MessageSend](request)
            .flatMap(requestHandler.sendMessageStream(_, withTenantFromParams(context, request.params)))
        case A2AMethod.TasksResubscribe =>
          paramsAs[A2ARequest.TasksResubscribe](request)
            .flatMap(requestHandler.resubscribe(_, withTenantFromParams(context, request.params)))
        case other =>
          ZIO.fail(A2AError.methodNotFound(other))
    streamTask.map { stream =>
      sseResponse(
        stream.map(event => JsonRpcResponse.success(request.id, event).toJson),
        isJsonRpc = true,
      )
    }

  private def paramsAs[A: JsonDecoder](request: JsonRpcRequest): Task[A] =
    ZIO.fromEither(
      request.params.toRight(A2AError.invalidParams("Missing params")).flatMap(_.as[A].left.map(A2AError.invalidParams))
    )

  private def withTenantFromParams(context: ServerCallContext, params: Option[Json]): ServerCallContext =
    params
      .flatMap(_.asObject)
      .flatMap(_.toMap.get("tenant"))
      .flatMap(_.asString)
      .filter(_.nonEmpty)
      .fold(context)(tenant => context.copy(tenant = Some(tenant)))

  private def routeRest(request: Request): Option[UIO[Response]] =
    val (tenant, path) = splitTenant(request.path.encode)
    val query          = request.url.queryParams

    def queryString(name: String): Option[String] =
      query.getAll(name).headOption

    def queryInt(name: String): Task[Option[Int]] =
      queryString(name) match
        case Some(value) =>
          value.toIntOption match
            case Some(parsed) => ZIO.succeed(Some(parsed))
            case None         => ZIO.fail(A2AError.invalidParams(s"$name must be a valid integer"))
        case None =>
          ZIO.succeed(None)

    def queryBool(name: String): Task[Option[Boolean]] =
      queryString(name) match
        case Some(value) =>
          value.toBooleanOption match
            case Some(parsed) => ZIO.succeed(Some(parsed))
            case None         => ZIO.fail(A2AError.invalidParams(s"$name must be a valid boolean"))
        case None =>
          ZIO.succeed(None)

    def queryStatus(name: String): Task[Option[TaskState]] =
      queryString(name) match
        case Some(value) =>
          ZIO
            .fromEither(
              Json.Str(value).as[TaskState].left.map(error => A2AError.invalidParams(s"Invalid $name: $error"))
            )
            .map(Some(_))
        case None =>
          ZIO.succeed(None)

    def rawSegments(value: String): List[String] =
      val stripped = value.stripPrefix("/")
      if stripped.isEmpty then Nil else stripped.split("/", -1).toList

    def nonEmptyTaskId(raw: String): Task[TaskId] =
      if raw.isEmpty then ZIO.fail(A2AError.invalidParams("Missing task ID"))
      else ZIO.succeed(TaskId(raw))

    def nonEmptyConfigId(raw: String): Task[String] =
      if raw.isEmpty then ZIO.fail(A2AError.invalidParams("Missing push notification config ID"))
      else ZIO.succeed(raw)

    val baseContext = contextFrom(request, tenant)
    val context     = baseContext.copy(
      requestedVersion = baseContext.requestedVersion
        .orElse(queryString(A2AHeader.Version))
        .orElse(queryString("a2aVersion"))
    )

    def bodyAs[A: JsonDecoder]: Task[A] =
      readBody(request).flatMap { body => ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest)) }

    def json[A: JsonEncoder](effect: Task[A], status: Int = 200): UIO[Response] =
      (validateServiceParameters(context, A2ATransport.HTTP_JSON) *> effect)
        .map(value => jsonResponse(value, status, A2AContentType.A2AJson))
        .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))

    val segments = rawSegments(path)

    (request.method, segments) match
      case (Method.POST, List("message:send")) =>
        Some(json(bodyAs[A2ARequest.MessageSend].flatMap(requestHandler.sendMessage(_, context))))
      case (Method.POST, List("message:stream")) =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            bodyAs[A2ARequest.MessageSend]
              .flatMap(requestHandler.sendMessageStream(_, context))
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case (Method.GET, List("tasks")) =>
        val params =
          for
            status           <- queryStatus("status")
            pageSize         <- queryInt("pageSize")
            historyLength    <- queryInt("historyLength")
            includeArtifacts <- queryBool("includeArtifacts")
          yield A2ARequest.TasksList(
            contextId = queryString("contextId").filter(_.nonEmpty).map(ContextId(_)),
            status = status,
            pageSize = pageSize,
            pageToken = queryString("pageToken"),
            historyLength = historyLength,
            statusTimestampAfter = queryString("statusTimestampAfter"),
            includeArtifacts = includeArtifacts,
            tenant = tenant,
          )
        Some(json(params.flatMap(requestHandler.listTasks(_, context))))
      case (Method.GET, List("tasks", rawTaskId)) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap { taskId =>
              queryInt("historyLength").flatMap(historyLength =>
                requestHandler.getTask(A2ARequest.TasksGet(taskId, historyLength, tenant), context)
              )
            }
          )
        )
      case (Method.POST, List("tasks", rawTaskAction)) if rawTaskAction.endsWith(":cancel") =>
        Some(
          json(
            nonEmptyTaskId(rawTaskAction.stripSuffix(":cancel")).flatMap(taskId =>
              requestHandler.cancelTask(A2ARequest.TasksCancel(taskId, tenant = tenant), context)
            ),
            status = 202,
          )
        )
      case (verb, List("tasks", rawTaskAction))
          if (verb == Method.GET || verb == Method.POST) && rawTaskAction.endsWith(":subscribe") =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            nonEmptyTaskId(rawTaskAction.stripSuffix(":subscribe"))
              .flatMap(taskId => requestHandler.resubscribe(A2ARequest.TasksResubscribe(taskId, tenant), context))
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case (Method.POST, List("tasks", rawTaskId, "pushNotificationConfigs")) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap(taskId =>
              bodyAs[TaskPushNotificationConfig]
                .map(_.copy(taskId = Some(taskId), tenant = tenant))
                .flatMap(requestHandler.createPushConfig(_, context))
            ),
            status = 201,
          )
        )
      case (Method.GET, List("tasks", rawTaskId, "pushNotificationConfigs")) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap(taskId =>
              requestHandler.listPushConfigs(A2ARequest.PushNotificationConfigList(taskId, tenant = tenant), context)
            )
          )
        )
      case (Method.GET, List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
        Some(
          json(
            for
              taskId   <- nonEmptyTaskId(rawTaskId)
              configId <- nonEmptyConfigId(rawConfigId)
              config   <- requestHandler
                .getPushConfig(A2ARequest.PushNotificationConfigGet(taskId, configId, tenant), context)
            yield config
          )
        )
      case (Method.DELETE, List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            (for
              taskId   <- nonEmptyTaskId(rawTaskId)
              configId <- nonEmptyConfigId(rawConfigId)
              _        <- requestHandler
                .deletePushConfig(A2ARequest.PushNotificationConfigDelete(taskId, configId, tenant), context)
            yield ())
              .as(emptyResponse(204)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case (Method.GET, List("extendedAgentCard")) =>
        Some(json(requestHandler.getExtendedAgentCard(context)))
      case (_, "tasks" :: "" :: _) =>
        Some(json[A2ATask](ZIO.fail(A2AError.invalidParams("Missing task ID"))))
      case _ =>
        None
    end match
  end routeRest

  private def splitTenant(pathname: String): (Option[String], String) =
    val knownPrefixes = Set("message:send", "message:stream", "tasks", "extendedAgentCard")
    val stripped      = pathname.stripPrefix("/")
    val segments      = if stripped.isEmpty then Nil else stripped.split("/", -1).toList
    segments match
      case first :: rest if first.nonEmpty && !knownPrefixes.contains(first) =>
        Some(first) -> ("/" + rest.mkString("/"))
      case _ =>
        None -> pathname

  private def contextFrom(request: Request, tenant: Option[String]): ServerCallContext =
    def header(name: String): Option[String] =
      request.headers.get(name)
    ServerCallContext(
      tenant = tenant,
      requestedVersion = header(A2AHeader.Version),
      requestedExtensions = header(A2AHeader.StandardExtensions)
        .orElse(header(A2AHeader.Extensions))
        .toList
        .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)),
    )

  private def readBody(request: Request): Task[String] =
    val maxBytes = config.maxRequestBodyBytes
    request.headers.get("content-length").flatMap(_.toLongOption) match
      case Some(length) if maxBytes > 0 && length > maxBytes =>
        ZIO.fail(A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit"))
      case _ =>
        val bytes =
          if maxBytes > 0 then
            request.body.asStream.take(maxBytes.toLong + 1L).runCollect.flatMap { chunk =>
              if chunk.length > maxBytes then
                ZIO.fail(A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit"))
              else ZIO.succeed(chunk)
            }
          else request.body.asChunk
        bytes.map(chunk => String(chunk.toArray, StandardCharsets.UTF_8))

  private def validateServiceParameters(context: ServerCallContext, binding: A2ATransport): Task[Unit] =
    validateVersion(context, binding) *> validateRequiredExtensions(context)

  private def validateVersion(context: ServerCallContext, binding: A2ATransport): Task[Unit] =
    val supported = agentCard.supportedInterfaces.filter(_.protocolBinding == binding).map(_.protocolVersion).toSet
    val version   = context.requestedVersion.getOrElse("0.3")
    if supported.contains(version) then ZIO.unit
    else ZIO.fail(A2AError.versionNotSupported(version))

  private def validateRequiredExtensions(context: ServerCallContext): Task[Unit] =
    val requested = context.requestedExtensions.toSet
    config.capabilities.extensions.find(extension => extension.required && !requested.contains(extension.uri)) match
      case Some(extension) => ZIO.fail(A2AError.extensionSupportRequired(extension.uri))
      case None            => ZIO.unit

  private def jsonRpcErrorResponse(id: Option[JsonRpcId], error: A2AError): Response =
    jsonResponse(JsonRpcResponse.fromA2AError(id, error))

  private def jsonResponse[A: JsonEncoder](
    value: A,
    status: Int = 200,
    contentType: String = A2AContentType.Json,
  ): Response =
    textResponse(value.toJson, status, contentType)

  private def restErrorResponse(error: A2AError): Response =
    val status = error.code match
      case A2AErrorCode.TaskNotFound => 404
      case _                         => 400
    textResponse(restErrorResponseBody(error, status).toJson, status, A2AContentType.A2AJson)

  private def restErrorResponseBody(error: A2AError, status: Int = 400): Json =
    Json.Obj(
      "error" -> Json.Obj(
        "code"    -> Json.Num(java.math.BigDecimal.valueOf(status.toLong)),
        "status"  -> Json.Str(if status == 404 then "NOT_FOUND" else "INVALID_ARGUMENT"),
        "message" -> Json.Str(error.message),
        "details" -> Json.Arr(
          Json.Obj(
            "@type"  -> Json.Str("type.googleapis.com/google.rpc.ErrorInfo"),
            "reason" -> Json.Str(errorReason(error.code)),
            "domain" -> Json.Str("a2a-protocol.org"),
          )
        ),
      )
    )

  private def errorReason(code: Int): String =
    code match
      case A2AErrorCode.TaskNotFound                 => "TASK_NOT_FOUND"
      case A2AErrorCode.TaskNotCancelable            => "TASK_NOT_CANCELABLE"
      case A2AErrorCode.PushNotificationNotSupported => "PUSH_NOTIFICATION_NOT_SUPPORTED"
      case A2AErrorCode.UnsupportedOperation         => "UNSUPPORTED_OPERATION"
      case A2AErrorCode.ContentTypeNotSupported      => "CONTENT_TYPE_NOT_SUPPORTED"
      case A2AErrorCode.InvalidAgentResponse         => "INVALID_AGENT_RESPONSE"
      case A2AErrorCode.VersionNotSupported          => "VERSION_NOT_SUPPORTED"
      case A2AErrorCode.ExtensionSupportRequired     => "EXTENSION_SUPPORT_REQUIRED"
      case _                                         => "INVALID_PARAMS"

  private def emptyResponse(status: Int): Response =
    Response(status = Status.fromInt(status))

  private def textResponse(
    body: String,
    status: Int,
    contentType: String,
  ): Response =
    Response(
      status = Status.fromInt(status),
      headers = Headers("Content-Type", contentType),
      body = Body.fromString(body, StandardCharsets.UTF_8),
    )

  private def sseResponse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
  ): Response =
    val wire =
      stream
        .map(data => s"data: $data\n\n")
        .catchAll(error => ZStream.succeed(s"event: error\ndata: ${streamErrorJson(error, isJsonRpc)}\n\n"))
        .map(data => data: CharSequence)
    Response(
      status = Status.Ok,
      headers = Headers(
        Header.Custom("Content-Type", A2AContentType.Sse),
        Header.Custom("Cache-Control", "no-cache"),
        Header.Custom("Connection", "keep-alive"),
        Header.Custom("X-Accel-Buffering", "no"),
      ),
      body = Body.fromCharSequenceStreamChunked(wire),
    )

  private def streamErrorJson(error: Throwable, isJsonRpc: Boolean): String =
    val a2aError = toA2AError(error)
    if isJsonRpc then JsonRpcResponse.fromA2AError(None, a2aError).toJson
    else restErrorResponseBody(a2aError).toJson

  private def toA2AError(error: Throwable): A2AError =
    error match
      case err: A2AError => err
      case other         => A2AError.internalError(Option(other.getMessage).getOrElse(other.getClass.getName))
end A2AServerLiveImpl
