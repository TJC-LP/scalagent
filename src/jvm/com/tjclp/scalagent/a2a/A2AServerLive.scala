package com.tjclp.scalagent.a2a

import scala.collection.concurrent.TrieMap

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** JVM-side server configuration. Mirror of the JS `A2AServerLive.Config`
  * minus Claude-Agent-SDK-specific fields (`agentOptions`,
  * `invocationPreparer`) since the JVM JVM scalagent build doesn't
  * include the Claude Agent SDK adapters.
  *
  * For CMA-backed agents (Phase 2b echobot), the only fields a typical
  * user needs are: `name`, `description`, `port`, `executionOverride`,
  * `skills`, and optionally `taskStore` / `eventStore`. */
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
      pushNotificationUrlPolicy: PushNotificationUrlPolicy = PushNotificationUrlPolicy.externalOnly,
  ):
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

/** Minimal JVM A2A server runtime using zio-http.
  *
  * Implements the synchronous happy path for CMA-backed agents:
  * `message/send` (executes via `config.executionOverride`), `tasks/get`,
  * `tasks/list`, `tasks/cancel`, `agent/getExtendedAgentCard`, and the
  * GET `/.well-known/agent-card.json` REST endpoint.
  *
  * Out of scope for this minimal impl:
  * - `message/subscribe` SSE streaming (event publisher buffers events
  *   into a list; subscribe just replays them after completion)
  * - `tasks/resubscribe`
  * - Push notification config CRUD
  * - Per-task event store integration beyond in-memory buffering
  *
  * These can be ported from the Bun A2AServerLive incrementally in
  * later phases as JVM CMA agents need them. */
private final class A2AServerLiveImpl(
    config: A2AServerLive.Config,
    runtime: Runtime[Any],
) extends A2AServer:

  private val taskStoreEff: A2ATaskStore =
    config.taskStore.getOrElse(A2ATaskStore.inMemory)

  private val serverFiberRef: zio.Ref.Synchronized[Option[Fiber.Runtime[Throwable, Unit]]] =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(zio.Ref.Synchronized.make(Option.empty[Fiber.Runtime[Throwable, Unit]])).getOrThrow()
    }

  /** Per-request collected events; in-memory until the request completes
    * and we either return the final task or replay them via SSE. */
  private final class CollectingPublisher extends A2AEventPublisher:
    private val events = TrieMap.empty[Long, A2AResponse.StreamEvent]
    private val counter = new java.util.concurrent.atomic.AtomicLong(0L)
    private val done = new java.util.concurrent.atomic.AtomicBoolean(false)

    def publish(event: A2AResponse.StreamEvent): UIO[Unit] =
      ZIO.succeed { events.put(counter.incrementAndGet(), event); () }

    def finish: UIO[Unit] = ZIO.succeed { done.set(true); () }

    def snapshot: List[A2AResponse.StreamEvent] =
      events.toList.sortBy(_._1).map(_._2)
  end CollectingPublisher

  def agentCard: AgentCard = config.toAgentCard

  def url: String = config.url

  def start: Task[Unit] =
    val server = Server.serve(a2aRoutes).provide(
      ZLayer.succeed(Server.Config.default.binding(config.host, config.port)),
      Server.live,
    )
    for
      fiber <- server.fork
      _     <- serverFiberRef.set(Some(fiber))
    yield ()

  def stop: Task[Unit] =
    serverFiberRef.modifyZIO {
      case Some(fiber) => fiber.interrupt.unit.as(((), None))
      case None        => ZIO.succeed(((), None))
    }

  private def a2aRoutes: Routes[Any, Response] =
    Routes(
      Method.POST / Root -> handler { (request: Request) =>
        for
          body     <- request.body.asString.mapError(e => buildErrorResponse(None, A2AError.invalidParams(e.getMessage)))
          rpcEither = body.fromJson[JsonRpcRequest]
          response <- rpcEither match
            case Left(msg) => ZIO.succeed(buildErrorResponse(None, A2AError.parseError(msg)))
            case Right(req) =>
              dispatchJsonRpc(req).map(rpc => Response.json(rpc.toJson)).catchAll { err =>
                ZIO.succeed(buildErrorResponse(req.id, toA2AError(err)))
              }
        yield response
      },
      Method.GET / ".well-known" / "agent-card.json" -> handler { (_: Request) =>
        ZIO.succeed(Response.json(config.toAgentCard.toJson))
      },
    )

  private def dispatchJsonRpc(request: JsonRpcRequest): Task[JsonRpcResponse] =
    request.method match
      case A2AMethod.MessageSend =>
        paramsAs[A2ARequest.MessageSend](request).flatMap(handleSendMessage).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksGet =>
        paramsAs[A2ARequest.TasksGet](request).flatMap(handleGetTask).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksList =>
        paramsAs[A2ARequest.TasksList](request).flatMap(handleListTasks).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.GetAuthenticatedExtendedCard =>
        ZIO.succeed(JsonRpcResponse.success(request.id, config.toAgentCard.toJsonAST.getOrElse(Json.Obj())))
      case other =>
        ZIO.fail(A2AError.methodNotFound(other))

  private def handleSendMessage(req: A2ARequest.MessageSend): Task[A2AResponse.SendMessageResult] =
    val message                = req.message
    val taskId                 = A2AEventIds.taskIdFor(message)
    val ctxId                  = A2AEventIds.contextIdFor(message)
    val tenant: Option[String] = None
    config.executionOverride match
      case Some(executor) =>
        val publisher = new CollectingPublisher
        val initial = A2ATask(
          id = taskId,
          contextId = ctxId,
          status = TaskStatus.working(),
          history = List(message),
        )
        val failureMessage = (err: Throwable) =>
          A2AMessage(
            role = A2ARole.Agent,
            parts = List(Part.Text(Option(err.getMessage).getOrElse(err.getClass.getName))),
          )
        for
          _ <- taskStoreEff.save(initial, tenant)
          _ <- executor(message, taskId, ctxId, publisher).catchAll(e =>
                 publisher.publish(
                   A2AResponse.StreamEvent.TaskStatusUpdate(
                     taskId,
                     ctxId,
                     TaskStatus.failed(failureMessage(e)),
                     `final` = true,
                   ),
                 ),
               )
          _      <- publisher.finish
          events  = publisher.snapshot
          finalTask: A2ATask = events.foldLeft(initial) { (t, ev) =>
                                ev match
                                  case A2AResponse.StreamEvent.TaskMessage(_, _, m) =>
                                    t.copy(history = t.history :+ m)
                                  case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, s, _, _) =>
                                    t.copy(status = s)
                                  case A2AResponse.StreamEvent.TaskArtifactUpdate(_, _, a, _, _, _) =>
                                    t.copy(artifacts = t.artifacts :+ a)
                                  case _ => t
                              }
          _ <- taskStoreEff.save(finalTask, tenant)
        yield A2AResponse.SendMessageResult.TaskResult(finalTask)
      case None =>
        ZIO.fail(
          A2AError.invalidRequest("This JVM A2AServerLive requires `executionOverride` to be configured"),
        )

  private def handleGetTask(req: A2ARequest.TasksGet): Task[A2ATask] =
    val tenant: Option[String] = None
    taskStoreEff.load(req.id, tenant).flatMap {
      case Some(task) => ZIO.succeed(A2ATaskStore.applyHistoryLength(task, req.historyLength))
      case None       => ZIO.fail(A2AError.taskNotFound(req.id))
    }

  private def handleListTasks(req: A2ARequest.TasksList): Task[A2AResponse.ListTasksResult] =
    val tenant: Option[String] = None
    taskStoreEff.list(req, tenant)

  private def paramsAs[A: JsonDecoder](request: JsonRpcRequest): Task[A] =
    ZIO.fromEither(
      request.params
        .toRight(A2AError.invalidParams("Missing params"))
        .flatMap(_.as[A].left.map(A2AError.invalidParams)),
    )

  private def buildErrorResponse(id: Option[JsonRpcId], error: A2AError): Response =
    Response.json(JsonRpcResponse.fromA2AError(id, error).toJson)

  private def toA2AError(e: Throwable): A2AError = e match
    case a: A2AError => a
    case other       => A2AError.internalError(Option(other.getMessage).getOrElse(other.getClass.getName))
end A2AServerLiveImpl
