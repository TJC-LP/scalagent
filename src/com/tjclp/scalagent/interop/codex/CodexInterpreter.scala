package com.tjclp.scalagent.interop.codex

import zio.*
import zio.stream.*
import zio.json.ast.Json
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.codex.{CodexClient, CodexEvent, CodexItem, CodexThreadOptions, SandboxMode}

/** Bridges the core `Agent` trait to the Codex runtime.
  *
  * Translates `ExecutionPolicy` to `CodexThreadOptions`, maps `CodexEvent` to `AgentEvent`,
  * and extracts the final agent message as the string output.
  *
  * Follows the same SharedRun pattern as `ClaudeInterpreter`.
  */
object CodexInterpreter:

  private final case class SharedRun[O](
      events: ZStream[Any, AgentError, AgentEvent],
      result: IO[AgentError, O]
  )

  private enum SharedState[O]:
    case Empty[O]() extends SharedState[O]
    case Initializing(promise: Promise[Nothing, SharedRun[O]])
    case Ready(shared: SharedRun[O])

  /** Create a string-output agent backed by Codex. */
  def string(
      client: CodexClient,
      threadOptions: CodexThreadOptions = CodexThreadOptions.default
  ): Agent[Any, String, String] =
    make(client, threadOptions)

  /** Start building a capability-typed agent backed by Codex.
    *
    * The builder's `codexTransform` wires capability declarations into
    * `CodexThreadOptions`: specifically, tool surface → sandbox mode.
    */
  def builder(
      client: CodexClient,
      threadOptions: CodexThreadOptions = CodexThreadOptions.default
  ): AgentBuilder[Any, String, String, Any] =
    AgentBuilder.withTransform(
      string(client, threadOptions),
      codexTransform(client, threadOptions)
    )

  /** Creates a transform that rebuilds the Codex-backed agent with
    * sandbox mode derived from the tool surface.
    */
  private def codexTransform(
      client: CodexClient,
      baseOptions: CodexThreadOptions
  ): (Agent[Any, String, String], ToolSurface, Int) => Agent[Any, String, String] =
    (_, toolSurface, _) =>
      val sandboxMode =
        if toolSurface.isEmpty then SandboxMode.ReadOnly
        else if toolSurface.isReadOnlyCompatible then SandboxMode.ReadOnly
        else SandboxMode.WorkspaceWrite
      val updatedOptions = baseOptions.copy(sandboxMode = Some(sandboxMode))
      make(client, updatedOptions)

  /** Core factory: create an Agent backed by Codex. */
  private def make(
      client: CodexClient,
      threadOptions: CodexThreadOptions
  ): Agent[Any, String, String] =
    new Agent[Any, String, String]:
      def run(principal: Any, input: String, policy: ExecutionPolicy): AgentRun[Any, String] =
        val effectiveOptions = overlayPolicy(threadOptions, policy)
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
                  _ <- ZIO.succeed { state = SharedState.Initializing(promise) }
                  shared <- buildSharedRun(client, input, effectiveOptions, policy)
                  _ <- promise.succeed(shared)
                  _ <- ZIO.succeed { state = SharedState.Ready(shared) }
                yield shared
          }

        AgentRun(
          events = ZStream.unwrap(getShared.map(_.events)),
          result = getShared.flatMap(_.result)
        )

  private def buildSharedRun(
      client: CodexClient,
      input: String,
      threadOptions: CodexThreadOptions,
      policy: ExecutionPolicy
  ): UIO[SharedRun[String]] =
    for
      queue <- Queue.unbounded[Take[AgentError, AgentEvent]]
      resultPromise <- Promise.make[AgentError, String]
      mapperState = CodexEventMapper.createState()
      thread = client.startThread(threadOptions)
      eventStream = thread.runStreamed(input)
        .mapError(t => AgentError.Unknown(s"Codex stream error: ${t.getMessage}", Some(t)))
      runner = applyDeadline(
        eventStream.runForeach { codexEvent =>
          val agentEvents = CodexEventMapper.mapEvent(codexEvent, mapperState)
          ZIO.foreachDiscard(agentEvents) { event =>
            queue.offer(Take.single(event)).unit *>
              (event match
                case AgentEvent.Completed(summary) =>
                  summary.resultText match
                    case Some(text) => resultPromise.succeed(text).ignore
                    case None => resultPromise.fail(AgentError.Unknown("Codex completed with no result text")).ignore
                case _ => ZIO.unit)
          }
        },
        policy
      ).either.flatMap {
        case Left(error) =>
          resultPromise.fail(error).ignore *> queue.offer(Take.fail(error)).unit
        case Right(_) =>
          resultPromise
            .fail(AgentError.Unknown("Codex stream completed with no final result"))
            .ignore *> queue.offer(Take.end).unit
      }
      _ <- runner.forkDaemon
    yield SharedRun(
      events = ZStream.fromQueue(queue).flattenTake,
      result = resultPromise.await
    )

  /** Overlay ExecutionPolicy onto CodexThreadOptions. */
  private def overlayPolicy(
      base: CodexThreadOptions,
      policy: ExecutionPolicy
  ): CodexThreadOptions =
    var opts = base
    // Codex doesn't have budget enforcement, but we honor sandbox defaults
    // maxTurns not applicable (Codex is per-turn)
    // deadline is handled via timeoutFail at the stream level
    opts

  /** Apply deadline enforcement. */
  private def applyDeadline[A](
      effect: IO[AgentError, A],
      policy: ExecutionPolicy
  ): IO[AgentError, A] =
    policy.deadline match
      case Some(duration) =>
        effect.timeoutFail(AgentError.Interrupted(s"Deadline exceeded: $duration"))(duration)
      case None => effect
