package com.tjclp.scalagent.interop.a2a

import zio.*
import zio.stream.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.core.a2a.A2ARemoteAgent
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.a2a.*

/**
 * Wraps an `A2AClient` as an `A2ARemoteAgent` — a remote A2A agent
 * that implements the core `Agent` trait.
 *
 * Callers can use this anywhere an `Agent` is expected: as a delegation
 * target, in `AgentBuilder`, or called directly via `.run()`.
 */
object A2AInterpreter:

  private final case class SharedRun[O](
    events: ZStream[Any, AgentError, AgentEvent],
    result: IO[AgentError, O])

  private enum SharedState[O]:
    case Empty[O]() extends SharedState[O]
    case Initializing(promise: Promise[Nothing, SharedRun[O]])
    case Ready(shared: SharedRun[O])

  /** Create an A2A remote agent from a client. */
  def fromClient(client: A2AClient): IO[Throwable, A2ARemoteAgent[Any, String, String]] =
    client.agentCard.map { agentCard =>
      new A2ARemoteAgent[Any, String, String]:
        val card: AgentCard = agentCard

        def run(
          principal: Any,
          input: String,
          policy: ExecutionPolicy,
        ): AgentRun[Any, String] =
          val message                            = A2AMessage.userText(input, None)
          val stateRef: Ref[SharedState[String]] = Unsafe.unsafe { implicit u =>
            Ref.unsafe.make[SharedState[String]](SharedState.Empty())
          }

          def getShared: URIO[Scope, SharedRun[String]] =
            Promise.make[Nothing, SharedRun[String]].flatMap { myPromise =>
              stateRef.modify {
                case SharedState.Ready(shared) =>
                  (ZIO.succeed(shared), SharedState.Ready(shared))
                case SharedState.Initializing(existing) =>
                  (existing.await, SharedState.Initializing(existing))
                case SharedState.Empty() =>
                  val init = buildSharedRun(client.stream(message, None), policy).flatMap { shared =>
                    myPromise.succeed(shared) *> stateRef.set(SharedState.Ready(shared)).as(shared)
                  }
                  (init, SharedState.Initializing(myPromise))
              }.flatten
            }

          AgentRun(
            events = ZStream.unwrap(getShared.map(_.events)),
            result = getShared.flatMap(_.result),
          )
        end run
    }

  /** Discover a remote A2A agent by URL. */
  def discover(agentUrl: String): IO[Throwable, A2ARemoteAgent[Any, String, String]] =
    for
      client <- A2AClient.discover(agentUrl)
      agent  <- fromClient(client)
    yield agent

  private def buildSharedRun(
    stream: ZStream[Any, Throwable, A2AResponse.StreamEvent],
    policy: ExecutionPolicy,
  ): URIO[Scope, SharedRun[String]] =
    for
      queue         <- Queue.unbounded[Take[AgentError, AgentEvent]]
      resultPromise <- Promise.make[AgentError, String]
      cancelledError = AgentError.Interrupted("Agent run scope closed")
      normalized     = stream
        .mapError(t => AgentError.Unknown(s"A2A stream error: ${t.getMessage}", Some(t)))
        .flatMap(event => ZStream.fromIterable(A2AEventMapper.mapStreamEvent(event)))
      runner = applyDeadline(
        normalized.runForeach { event =>
          queue.offer(Take.single(event)).unit *>
            (event match
              case AgentEvent.Completed(summary) if summary.isSuccess =>
                summary.resultText match
                  case Some(text) => resultPromise.succeed(text).ignore
                  case None       => resultPromise.fail(AgentError.Unknown("A2A completed with no result text")).ignore
              case AgentEvent.Completed(summary) =>
                val reason = summary.resultText.getOrElse(summary.stopReason.getOrElse("unknown"))
                resultPromise.fail(AgentError.Unknown(s"A2A task failed: $reason")).ignore
              case _ =>
                ZIO.unit)
        },
        policy,
      ).either
        .flatMap {
          case Left(error) =>
            resultPromise.fail(error).ignore *> queue.offer(Take.fail(error)).unit
          case Right(_) =>
            resultPromise
              .fail(AgentError.Unknown("A2A stream completed with no final result"))
              .ignore *> queue.offer(Take.end).unit
        }
        .onInterrupt(
          resultPromise.fail(cancelledError).ignore *>
            queue.offer(Take.fail(cancelledError)).ignore
        )
      _ <- runner.forkScoped
    yield SharedRun(
      events = ZStream.fromQueue(queue).flattenTake,
      result = resultPromise.await,
    )

  private def applyDeadline[A](
    effect: IO[AgentError, A],
    policy: ExecutionPolicy,
  ): IO[AgentError, A] =
    policy.deadline match
      case Some(duration) =>
        effect.timeoutFail(AgentError.Interrupted(s"Deadline exceeded: $duration"))(duration)
      case None =>
        effect
end A2AInterpreter
