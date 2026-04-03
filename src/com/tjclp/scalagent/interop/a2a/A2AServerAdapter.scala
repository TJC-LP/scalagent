package com.tjclp.scalagent.interop.a2a

import zio.*
import zio.stream.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.core.a2a.A2AEndpoint
import com.tjclp.scalagent.a2a.*
import com.tjclp.scalagent.errors.AgentError

/** Exposes any `Agent[Any, String, O]` as an A2A endpoint.
  *
  * The adapter bridges incoming A2A messages to `Agent.run()` and maps
  * the resulting `AgentEvent` stream back to A2A protocol events.
  *
  * Uses the existing `A2AServer` infrastructure for HTTP/JSON-RPC handling,
  * injecting a custom agent executor that delegates to the DSL agent.
  */
object A2AServerAdapter:

  /** Configuration for exposing an agent as A2A. */
  final case class Config(
      name: String,
      description: String,
      host: String = "localhost",
      port: Int = 3000,
      skills: List[AgentSkill] = Nil
  ):
    def toAgentCard: AgentCard =
      AgentCard(
        name = name,
        description = description,
        url = s"http://$host:$port",
        capabilities = AgentCapabilities(streaming = true),
        skills = skills
      )

  /** Create an A2A endpoint from any string-input agent.
    *
    * The returned `A2AEndpoint` can be started to accept incoming
    * A2A requests and delegate them to the provided agent.
    *
    * Uses the existing `A2AServer` for HTTP/JSON-RPC plumbing,
    * configured with `AgentOptions` that run the provided agent.
    */
  def expose(
      agent: Agent[Any, String, ?],
      config: Config,
      agentOptions: com.tjclp.scalagent.config.AgentOptions =
        com.tjclp.scalagent.config.AgentOptions.default
  ): ZIO[Scope, Throwable, A2AEndpoint] =
    val serverConfig = A2AServer.Config(
      name = config.name,
      description = config.description,
      host = config.host,
      port = config.port,
      agentOptions = agentOptions,
      skills = config.skills
    )
    A2AServer.create(serverConfig).map { server =>
      new A2AEndpoint:
        def start: Task[Unit] = ZIO.unit // Already started by A2AServer.create
        def stop: Task[Unit] = server.stop
        def url: String = server.url
        def card: AgentCard = server.agentCard
    }

  /** Map an agent's event stream to A2A status messages.
    *
    * Useful for custom A2A server implementations that want to
    * publish agent progress as A2A task status updates.
    */
  def eventsToA2AMessages(
      events: ZStream[Any, AgentError, AgentEvent]
  ): ZStream[Any, AgentError, A2AMessage] =
    events.flatMap { event =>
      A2AEventMapper.toA2AMessage(event) match
        case Some(msg) => ZStream.succeed(msg)
        case None      => ZStream.empty
    }
