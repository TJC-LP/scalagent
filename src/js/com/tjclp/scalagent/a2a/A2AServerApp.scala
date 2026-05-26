package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.config.AgentOptions
import zio.*

/**
 * Declarative entry point for running a dedicated A2A server.
 *
 * Extend this trait on a top-level `object` when the process is just one
 * A2A server. It mirrors fast-mcp-scala's `McpServerApp` shape: user code
 * declares server metadata and policy, while the trait owns config assembly,
 * startup, and shutdown.
 *
 * {{{
 * object Researcher extends A2AServerApp[Researcher.type]:
 *   override def description: String = "Answers research questions"
 *   override def agentOptions: AgentOptions =
 *     AgentOptions.default.withSystemPrompt("You are a careful researcher.")
 * }}}
 */
trait A2AServerApp[Self <: Singleton] extends ZIOAppDefault:

  def name: String = getClass.getSimpleName.stripSuffix("$")
  def description: String

  def host: String =
    sys.env.get("A2A_HOST").orElse(sys.env.get("SERVICE_HOST")).getOrElse("localhost")

  def port: Int =
    sys.env
      .get("A2A_PORT")
      .orElse(sys.env.get("SERVICE_PORT"))
      .flatMap(_.toIntOption)
      .getOrElse(3000)

  def agentOptions: AgentOptions = AgentOptions.default

  /**
   * Effectful hook for servers that need startup-time runtime discovery
   * before constructing their agent options. Simple servers can keep
   * overriding [[agentOptions]].
   */
  def agentOptionsZIO: Task[AgentOptions] = ZIO.succeed(agentOptions)

  def skills: List[AgentSkill]                                                    = Nil
  def executionMode: ExecutionMode                                                = ExecutionMode.Default
  def taskTimeout: Option[Duration]                                               = None
  def capabilities: AgentCapabilities                                             = AgentCapabilities.default
  def sessionLogDir: Option[String]                                               = None
  def invocationPreparer: Option[(A2AMessage, TaskId) => Task[InvocationContext]] = None
  def executionOverride: Option[(A2AMessage, TaskId, ContextId, A2AEventPublisher) => Task[Unit]] = None
  def eventStore: Option[A2AEventStore] = None
  def replayProvider: Option[A2AReplayProvider] = None

  /**
   * Plug a durable task store. When `None` (default) tasks live in a
   * process-local map and disappear when the host scales to zero — fine
   * for local dev, broken for Modal `@web_server` containers.
   */
  def taskStore: Option[A2ATaskStore] = None

  /**
   * Effectful hook for stores that need runtime discovery (env vars,
   * external clients) before construction. Simple servers stay on
   * [[taskStore]]; the deploy-bound `GeneratedA2aServer` overrides this
   * to wire its Modal-Dict-backed store.
   */
  def taskStoreZIO: Task[Option[A2ATaskStore]] = ZIO.succeed(taskStore)

  protected final def configWith(options: AgentOptions, store: Option[A2ATaskStore]): A2AServerLive.Config =
    A2AServerLive.Config(
      name = name,
      description = description,
      host = host,
      port = port,
      agentOptions = options,
      executionMode = executionMode,
      taskTimeout = taskTimeout,
      capabilities = capabilities,
      skills = skills,
      sessionLogDir = sessionLogDir,
      invocationPreparer = invocationPreparer,
      executionOverride = executionOverride,
      taskStore = store,
      eventStore = eventStore,
      replayProvider = replayProvider,
    )

  final def config: A2AServerLive.Config =
    configWith(agentOptions, taskStore)

  final def configZIO: Task[A2AServerLive.Config] =
    for
      options <- agentOptionsZIO
      store   <- taskStoreZIO
    yield configWith(options, store)

  def onStarted(server: A2AServer): UIO[Unit] = ZIO.unit

  override final def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      ZIO.runtime[Any].flatMap { runtime =>
        configZIO
          .flatMap(config => ZIO.acquireRelease(A2AServerLive.start(config, runtime))(_.stop.ignore))
          .flatMap(server => onStarted(server) *> ZIO.never)
      }
    }

end A2AServerApp
