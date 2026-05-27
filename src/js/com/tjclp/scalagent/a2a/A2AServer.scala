package com.tjclp.scalagent.a2a

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import scala.scalajs.js.annotation.*
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.TimeoutException
import zio.*
import zio.stream.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.{ClaudeAgent, CollectionPolicy, QueryCollector, QueryResult}
import com.tjclp.scalagent.config.*

// `A2AServer`, `A2AEventPublisher`, `A2AEventStore`, `A2AReplayProvider`
// now live in `A2AServerTypes.scala` under shared sources (cross-built).

// JS-side server configuration lives on `A2AServerLive.Config` (below).
// The shared `trait A2AServer` doesn't have a companion `object A2AServer`
// because Scala 3 requires same-file companions, and the Config references
// JS-only `AgentOptions`. JVM mode defines its own Config under
// `src/jvm/a2a/`.

/**
 * Per-task A2A session logger. Writes JSONL to a configurable directory.
 *
 * This supplements Claude's native session transcripts with A2A-level events:
 * task IDs, prompts, completion status, and timing.
 */
object SessionLogger:
  @js.native
  @JSImport("node:fs", JSImport.Namespace)
  private object Fs extends js.Object:
    def appendFileSync(path: String, data: String): Unit   = js.native
    def mkdirSync(path: String, options: js.Dynamic): Unit = js.native
    def existsSync(path: String): Boolean                  = js.native

  private var logDir: Option[String] = None
  private var dirEnsured             = false

  /** Configure the log directory. Call before using logEvent. */
  def configure(dir: Option[String]): Unit =
    logDir = dir
    dirEnsured = false

  private def ensureDir(): Boolean =
    logDir match
      case None      => false
      case Some(dir) =>
        if !dirEnsured then
          try
            if !Fs.existsSync(dir) then Fs.mkdirSync(dir, js.Dynamic.literal(recursive = true))
            dirEnsured = true
          catch case _: Throwable => ()
        dirEnsured

  /** Log an A2A event. */
  def logEvent(
    taskId: String,
    event: String,
    data: String,
  ): Unit =
    if !ensureDir() then return
    val dir     = logDir.get
    val ts      = new js.Date().toISOString().asInstanceOf[String]
    val escaped = data
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      .replaceAll("[\\x00-\\x1f]", "")
    val line = s"""{"ts":"$ts","taskId":"$taskId","event":"$event","data":"$escaped"}"""
    try Fs.appendFileSync(s"$dir/$taskId.jsonl", line + "\n")
    catch case _: Throwable => ()
end SessionLogger

// `PushNotificationUrlPolicy` (trait + companion) now lives in
// `A2AServerTypes.scala` under shared sources. The `externalOnly`
// implementation was refactored to use `java.net.URI` instead of
// the JS `URL` constructor for cross-build compatibility.

/**
 * Factory + Config for the JS-side `A2AServerLive` implementation. JVM mode
 * uses a separate factory + Config in `src/jvm/a2a/`. The JS Config carries
 * Claude-Agent-SDK-specific fields (`agentOptions`, `invocationPreparer`)
 * that the JVM side doesn't need.
 */
object A2AServerLive:

  final case class Config(
    name: String,
    description: String,
    host: String = "localhost",
    port: Int = 3000,
    agentOptions: AgentOptions = AgentOptions.default,
    executionMode: ExecutionMode = ExecutionMode.Default,
    taskTimeout: Option[Duration] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default,
    skills: List[AgentSkill] = Nil,
    sessionLogDir: Option[String] = None,
    invocationPreparer: Option[(A2AMessage, TaskId) => Task[InvocationContext]] = None,
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

  /** Workspace-staging helper bound to the JS-only `WorkspaceStaging`. */
  val workspaceStaging: (A2AMessage, TaskId) => Task[InvocationContext] =
    (message, taskId) => ZIO.attempt(WorkspaceStaging.stageFromMessage(message, taskId).toInvocationContext)

  /** Create and start an A2A server. */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    for
      runtime <- ZIO.runtime[Any]
      server  <- ZIO.acquireRelease(start(config, runtime))(_.stop.ignore)
    yield server

  /** Start server without scope management. */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    for
      registry <- A2ARuntimeRegistry.make
      server   <- ZIO.attempt(A2AServerLiveImpl(config, runtime, registry))
      _        <- server.start
    yield server

  /** Create a server layer. */
  def live(config: Config): ZLayer[Scope, Throwable, A2AServer] =
    ZLayer.fromZIO(create(config))
end A2AServerLive

private final class PushNotificationSender(
  store: A2APushNotificationStore,
  urlPolicy: PushNotificationUrlPolicy)
    extends A2APushNotificationSender:
  private val deliveryChains = mutable.Map.empty[(String, String), Promise[Nothing, Unit]]

  def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit] =
    val key = (context.tenant.getOrElse(""), event.taskId.value)
    (for
      previous <- ZIO.succeed(deliveryChains.get(key))
      current  <- Promise.make[Nothing, Unit]
      _        <- ZIO.succeed(deliveryChains.update(key, current))
      _        <-
        (previous.fold(ZIO.unit)(_.await) *> sendNow(event, context))
          .catchAll(error => ZIO.logWarning(s"Failed to send A2A push notification: ${error.getMessage}"))
          .ensuring(
            current.succeed(()).unit *>
              ZIO.succeed {
                if deliveryChains.get(key).contains(current) then deliveryChains.remove(key)
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
      ZIO
        .fromPromiseJS {
          val headers = js.Dynamic.literal(`Content-Type` = A2AContentType.A2AJson)
          config.authentication match
            case Some(auth) if auth.scheme.nonEmpty && auth.credentials.nonEmpty =>
              headers.updateDynamic("Authorization")(s"${auth.scheme} ${auth.credentials}")
            case _ =>
              config.token.foreach(token => headers.updateDynamic("X-A2A-Notification-Token")(token))
          val init = js.Dynamic.literal(
            method = "POST",
            headers = headers,
            body = event.toJson,
          )
          js.Dynamic.global.fetch(config.url, init).asInstanceOf[js.Promise[js.Dynamic]]
        }
        .flatMap { response =>
          val ok = response.selectDynamic("ok").asInstanceOf[Boolean]
          if ok then ZIO.unit
          else
            val status = response.selectDynamic("status").asInstanceOf[Int]
            ZIO.fail(new RuntimeException(s"Push callback ${config.url} returned HTTP $status"))
        }
end PushNotificationSender

/** Live implementation of A2A Server (Bun.serve runtime). */
private final class A2AServerLiveImpl(
  config: A2AServerLive.Config,
  runtime: Runtime[Any],
  runtimeRegistry: A2ARuntimeRegistry)
    extends A2AServer:

  private var bunServer: js.Dynamic = null
  private val taskStore             = config.taskStore.getOrElse(A2ATaskStore.inMemory)
  private val pushStore             = config.pushNotificationStore.getOrElse(A2APushNotificationStore.inMemory)
  private val pushSender            = PushNotificationSender(pushStore, config.pushNotificationUrlPolicy)
  private val requestConfig         = A2ARequestHandler.Config(
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

  override def agentCard: AgentCard = config.toAgentCardAt(url)

  override def url: String =
    if bunServer == null then config.url
    else
      val actualPort = bunServer.selectDynamic("port")
      if js.isUndefined(actualPort) || actualPort == null then config.url
      else s"http://${config.host}:${actualPort.asInstanceOf[Int]}"

  override def start: Task[Unit] =
    ZIO.attempt {
      SessionLogger.configure(config.sessionLogDir)
      bunServer = BunServer.serve(
        js.Dynamic.literal(
          hostname = config.host,
          port = config.port,
          fetch = createFetchHandler,
        )
      )
      ()
    }

  override def stop: Task[Unit] =
    runtimeRegistry.interruptAll *>
      ZIO.attempt {
        if bunServer != null then bunServer.stop()
        ()
      }

  private def execute(prepared: A2ARequestHandler.PreparedRun, publisher: A2AEventPublisher): Task[Unit] =
    config.executionOverride match
      case Some(overrideRun) =>
        withTaskTimeout(
          prepared.task.id,
          overrideRun(prepared.message, prepared.task.id, prepared.task.contextId, publisher),
        )
      case None =>
        val preparedInvocation =
          config.invocationPreparer match
            case Some(prep) => prep(prepared.message, prepared.task.id)
            case None       => ZIO.succeed(InvocationContext(prompt = prepared.message.text))

        val effect: Task[(QueryResult, List[Artifact])] =
          preparedInvocation.flatMap { invocation =>
            SessionLogger.logEvent(prepared.task.id.value, "prompt", invocation.prompt)
            ClaudeAgent
              .queryComplete(
                invocation.prompt,
                invocation.optionsModifier(config.agentOptions),
                collectionPolicy = CollectionPolicy.ResultOnly,
                sink = progressSink(prepared.task.id, prepared.task.contextId, publisher),
              )
              .provideLayer(ClaudeAgent.live)
              .flatMap(result => invocation.artifactsAfter.map(artifacts => (result, artifacts)))
              .ensuring(invocation.cleanup.ignore)
          }

        withTaskTimeout(prepared.task.id, effect).flatMap {
          case (queryResult, artifacts) =>
            val responseText =
              queryResult.outcome.resultText
                .orElse(queryResult.semanticText.toOption)
                .getOrElse("Error: " + queryResult.outcome.toString)
            val responseMsg = A2AMessage
              .agentText(responseText, Some(prepared.task.contextId))
              .copy(taskId = Some(prepared.task.id))
            SessionLogger.logEvent(prepared.task.id.value, "completed", responseText.take(500))
            ZIO.foreachDiscard(artifacts)(artifact =>
              publisher.publish(
                A2AResponse.StreamEvent.TaskArtifactUpdate(
                  prepared.task.id,
                  prepared.task.contextId,
                  artifact,
                  append = false,
                  lastChunk = true,
                )
              )
            ) *>
              publisher.publish(
                A2AResponse.StreamEvent.TaskStatusUpdate(
                  prepared.task.id,
                  prepared.task.contextId,
                  TaskStatus.completed(responseMsg),
                  `final` = true,
                )
              )
        }

  private def progressSink(
    taskId: TaskId,
    contextId: ContextId,
    publisher: A2AEventPublisher,
  ): QueryCollector.MessageSink =
    var state = A2AProgress.State.empty
    message =>
      val (nextState, statusMessage) = A2AProgress.statusMessage(message, contextId, taskId, state)
      state = nextState
      statusMessage match
        case Some(progressMessage) =>
          publisher.publish(
            A2AResponse.StreamEvent.TaskStatusUpdate(
              taskId,
              contextId,
              TaskStatus.working(Some(progressMessage)),
            )
          )
        case None =>
          ZIO.unit
  end progressSink

  private def withTaskTimeout[A](taskId: TaskId, effect: Task[A]): Task[A] =
    config.taskTimeout match
      case Some(timeout) =>
        effect.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} timed out after $timeout"))(timeout)
      case None =>
        effect

  private def createFetchHandler: js.Function1[js.Dynamic, js.Promise[js.Dynamic]] =
    (req: js.Dynamic) =>
      val method   = req.method.asInstanceOf[String]
      val urlObj   = js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url.asInstanceOf[String])
      val pathname = urlObj.pathname.asInstanceOf[String]

      if pathname == A2APaths.AgentCard && method == "GET" then js.Promise.resolve(jsonResponse(agentCard))
      else if pathname == "/" && method == "POST" then
        runToPromise(
          readBody(req)
            .flatMap(body => handleJsonRpc(body, contextFrom(req, None)))
            .catchAll(error => ZIO.succeed(jsonResponse(JsonRpcResponse.fromA2AError(None, toA2AError(error)))))
        )
      else
        routeRest(method, pathname, urlObj, req) match
          case Some(effect) => runToPromise(effect)
          case None         => js.Promise.resolve(textResponse("Not Found", 404, "text/plain"))

  private def contextFrom(req: js.Dynamic, tenant: Option[String]): ServerCallContext =
    val headers                              = req.selectDynamic("headers")
    def header(name: String): Option[String] =
      if js.isUndefined(headers) || headers == null then None
      else
        val value = headers.asInstanceOf[js.Dynamic].get(name)
        if js.isUndefined(value) || value == null then None
        else Some(value.asInstanceOf[String])
    ServerCallContext(
      tenant = tenant,
      requestedVersion = header(A2AHeader.Version),
      requestedExtensions = header(A2AHeader.StandardExtensions)
        .orElse(header(A2AHeader.Extensions))
        .toList
        .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)),
    )

  private def readBody(req: js.Dynamic): Task[String] =
    val headers                              = req.selectDynamic("headers")
    def header(name: String): Option[String] =
      if js.isUndefined(headers) || headers == null then None
      else
        val value = headers.asInstanceOf[js.Dynamic].get(name)
        if js.isUndefined(value) || value == null then None
        else Some(value.asInstanceOf[String])
    val maxBytes = config.maxRequestBodyBytes
    header("content-length").flatMap(_.toIntOption) match
      case Some(length) if maxBytes > 0 && length > maxBytes =>
        ZIO.fail(A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit"))
      case _ =>
        ZIO.fromPromiseJS(req.text().asInstanceOf[js.Promise[String]]).flatMap { body =>
          if maxBytes > 0 && utf8ByteLength(body) > maxBytes then
            ZIO.fail(A2AError.invalidRequest(s"Request body exceeds ${maxBytes} byte limit"))
          else ZIO.succeed(body)
        }

  private def utf8ByteLength(body: String): Int =
    js.Dynamic
      .newInstance(js.Dynamic.global.TextEncoder)()
      .encode(body)
      .length
      .asInstanceOf[Int]

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

  private def handleJsonRpc(body: String, context: ServerCallContext): Task[js.Dynamic] =
    ZIO
      .fromEither(body.fromJson[JsonRpcRequest].left.map(A2AError.invalidRequest))
      .foldZIO(
        error => ZIO.succeed(jsonResponse(JsonRpcResponse.fromA2AError(None, error))),
        request =>
          val isStreaming = request.method == A2AMethod.MessageStream || request.method == A2AMethod.TasksResubscribe
          val routed      =
            if isStreaming then handleJsonRpcStream(request, context)
            else handleJsonRpcSingle(request, context).map(response => jsonResponse(response))
          (validateServiceParameters(context, A2ATransport.JSONRPC) *> routed)
            .catchAll(error => ZIO.succeed(jsonResponse(JsonRpcResponse.fromA2AError(request.id, toA2AError(error))))),
      )

  private def handleJsonRpcSingle(request: JsonRpcRequest, context: ServerCallContext): Task[JsonRpcResponse] =
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

  private def handleJsonRpcStream(request: JsonRpcRequest, context: ServerCallContext): Task[js.Dynamic] =
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

  private def routeRest(
    method: String,
    pathname: String,
    urlObj: js.Dynamic,
    req: js.Dynamic,
  ): Option[Task[js.Dynamic]] =
    val (tenant, path)                            = A2APathRouting.splitTenant(pathname)
    val query                                     = urlObj.searchParams.asInstanceOf[js.Dynamic]
    def queryString(name: String): Option[String] =
      val value = query.get(name)
      if js.isUndefined(value) || value == null then None
      else Some(value.asInstanceOf[String])
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
    val baseContext = contextFrom(req, tenant)
    val context     = baseContext.copy(
      requestedVersion = baseContext.requestedVersion
        .orElse(queryString(A2AHeader.Version))
        .orElse(queryString("a2aVersion"))
    )
    def bodyAs[A: JsonDecoder]: Task[A] =
      readBody(req).flatMap { body => ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest)) }
    def json[A: JsonEncoder](effect: Task[A], status: Int = 200): Task[js.Dynamic] =
      (validateServiceParameters(context, A2ATransport.HTTP_JSON) *> effect)
        .map(value => jsonResponse(value, status, A2AContentType.A2AJson))
        .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))

    val segments = rawSegments(path)

    (method, segments) match
      case ("POST", List("message:send")) =>
        Some(json(bodyAs[A2ARequest.MessageSend].flatMap(requestHandler.sendMessage(_, context))))
      case ("POST", List("message:stream")) =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            bodyAs[A2ARequest.MessageSend]
              .flatMap(requestHandler.sendMessageStream(_, context))
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case ("GET", List("tasks")) =>
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
      case ("GET", List("tasks", rawTaskId)) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap { taskId =>
              queryInt("historyLength").flatMap(historyLength =>
                requestHandler.getTask(A2ARequest.TasksGet(taskId, historyLength, tenant), context)
              )
            }
          )
        )
      case ("POST", List("tasks", rawTaskAction)) if rawTaskAction.endsWith(":cancel") =>
        Some(
          json(
            nonEmptyTaskId(rawTaskAction.stripSuffix(":cancel")).flatMap(taskId =>
              requestHandler.cancelTask(A2ARequest.TasksCancel(taskId, tenant = tenant), context)
            ),
            status = 202,
          )
        )
      case (verb, List("tasks", rawTaskAction))
          if (verb == "GET" || verb == "POST") && rawTaskAction.endsWith(":subscribe") =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            nonEmptyTaskId(rawTaskAction.stripSuffix(":subscribe"))
              .flatMap(taskId => requestHandler.resubscribe(A2ARequest.TasksResubscribe(taskId, tenant), context))
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case ("POST", List("tasks", rawTaskId, "pushNotificationConfigs")) =>
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
      case ("GET", List("tasks", rawTaskId, "pushNotificationConfigs")) =>
        Some(
          json(
            nonEmptyTaskId(rawTaskId).flatMap(taskId =>
              requestHandler.listPushConfigs(A2ARequest.PushNotificationConfigList(taskId, tenant = tenant), context)
            )
          )
        )
      case ("GET", List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
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
      case ("DELETE", List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
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
      case ("GET", List("extendedAgentCard")) =>
        Some(json(requestHandler.getExtendedAgentCard(context)))
      case (_, "tasks" :: "" :: _) =>
        Some(json[A2ATask](ZIO.fail(A2AError.invalidParams("Missing task ID"))))
      case _ =>
        None
    end match
  end routeRest

  private def toA2AError(error: Throwable): A2AError =
    error match
      case err: A2AError => err
      case other         => A2AError.internalError(other.getMessage)

  private def runToPromise(effect: Task[js.Dynamic]): js.Promise[js.Dynamic] =
    Unsafe.unsafe { implicit unsafe => runtime.unsafe.runToFuture(effect).toJSPromise }

  private def jsonResponse[A: JsonEncoder](
    value: A,
    status: Int = 200,
    contentType: String = A2AContentType.Json,
  ): js.Dynamic =
    textResponse(value.toJson, status, contentType)

  private def restErrorResponse(error: A2AError): js.Dynamic =
    val status = error.code match
      case A2AErrorCode.TaskNotFound => 404
      case _                         => 400
    val body = Json.Obj(
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
    textResponse(body.toJson, status, A2AContentType.A2AJson)

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

  private def emptyResponse(status: Int): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      null,
      js.Dynamic.literal(status = status, headers = js.Dynamic.literal()),
    )

  private def textResponse(
    body: String,
    status: Int,
    contentType: String,
  ): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      body,
      js.Dynamic.literal(status = status, headers = js.Dynamic.literal(`Content-Type` = contentType)),
    )

  private def sseResponse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
  ): js.Dynamic =
    val encoder                                       = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    var fiber: Option[Fiber.Runtime[Throwable, Unit]] = None
    var canceled                                      = false
    val readable                                      = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(
      js.Dynamic.literal(
        start = (controller: js.Dynamic) =>
          val run =
            stream
              .runForeach { data =>
                ZIO.attempt {
                  if !canceled then controller.enqueue(encoder.encode(s"data: $data\n\n"))
                }
              }
              .catchAll { error =>
                ZIO.attempt {
                  if !canceled then
                    controller.enqueue(encoder.encode(s"event: error\ndata: ${streamErrorJson(error, isJsonRpc)}\n\n"))
                }
              }
              .ensuring(ZIO.attempt(if !canceled then controller.close()).ignore)
          Unsafe.unsafe { implicit unsafe => fiber = Some(runtime.unsafe.fork(run)) }
        ,
        cancel = (_: js.Any) =>
          canceled = true
          fiber.foreach(active => Unsafe.unsafe { implicit unsafe => runtime.unsafe.fork(active.interrupt) })
          (),
      )
    )
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      readable,
      js.Dynamic.literal(
        status = 200,
        headers = js.Dynamic.literal(
          `Content-Type` = A2AContentType.Sse,
          `Cache-Control` = "no-cache",
          Connection = "keep-alive",
          `X-Accel-Buffering` = "no",
        ),
      ),
    )
  end sseResponse

  private def streamErrorJson(error: Throwable, isJsonRpc: Boolean): String =
    val a2aError = toA2AError(error)
    if isJsonRpc then JsonRpcResponse.fromA2AError(None, a2aError).toJson
    else restErrorResponseBody(a2aError).toJson

  private def restErrorResponseBody(error: A2AError): Json =
    Json.Obj(
      "error" -> Json.Obj(
        "code"    -> Json.Num(java.math.BigDecimal.valueOf(400L)),
        "status"  -> Json.Str("INVALID_ARGUMENT"),
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
end A2AServerLiveImpl

/** Bun.serve binding. */
@js.native
@JSGlobal("Bun")
private object BunServer extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
