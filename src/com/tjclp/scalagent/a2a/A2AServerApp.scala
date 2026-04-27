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

  def agentOptions: AgentOptions                                                  = AgentOptions.default
  def skills: List[AgentSkill]                                                    = Nil
  def sessionLogDir: Option[String]                                               = None
  def invocationPreparer: Option[(A2AMessage, TaskId) => Task[InvocationContext]] = None

  final def config: A2AServer.Config =
    A2AServer.Config(
      name = name,
      description = description,
      host = host,
      port = port,
      agentOptions = agentOptions,
      skills = skills,
      sessionLogDir = sessionLogDir,
      invocationPreparer = invocationPreparer,
    )

  def onStarted(server: A2AServer): UIO[Unit] = ZIO.unit

  override final def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      ZIO.runtime[Any].flatMap { runtime =>
        ZIO
          .acquireRelease(A2AServer.start(config, runtime))(_.stop.ignore)
          .flatMap(server => onStarted(server) *> ZIO.never)
      }
    }

end A2AServerApp
