package com.tjclp.scalagent.a2a

import java.net.URI
import java.net.InetSocketAddress
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import io.grpc.Server as GrpcServer
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
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
  final case class GrpcTlsConfig(
    certChainPath: String,
    privateKeyPath: String)

  final case class Config(
    name: String,
    description: String,
    host: String = A2AServerDefaults.JvmHost,
    port: Int = A2AServerDefaults.Port,
    grpcPort: Option[Int] = None,
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
    override val agentCardAuth: A2AAgentCardAuth = A2AAgentCardAuth.permitAll,
    extendedAgentCardAuth: A2AExtendedAgentCardAuth = A2AExtendedAgentCardAuth.requireAuthorizationHeader,
    requestAuth: A2ARequestAuth = A2ARequestAuth.requireAuthorizationWhenAdvertised,
    override val messageResponseOverride: Option[A2ARequest.MessageSend => Task[A2AMessage]] = None,
    tenant: Option[String] = None,
    advertisedUrl: Option[String] = None,
    grpcTls: Option[GrpcTlsConfig] = None,
    grpcAdvertisedUrl: Option[String] = None)
      extends A2AServerLiveConfig

  /** Create and start a JVM A2A server. */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    A2AServerLifecycle.create(start(config, _))

  /** Start a JVM A2A server without scope management. */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    A2AServerLifecycle.start { runtimeRegistry =>
      for
        serverScopeRef <- Ref.Synchronized.make(Option.empty[Scope.Closeable])
        grpcServerRef  <- Ref.Synchronized.make(Option.empty[GrpcServer])
        server <- ZIO.attempt(A2AServerLiveImpl(config, runtime, runtimeRegistry, serverScopeRef, grpcServerRef))
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
  serverScopeRef: Ref.Synchronized[Option[Scope.Closeable]],
  grpcServerRef: Ref.Synchronized[Option[GrpcServer]])
    extends A2AServer
    with A2AHttpResponseRenderer[Response]:

  private val serverCore =
    A2AServerCore.make(config, runtime, runtimeRegistry, JvmPushNotificationPoster, () => agentCard, execute)
  private val requestHandler = serverCore.requestHandler

  def agentCard: AgentCard =
    val card = config.toAgentCardAt(url)
    config.grpcPort match
      case Some(port) =>
        card.copy(
          supportedInterfaces = card.supportedInterfaces :+ AgentInterface.grpc(
            grpcUrl(port),
            config.tenant,
          )
        )
      case None =>
        card

  def url: String = config.url

  def start: Task[Unit] =
    A2AServerLifecycle.startOnce(serverScopeRef)(openServerScope)

  private def openServerScope: Task[Scope.Closeable] =
    for
      scope <- Scope.make
      _     <-
        (for
          // zio-http's Server.Config.default caps request bodies at 100 KiB
          // (RequestStreaming.Disabled(1024*100)); A2A messages carry base64
          // file uploads that exceed that, so apply the configured limit or
          // they'd 413. disableRequestStreaming keeps full-body aggregation
          // (the routes parse the whole JSON-RPC body) with the larger cap.
          serverEnv <- (ZLayer.succeed(
                         Server.Config.default
                           .binding(config.host, config.port)
                           .disableRequestStreaming(config.maxRequestBodyBytes)
                       ) >>> Server.live)
            .build(scope)
          _ <- Server.install(a2aRoutes).provideEnvironment(serverEnv)
          _ <- startGrpcServer
        yield ()).catchAllCause(closeStartupScope(scope, _))
    yield scope

  private def closeStartupScope(scope: Scope.Closeable, cause: Cause[Throwable]): Task[Nothing] =
    val failure =
      if cause.isInterruptedOnly then ZIO.failCause(cause)
      else ZIO.fail(cause.squash)
    scope.close(Exit.failCause(cause)).ignore *> failure

  def stop: Task[Unit] =
    runtimeRegistry.interruptAll *>
      stopGrpcServer *>
      A2AServerLifecycle.stopOnce(serverScopeRef)(_.close(Exit.succeed(())))

  private def startGrpcServer: Task[Unit] =
    config.grpcPort match
      case None =>
        ZIO.unit
      case Some(port) =>
        grpcServerRef.modifyZIO {
          case Some(server) =>
            ZIO.succeed(((), Some(server)))
          case None =>
            ZIO
              .attemptBlocking {
                val builder = NettyServerBuilder
                  .forAddress(InetSocketAddress(config.host, port))
                config.grpcTls.foreach { tls =>
                  builder.useTransportSecurity(new File(tls.certChainPath), new File(tls.privateKeyPath))
                }
                builder
                  .addService(
                    A2AGrpcJavaService.serviceDefinition(runtime, agentCard, config.capabilities, requestHandler)
                  )
                  .build()
                  .start()
              }
              .map(server => ((), Some(server)))
        }

  private def stopGrpcServer: UIO[Unit] =
    grpcServerRef
      .modifyZIO {
        case Some(server) =>
          ZIO
            .attemptBlocking {
              server.shutdown()
              if !server.awaitTermination(5, TimeUnit.SECONDS) then server.shutdownNow()
            }
            .orDie
            .as(((), None))
        case None =>
          ZIO.succeed(((), None))
      }

  private def grpcUrl(port: Int): String =
    config.grpcAdvertisedUrl.getOrElse {
      val scheme = if config.grpcTls.isDefined then "https" else "http"
      A2AServerDefaults.url(config.host, port, scheme)
    }

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
    dispatchHttpResponse(httpRequest, agentCard, config.capabilities, requestHandler, config.executionMode)

  private[a2a] def dispatchJsonRpc(request: JsonRpcRequest): Task[JsonRpcResponse] =
    A2AJsonRpcRouting.single(request, ServerCallContext(), requestHandler)

  private def restMethodName(method: Method): String =
    method match
      case Method.GET    => "GET"
      case Method.POST   => "POST"
      case Method.DELETE => "DELETE"
      case other         => other.toString

  private final class ZioHttpA2ARequestView(request: Request) extends A2ALimitedHttpRequestView:
    def maxRequestBodyBytes: Int = config.maxRequestBodyBytes

    def methodName: String = restMethodName(request.method)
    def path: String       = request.path.encode

    def header(name: String): Option[String] =
      request.headers.get(name)

    override def headerEntries: Iterable[(String, String)] =
      request.headers.iterator.map(header => header.headerName -> header.renderedValue).toList

    def queryParam(name: String): Option[String] =
      request.url.queryParams.getAll(name).headOption

    override def queryParams: Iterable[(String, String)] =
      request.url.queryParams.map.toList.flatMap {
        case (name, values) =>
          values.toList.map(name -> _)
      }

    protected def readBodyAfterContentLength(maxBytes: Int): Task[String] =
      val bytes =
        if maxBytes > 0 then
          request.body.asStream.take(maxBytes.toLong + 1L).runCollect.flatMap { chunk =>
            if chunk.length > maxBytes then ZIO.fail(A2AHttpBinding.bodySizeExceeded(maxBytes))
            else ZIO.succeed(chunk)
          }
        else request.body.asChunk
      bytes.map(chunk => String(chunk.toArray, StandardCharsets.UTF_8))
  end ZioHttpA2ARequestView

  protected def emptyResponse(status: Int, headers: List[(String, String)]): Response =
    Response(status = Status.fromInt(status), headers = responseHeaders(headers))

  protected def textResponse(
    body: String,
    status: Int,
    headers: List[(String, String)],
  ): Response =
    Response(
      status = Status.fromInt(status),
      headers = responseHeaders(headers),
      body = Body.fromString(body, StandardCharsets.UTF_8),
    )

  protected def sseResponse(
    wireStream: ZStream[Any, Nothing, String],
    headers: List[(String, String)],
  ): Response =
    val wire =
      wireStream
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
