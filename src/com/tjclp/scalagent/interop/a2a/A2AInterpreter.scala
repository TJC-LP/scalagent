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

  private final case class SharedRun[O](
      events: ZStream[Any, AgentError, AgentEvent],
      result: IO[AgentError, O]
  )

  private enum SharedState[O]:
    case Empty[O]() extends SharedState[O]
    case Initializing(promise: Promise[Nothing, SharedRun[O]])
    case Ready(shared: SharedRun[O])

  /** Create an A2A remote agent from a client. */
  def fromClient(client: A2AClient): IO[Throwable, A2ARemoteAgent[Any, String, String]] =
    client.agentCard.map { agentCard =>
      new A2ARemoteAgent[Any, String, String]:
        val card: AgentCard = agentCard

        def run(principal: Any, input: String, policy: ExecutionPolicy): AgentRun[Any, String] =
          val message = A2AMessage.userText(input, None)
          var state: SharedState[String] = SharedState.Empty()

          def getShared: UIO[SharedRun[String]] =
            ZIO.suspendSucceed {
              state match
                case SharedState.Ready(shared) =>
                  ZIO.succeed(shared)
                case SharedState.Initializing(promise) =>
                  promise.await
                case SharedState.Empty() =>
                  for
                    promise <- Promise.make[Nothing, SharedRun[String]]
                    _ <- ZIO.succeed {
                      state = SharedState.Initializing(promise)
                    }
                    shared <- buildSharedRun(client.stream(message, None), policy)
                    _ <- promise.succeed(shared)
                    _ <- ZIO.succeed {
                      state = SharedState.Ready(shared)
                    }
                  yield shared
            }

          AgentRun(
            events = ZStream.unwrap(getShared.map(_.events)),
            result = getShared.flatMap(_.result)
          )
    }

  /** Discover a remote A2A agent by URL. */
  def discover(agentUrl: String): IO[Throwable, A2ARemoteAgent[Any, String, String]] =
    for
      client <- A2AClient.discover(agentUrl)
      agent  <- fromClient(client)
    yield agent

  private def buildSharedRun(
      stream: ZStream[Any, Throwable, A2AResponse.StreamEvent],
      policy: ExecutionPolicy
  ): UIO[SharedRun[String]] =
    for
      queue <- Queue.unbounded[Take[AgentError, AgentEvent]]
      resultPromise <- Promise.make[AgentError, String]
      normalized = stream
        .mapError(t => AgentError.Unknown(s"A2A stream error: ${t.getMessage}", Some(t)))
        .flatMap(event => ZStream.fromIterable(A2AEventMapper.mapStreamEvent(event)))
      runner = applyDeadline(
        normalized.runForeach { event =>
          queue.offer(Take.single(event)).unit *>
            (event match
              case AgentEvent.Completed(summary) =>
                summary.resultText match
                  case Some(text) => resultPromise.succeed(text).ignore
                  case None       => resultPromise.fail(AgentError.Unknown("A2A completed with no result text")).ignore
              case _ =>
                ZIO.unit)
        },
        policy
      ).either.flatMap {
        case Left(error) =>
          resultPromise.fail(error).ignore *> queue.offer(Take.fail(error)).unit
        case Right(_) =>
          resultPromise
            .fail(AgentError.Unknown("A2A stream completed with no final result"))
            .ignore *> queue.offer(Take.end).unit
      }
      _ <- runner.forkDaemon
    yield SharedRun(
      events = ZStream.fromQueue(queue).flattenTake,
      result = resultPromise.await
    )

  private def applyDeadline[A](
      effect: IO[AgentError, A],
      policy: ExecutionPolicy
  ): IO[AgentError, A] =
    policy.deadline match
      case Some(duration) =>
        effect.timeoutFail(AgentError.Interrupted(s"Deadline exceeded: $duration"))(duration)
      case None =>
        effect
