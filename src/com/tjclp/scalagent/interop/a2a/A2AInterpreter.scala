package com.tjclp.scalagent.interop.a2a

import zio.*
import zio.stream.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.core.a2a.A2ARemoteAgent
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.a2a.*

/** Wraps an `A2AClient` as an `A2ARemoteAgent` — a remote A2A agent
  * that implements the core `Agent` trait.
  *
  * Callers can use this anywhere an `Agent` is expected: as a delegation
  * target, in `AgentBuilder`, or called directly via `.run()`.
  */
object A2AInterpreter:

  /** Create an A2A remote agent from a client. */
  def fromClient(client: A2AClient): IO[Throwable, A2ARemoteAgent[Any, String, String]] =
    client.agentCard.map { agentCard =>
      new A2ARemoteAgent[Any, String, String]:
        val card: AgentCard = agentCard

        def run(principal: Any, input: String, policy: ExecutionPolicy): AgentRun[Any, String] =
          val message = A2AMessage.userText(input, None)
          val a2aStream = client.stream(message, None)

          val setup: ZIO[Scope, AgentError, (ZStream[Any, AgentError, AgentEvent], IO[AgentError, String])] =
            for
              resultPromise <- Promise.make[AgentError, String]
              eventStream = a2aStream
                .mapError(t => AgentError.Unknown(s"A2A stream error: ${t.getMessage}", Some(t)))
                .flatMap(event => ZStream.fromIterable(A2AEventMapper.mapStreamEvent(event)))
                .tap {
                  case AgentEvent.Completed(summary) =>
                    summary.resultText match
                      case Some(text) => resultPromise.succeed(text)
                      case None       => resultPromise.fail(AgentError.Unknown("A2A completed with no result text"))
                  case _ => ZIO.unit
                }
              resultEffect = resultPromise.await
            yield (eventStream, resultEffect)

          AgentRun(
            events = ZStream.unwrapScoped(setup.map(_._1)),
            result = ZIO.scoped(setup.flatMap { (stream, extract) =>
              stream.runDrain *> extract
            })
          )
    }

  /** Discover a remote A2A agent by URL. */
  def discover(agentUrl: String): IO[Throwable, A2ARemoteAgent[Any, String, String]] =
    for
      client <- A2AClient.discover(agentUrl)
      agent  <- fromClient(client)
    yield agent
