package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import scala.scalajs.js.annotation.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.stream.*
import zio.json.*
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
    host: String = A2AServerDefaults.JsHost,
    port: Int = A2AServerDefaults.Port,
    agentOptions: AgentOptions = AgentOptions.default,
    executionMode: ExecutionMode = ExecutionMode.Default,
    taskTimeout: Option[Duration] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default,
    skills: List[AgentSkill] = Nil,
    extendedAgentCard: Option[AgentCard] = None,
    sessionLogDir: Option[String] = None,
    invocationPreparer: Option[(A2AMessage, TaskId) => Task[InvocationContext]] = None,
    executionOverride: Option[(A2AMessage, TaskId, ContextId, A2AEventPublisher) => Task[Unit]] = None,
    pushNotificationStore: Option[A2APushNotificationStore] = None,
    taskStore: Option[A2ATaskStore] = None,
    eventStore: Option[A2AEventStore] = None,
    replayProvider: Option[A2AReplayProvider] = None,
    eventReplayLimit: Int = A2AServerDefaults.EventReplayLimit,
    eventStoreAppendTimeout: Duration = A2AServerDefaults.EventStoreAppendTimeout,
    eventStoreLoadTimeout: Duration = A2AServerDefaults.EventStoreLoadTimeout,
    maxRequestBodyBytes: Int = A2AServerDefaults.MaxRequestBodyBytes,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy = A2AServerDefaults.PushUrlPolicy,
    tenant: Option[String] = None)
      extends A2AServerLiveConfig

  /** Workspace-staging helper bound to the JS-only `WorkspaceStaging`. */
  val workspaceStaging: (A2AMessage, TaskId) => Task[InvocationContext] =
    (message, taskId) => ZIO.attempt(WorkspaceStaging.stageFromMessage(message, taskId).toInvocationContext)

  /** Create and start an A2A server. */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    A2AServerLifecycle.create(start(config, _))

  /** Start server without scope management. */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    A2AServerLifecycle.start { registry =>
      Ref.Synchronized
        .make(Option.empty[js.Dynamic])
        .map(serverRef => A2AServerLiveImpl(config, runtime, registry, serverRef))
    }

  /** Create a server layer. */
  def live(config: Config): ZLayer[Scope, Throwable, A2AServer] =
    A2AServerLifecycle.live(create(config))
end A2AServerLive

private object JsPushNotificationPoster extends A2APushNotificationPoster:
  def post(
    event: A2AResponse.StreamEvent,
    config: TaskPushNotificationConfig,
    headers: List[(String, String)],
  ): Task[Unit] =
    ZIO
      .fromPromiseJS {
        val jsHeaders = js.Dynamic.literal()
        headers.foreach { case (name, value) => jsHeaders.updateDynamic(name)(value) }
        val init = js.Dynamic.literal(
          method = "POST",
          headers = jsHeaders,
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
end JsPushNotificationPoster

/** Live implementation of A2A Server (Bun.serve runtime). */
private final class A2AServerLiveImpl(
  config: A2AServerLive.Config,
  runtime: Runtime[Any],
  runtimeRegistry: A2ARuntimeRegistry,
  serverRef: Ref.Synchronized[Option[js.Dynamic]])
    extends A2AServer:

  private var bunServer: js.Dynamic = null
  private val serverCore            =
    A2AServerCore.make(config, runtime, runtimeRegistry, JsPushNotificationPoster, () => agentCard, execute)
  private val requestHandler = serverCore.requestHandler

  override def agentCard: AgentCard = config.toAgentCardAt(url)

  override def url: String =
    if bunServer == null then config.url
    else
      val actualPort = bunServer.selectDynamic("port")
      if js.isUndefined(actualPort) || actualPort == null then config.url
      else s"http://${config.host}:${actualPort.asInstanceOf[Int]}"

  override def start: Task[Unit] =
    A2AServerLifecycle.startOnce(serverRef) {
      ZIO.attempt {
        SessionLogger.configure(config.sessionLogDir)
        val server = BunServer.serve(
          js.Dynamic.literal(
            hostname = config.host,
            port = config.port,
            fetch = createFetchHandler,
          )
        )
        bunServer = server
        server
      }
    }

  override def stop: Task[Unit] =
    runtimeRegistry.interruptAll *>
      A2AServerLifecycle.stopOnce(serverRef) { server =>
        ZIO.attempt {
          server.stop()
          bunServer = null
        }
      }

  private def execute(prepared: A2ARequestHandler.PreparedRun, publisher: A2AEventPublisher): Task[Unit] =
    config.runExecutionOverride(prepared, publisher) match
      case Some(effect) =>
        effect
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

        A2ATaskTimeout(prepared.task.id, config.taskTimeout, effect).flatMap {
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

  private def createFetchHandler: js.Function1[js.Dynamic, js.Promise[js.Dynamic]] =
    (req: js.Dynamic) =>
      val method      = req.method.asInstanceOf[String]
      val urlObj      = js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url.asInstanceOf[String])
      val pathname    = urlObj.pathname.asInstanceOf[String]
      val httpRequest = JsA2AHttpRequestView(req, urlObj, method, pathname)

      runToPromise(
        A2AHttpBinding
          .dispatchHttp(httpRequest, agentCard, config.capabilities, requestHandler)
          .map(renderHttpResponse)
      )

  private final class JsA2AHttpRequestView(
    req: js.Dynamic,
    urlObj: js.Dynamic,
    val methodName: String,
    val path: String)
      extends A2AHttpRequestView:
    def header(name: String): Option[String] =
      val headers = req.selectDynamic("headers")
      if js.isUndefined(headers) || headers == null then None
      else
        val value = headers.asInstanceOf[js.Dynamic].get(name)
        if js.isUndefined(value) || value == null then None
        else Some(value.asInstanceOf[String])

    def queryParam(name: String): Option[String] =
      val searchParams = urlObj.searchParams.asInstanceOf[js.Dynamic]
      val value        = searchParams.get(name)
      if js.isUndefined(value) || value == null then None
      else Some(value.asInstanceOf[String])

    def readBody: Task[String] =
      val maxBytes = config.maxRequestBodyBytes
      ZIO.fromEither(
        A2AHttpBinding.validateContentLength(header("content-length").flatMap(_.toLongOption), maxBytes)
      ) *>
        ZIO.fromPromiseJS(req.text().asInstanceOf[js.Promise[String]]).flatMap { body =>
          ZIO
            .fromEither(A2AHttpBinding.validateBodyLength(utf8ByteLength(body).toLong, maxBytes))
            .as(body)
        }
  end JsA2AHttpRequestView

  private def utf8ByteLength(body: String): Int =
    js.Dynamic
      .newInstance(js.Dynamic.global.TextEncoder)()
      .encode(body)
      .length
      .asInstanceOf[Int]

  private def renderHttpResponse(plan: A2AHttpResponsePlan): js.Dynamic =
    plan match
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        textResponse(body, status, headers)
      case A2AHttpResponsePlan.Empty(status, headers) =>
        emptyResponse(status, headers)
      case A2AHttpResponsePlan.Sse(stream, isJsonRpc, headers) =>
        sseResponse(stream, isJsonRpc, headers)

  private def runToPromise(effect: Task[js.Dynamic]): js.Promise[js.Dynamic] =
    Unsafe.unsafe { implicit unsafe => runtime.unsafe.runToFuture(effect).toJSPromise }

  private def emptyResponse(status: Int, headers: List[(String, String)]): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      null,
      js.Dynamic.literal(status = status, headers = responseHeaders(headers)),
    )

  private def textResponse(
    body: String,
    status: Int,
    headers: List[(String, String)],
  ): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      body,
      js.Dynamic.literal(status = status, headers = responseHeaders(headers)),
    )

  private def sseResponse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
    headers: List[(String, String)],
  ): js.Dynamic =
    val encoder                                       = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    var fiber: Option[Fiber.Runtime[Throwable, Unit]] = None
    var canceled                                      = false
    val readable                                      = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(
      js.Dynamic.literal(
        start = (controller: js.Dynamic) =>
          val run =
            A2AHttpBinding
              .sseWireStream(stream, isJsonRpc)
              .runForeach { frame =>
                ZIO.attempt {
                  if !canceled then controller.enqueue(encoder.encode(frame))
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
        headers = responseHeaders(headers),
      ),
    )
  end sseResponse

  private def responseHeaders(values: List[(String, String)]): js.Dynamic =
    val headers = js.Dynamic.literal()
    values.foreach { case (name, value) => headers.updateDynamic(name)(value) }
    headers
end A2AServerLiveImpl

/** Bun.serve binding. */
@js.native
@JSGlobal("Bun")
private object BunServer extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
