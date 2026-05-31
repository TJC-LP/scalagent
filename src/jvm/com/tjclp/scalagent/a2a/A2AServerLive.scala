package com.tjclp.scalagent.a2a

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

/**
 * JVM-side server configuration. Mirror of the JS `A2AServerLive.Config`
 * minus Claude-Agent-SDK-specific fields (`agentOptions`,
 * `invocationPreparer`) since the JVM scalagent build doesn't include the
 * Claude Agent SDK adapters.
 *
 * The JVM default host binds all interfaces for container deployments; set it
 * to `localhost` for local-only development. Tenant values are protocol
 * fields, not authentication: production deployments should place this server
 * behind an auth layer that validates or overrides tenant identity.
 */
object A2AServerLive:

  final case class Config(
    name: String,
    description: String,
    host: String = A2AServerDefaults.JvmHost,
    port: Int = A2AServerDefaults.Port,
    executionMode: ExecutionMode = ExecutionMode.Default,
    taskTimeout: Option[Duration] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default,
    skills: List[AgentSkill] = Nil,
    extendedAgentCard: Option[AgentCard] = None,
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

  /** Create and start a JVM A2A server. */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    A2AServerLifecycle.create(start(config, _))

  /** Start a JVM A2A server without scope management. */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    A2AServerLifecycle.start { runtimeRegistry =>
      for
        serverScopeRef <- Ref.Synchronized.make(Option.empty[Scope.Closeable])
        server         <- ZIO.attempt(A2AServerLiveImpl(config, runtime, runtimeRegistry, serverScopeRef))
      yield server
    }

  /** Create a server layer. */
  def live(config: Config): ZLayer[Scope, Throwable, A2AServer] =
    A2AServerLifecycle.live(create(config))
end A2AServerLive

private object JvmPushNotificationPoster extends A2APushNotificationPoster:
  private val client = HttpClient.newHttpClient()

  def post(
    event: A2AResponse.StreamEvent,
    config: TaskPushNotificationConfig,
    headers: List[(String, String)],
  ): Task[Unit] =
    ZIO.attemptBlocking {
      var builder = HttpRequest
        .newBuilder(URI.create(config.url))
        .POST(HttpRequest.BodyPublishers.ofString(event.toJson, StandardCharsets.UTF_8))
      headers.foreach { case (name, value) => builder = builder.header(name, value) }
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
      if response.statusCode() < 200 || response.statusCode() >= 300 then
        throw RuntimeException(s"Push callback ${config.url} returned HTTP ${response.statusCode()}")
    }
end JvmPushNotificationPoster

/** Live implementation of A2A Server using zio-http. */
private[a2a] final class A2AServerLiveImpl(
  config: A2AServerLive.Config,
  runtime: Runtime[Any],
  runtimeRegistry: A2ARuntimeRegistry,
  serverScopeRef: Ref.Synchronized[Option[Scope.Closeable]])
    extends A2AServer:

  private val serverCore =
    A2AServerCore.make(config, runtime, runtimeRegistry, JvmPushNotificationPoster, () => agentCard, execute)
  private val requestHandler = serverCore.requestHandler

  def agentCard: AgentCard = config.toAgentCardAt(url)

  def url: String = config.url

  def start: Task[Unit] =
    A2AServerLifecycle.startOnce(serverScopeRef)(openServerScope)

  private def openServerScope: Task[Scope.Closeable] =
    for
      scope <- Scope.make
      _     <-
        (for
          serverEnv <- (ZLayer.succeed(Server.Config.default.binding(config.host, config.port)) >>> Server.live)
            .build(scope)
          _ <- Server.install(a2aRoutes).provideEnvironment(serverEnv)
        yield ()).catchAllCause(closeStartupScope(scope, _))
    yield scope

  private def closeStartupScope(scope: Scope.Closeable, cause: Cause[Throwable]): Task[Nothing] =
    val failure =
      if cause.isInterruptedOnly then ZIO.failCause(cause)
      else ZIO.fail(cause.squash)
    scope.close(Exit.failCause(cause)).ignore *> failure

  def stop: Task[Unit] =
    runtimeRegistry.interruptAll *>
      A2AServerLifecycle.stopOnce(serverScopeRef)(_.close(Exit.succeed(())))

  private def execute(prepared: A2ARequestHandler.PreparedRun, publisher: A2AEventPublisher): Task[Unit] =
    config.runExecutionOverride(prepared, publisher) match
      case Some(effect) =>
        effect
      case None =>
        ZIO.fail(
          A2AError.invalidRequest("This JVM A2AServerLive requires `executionOverride` to be configured")
        )

  private def a2aRoutes: Routes[Any, Nothing] =
    Routes.singleton(handler { (_: Path, request: Request) => handleHttp(request) })

  private[a2a] def handleHttp(request: Request): UIO[Response] =
    val httpRequest = ZioHttpA2ARequestView(request)
    A2AHttpBinding
      .dispatchHttp(httpRequest, agentCard, config.capabilities, requestHandler)
      .map(renderHttpResponse)

  private[a2a] def dispatchJsonRpc(request: JsonRpcRequest): Task[JsonRpcResponse] =
    A2AJsonRpcRouting.single(request, ServerCallContext(), requestHandler)

  private def renderHttpResponse(plan: A2AHttpResponsePlan): Response =
    plan match
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        textResponse(body, status, headers)
      case A2AHttpResponsePlan.Empty(status, headers) =>
        emptyResponse(status, headers)
      case A2AHttpResponsePlan.Sse(stream, isJsonRpc, headers) =>
        sseResponse(stream, isJsonRpc, headers)

  private def restMethodName(method: Method): String =
    method match
      case Method.GET    => "GET"
      case Method.POST   => "POST"
      case Method.DELETE => "DELETE"
      case other         => other.toString

  private final class ZioHttpA2ARequestView(request: Request) extends A2AHttpRequestView:
    def methodName: String = restMethodName(request.method)
    def path: String       = request.path.encode

    def header(name: String): Option[String] =
      request.headers.get(name)

    def queryParam(name: String): Option[String] =
      request.url.queryParams.getAll(name).headOption

    def readBody: Task[String] =
      val maxBytes = config.maxRequestBodyBytes
      ZIO.fromEither(
        A2AHttpBinding.validateContentLength(header("content-length").flatMap(_.toLongOption), maxBytes)
      ) *>
        {
          val bytes =
            if maxBytes > 0 then
              request.body.asStream.take(maxBytes.toLong + 1L).runCollect.flatMap { chunk =>
                if chunk.length > maxBytes then ZIO.fail(A2AHttpBinding.bodySizeExceeded(maxBytes))
                else ZIO.succeed(chunk)
              }
            else request.body.asChunk
          bytes.map(chunk => String(chunk.toArray, StandardCharsets.UTF_8))
        }
  end ZioHttpA2ARequestView

  private def emptyResponse(status: Int, headers: List[(String, String)]): Response =
    Response(status = Status.fromInt(status), headers = responseHeaders(headers))

  private def textResponse(
    body: String,
    status: Int,
    headers: List[(String, String)],
  ): Response =
    Response(
      status = Status.fromInt(status),
      headers = responseHeaders(headers),
      body = Body.fromString(body, StandardCharsets.UTF_8),
    )

  private def sseResponse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
    headers: List[(String, String)],
  ): Response =
    val wire =
      A2AHttpBinding
        .sseWireStream(stream, isJsonRpc)
        .map(data => data: CharSequence)
    Response(
      status = Status.Ok,
      headers = responseHeaders(headers),
      body = Body.fromCharSequenceStreamChunked(wire),
    )
  end sseResponse

  private def responseHeaders(values: List[(String, String)]): Headers =
    Headers.fromIterable(values.map { case (name, value) => Header.Custom(name, value) })
end A2AServerLiveImpl
