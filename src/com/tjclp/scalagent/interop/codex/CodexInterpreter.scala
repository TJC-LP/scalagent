package com.tjclp.scalagent.interop.codex

import zio.*
import zio.stream.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.core.mcp.McpToolSurface
import com.tjclp.scalagent.config.StructuredOutput
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.codex.{
  CodexClient,
  CodexEvent,
  CodexInput,
  CodexThreadOptions,
  CodexTurnOptions,
  SandboxMode,
}

/**
 * Bridges the core `Agent` trait to the Codex runtime.
 *
 * Translates `ExecutionPolicy` to `CodexThreadOptions`, maps `CodexEvent` to `AgentEvent`,
 * and extracts typed output from the final agent message.
 *
 * Follows the same SharedRun pattern as `ClaudeInterpreter`.
 */
object CodexInterpreter:

  private final case class SharedRun[O](
    events: ZStream[Any, AgentError, AgentEvent],
    result: IO[AgentError, O])

  private enum SharedState[O]:
    case Empty[O]() extends SharedState[O]
    case Initializing(promise: Promise[Nothing, SharedRun[O]])
    case Ready(shared: SharedRun[O])

  /**
   * Create a string-output agent backed by Codex.
   *
   * Input type is `CodexInput` (`String | Seq[CodexInputItem]`) — callers
   * can pass plain strings or multimodal input items.
   */
  def string(
    client: CodexClient,
    threadOptions: CodexThreadOptions = CodexThreadOptions.default,
  ): Agent[Any, CodexInput, String] =
    make[String](client, threadOptions)

  /** Create a structured-output agent backed by Codex. */
  def typed[A](
    client: CodexClient,
    threadOptions: CodexThreadOptions = CodexThreadOptions.default,
  )(using StructuredOutput[A]
  ): Agent[Any, CodexInput, A] =
    make[A](client, threadOptions)

  /**
   * Start building a capability-typed string agent backed by Codex.
   *
   * Codex thread options can control sandbox, approval, web search, and related execution
   * settings, but the upstream SDK does not expose Claude-style per-tool allowlists. The
   * builder therefore maps declared tool surfaces to sandbox posture only.
   */
  def builder(
    client: CodexClient,
    threadOptions: CodexThreadOptions = CodexThreadOptions.default,
  ): AgentBuilder[Any, CodexInput, String, Any] =
    AgentBuilder.withTransform(
      string(client, threadOptions),
      codexTransform[String](client, threadOptions),
    )

  /**
   * Start building a Codex-backed agent with an explicit sandbox mode.
   *
   * Prefer this when you want Codex behavior to follow the chosen sandbox directly
   * instead of inferring it from a compatibility tool surface.
   */
  def sandboxedBuilder(
    client: CodexClient,
    sandboxMode: SandboxMode,
    threadOptions: CodexThreadOptions = CodexThreadOptions.default,
  ): AgentBuilder[Any, CodexInput, String, Any] =
    builder(client, threadOptions.copy(sandboxMode = Some(sandboxMode)))

  /** Start building a capability-typed structured-output agent backed by Codex. */
  def typedBuilder[A](
    client: CodexClient,
    threadOptions: CodexThreadOptions = CodexThreadOptions.default,
  )(using so: StructuredOutput[A]
  ): AgentBuilder[Any, CodexInput, A, Any] =
    AgentBuilder.withTransform(
      typed[A](client, threadOptions),
      codexTransform[A](client, threadOptions),
    )

  /** Start building a typed Codex-backed agent with an explicit sandbox mode. */
  def typedSandboxedBuilder[A](
    client: CodexClient,
    sandboxMode: SandboxMode,
    threadOptions: CodexThreadOptions = CodexThreadOptions.default,
  )(using so: StructuredOutput[A]
  ): AgentBuilder[Any, CodexInput, A, Any] =
    typedBuilder[A](client, threadOptions.copy(sandboxMode = Some(sandboxMode)))

  /**
   * Creates a transform that rebuilds the Codex-backed agent with sandbox mode
   * derived from the declared capability surface.
   */
  private def codexTransform[O](
    client: CodexClient,
    baseOptions: CodexThreadOptions,
  )(using codec: OutputCodec[O]
  ): (Agent[Any, CodexInput, O], BuilderConfig) => Agent[Any, CodexInput, O] =
    (_, cfg) =>
      require(
        cfg.tools.tools.isEmpty,
        "CodexInterpreter does not support registering custom ToolDefs; only sandbox posture is configurable",
      )
      require(
        cfg.mcpToolSurfaces.isEmpty,
        "CodexInterpreter does not support MCP tool registration; the upstream Codex SDK does not expose it",
      )

      val inferredSandboxMode =
        if cfg.fullToolAccess then SandboxMode.FullAccess
        else if cfg.tools.isEmpty || cfg.tools.isReadOnlyCompatible then SandboxMode.ReadOnly
        else SandboxMode.WorkspaceWrite

      val opts = baseOptions.copy(
        sandboxMode = Some(baseOptions.sandboxMode.getOrElse(inferredSandboxMode))
      )

      val withDir = cfg.directoryScope.fold(opts) { scope =>
        opts.copy(
          workingDirectory = Some(scope.cwd),
          additionalDirectories = scope.additionalDirectories,
        )
      }

      make[O](client, withDir)

  /** Core factory: create an Agent backed by Codex. */
  private def make[O](
    client: CodexClient,
    threadOptions: CodexThreadOptions,
  )(using codec: OutputCodec[O]
  ): Agent[Any, CodexInput, O] =
    new Agent[Any, CodexInput, O]:
      def run(
        principal: Any,
        input: CodexInput,
        policy: ExecutionPolicy,
      ): AgentRun[Any, O] =
        val effectiveOptions = overlayPolicy(threadOptions, policy)
        val turnOptions      = codec.structuredOutputFormat match
          case Some(so) => CodexTurnOptions(outputSchema = Some(so.jsonSchema))
          case None     => CodexTurnOptions.default

        val stateRef: Ref[SharedState[O]] = Unsafe.unsafe { implicit u =>
          Ref.unsafe.make[SharedState[O]](SharedState.Empty())
        }

        def getShared: URIO[Scope, SharedRun[O]] =
          Promise.make[Nothing, SharedRun[O]].flatMap { myPromise =>
            stateRef.modify {
              case SharedState.Ready(shared) =>
                (ZIO.succeed(shared), SharedState.Ready(shared))
              case SharedState.Initializing(existing) =>
                (existing.await, SharedState.Initializing(existing))
              case SharedState.Empty() =>
                val init = buildSharedRun(client, input, effectiveOptions, turnOptions, policy, codec).flatMap {
                  shared => myPromise.succeed(shared) *> stateRef.set(SharedState.Ready(shared)).as(shared)
                }
                (init, SharedState.Initializing(myPromise))
            }.flatten
          }

        AgentRun(
          events = ZStream.unwrap(getShared.map(_.events)),
          result = getShared.flatMap(_.result),
        )
      end run

  private def buildSharedRun[O](
    client: CodexClient,
    input: CodexInput,
    threadOptions: CodexThreadOptions,
    turnOptions: CodexTurnOptions,
    policy: ExecutionPolicy,
    codec: OutputCodec[O],
  ): URIO[Scope, SharedRun[O]] =
    for
      queue         <- Queue.unbounded[Take[AgentError, AgentEvent]]
      resultPromise <- Promise.make[AgentError, O]
      cancelledError = AgentError.Interrupted("Agent run scope closed")
      mapperState    = CodexEventMapper.createState()
      thread         = client.startThread(threadOptions)
      eventStream    = thread
        .runStreamed(input, turnOptions)
        .mapError(t => AgentError.Unknown(s"Codex stream error: ${t.getMessage}", Some(t)))
      runner = applyDeadline(
        eventStream.runForeach { codexEvent =>
          val agentEvents = CodexEventMapper.mapEvent(codexEvent, mapperState)
          ZIO.foreachDiscard(agentEvents) { event =>
            queue.offer(Take.single(event)).unit *>
              (event match
                case AgentEvent.Completed(summary) if summary.isSuccess =>
                  summary.resultText match
                    case Some(text) =>
                      decodeOutput(text, codec).foldZIO(
                        error => resultPromise.fail(error).ignore,
                        output => resultPromise.succeed(output).ignore,
                      )
                    case None =>
                      resultPromise.fail(AgentError.Unknown("Codex completed with no result text")).ignore
                case AgentEvent.Completed(summary) =>
                  val reason = summary.resultText.getOrElse(summary.stopReason.getOrElse("unknown"))
                  resultPromise.fail(AgentError.Unknown(s"Codex turn failed: $reason")).ignore
                case _ => ZIO.unit)
          }
        },
        policy,
      ).either
        .flatMap {
          case Left(error) =>
            resultPromise.fail(error).ignore *> queue.offer(Take.fail(error)).unit
          case Right(_) =>
            resultPromise
              .fail(AgentError.Unknown("Codex stream completed with no final result"))
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

  private def decodeOutput[O](
    resultText: String,
    codec: OutputCodec[O],
  ): IO[AgentError, O] =
    val structuredOutput =
      codec.structuredOutputFormat match
        case Some(_) =>
          Json.decoder.decodeJson(resultText).map(Some(_)).left.map { err =>
            s"Expected Codex structured output as JSON text: $err"
          }
        case None => Right(None)

    ZIO
      .fromEither(structuredOutput.flatMap(codec.decode(resultText, _)))
      .mapError(AgentError.MessageParseError(_))

  /** Overlay ExecutionPolicy onto CodexThreadOptions. */
  private def overlayPolicy(
    base: CodexThreadOptions,
    policy: ExecutionPolicy,
  ): CodexThreadOptions =
    var opts = base
    // Codex doesn't expose budget or maxTurns at the thread level.
    // Deadline is enforced via timeoutFail at the stream level.
    opts

  /** Apply deadline enforcement. */
  private def applyDeadline[A](
    effect: IO[AgentError, A],
    policy: ExecutionPolicy,
  ): IO[AgentError, A] =
    policy.deadline match
      case Some(duration) =>
        effect.timeoutFail(AgentError.Interrupted(s"Deadline exceeded: $duration"))(duration)
      case None => effect
end CodexInterpreter
