package com.tjclp.scalagent.interop.claude

import zio.*
import zio.stream.*
import zio.json.ast.Json
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.config.{AgentOptions, OutputFormat, StructuredOutput}
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.messages.{AgentMessage, ResultOutcome}
import com.tjclp.scalagent.{ClaudeAgent, CollectionPolicy}

/** Bridges the core `Agent` trait to the existing `ClaudeAgent` runtime.
  *
  * Translates `ExecutionPolicy` to `AgentOptions`, maps `AgentMessage` to `AgentEvent`,
  * and extracts typed output via `OutputCodec`.
  */
object ClaudeInterpreter:

  private final case class SharedRun[O](
      events: ZStream[Any, AgentError, AgentEvent],
      result: IO[AgentError, O]
  )

  private enum SharedState[O]:
    case Empty[O]() extends SharedState[O]
    case Initializing(promise: Promise[Nothing, SharedRun[O]])
    case Ready(shared: SharedRun[O])

  /** Create a string-output agent backed by ClaudeAgent. */
  def string(
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions = AgentOptions.default
  ): Agent[Any, String, String] =
    make[String](claudeAgent, baseOptions)

  /** Create a typed-output agent backed by ClaudeAgent. */
  def typed[A](
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions = AgentOptions.default
  )(using StructuredOutput[A]): Agent[Any, String, A] =
    make[A](claudeAgent, baseOptions)

  /** Start building a capability-typed string agent.
    *
    * The builder's `agentTransform` wires tool surface declarations into
    * `AgentOptions`, so `.withTools(surface).build` produces an agent that
    * actually restricts tool access at the provider level.
    */
  def builder(
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions = AgentOptions.default
  ): AgentBuilder[Any, String, String, Any] =
    AgentBuilder.withTransform(
      string(claudeAgent, baseOptions),
      claudeTransform(claudeAgent, baseOptions)
    )

  /** Start building a capability-typed structured-output agent. */
  def typedBuilder[A](
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions = AgentOptions.default
  )(using so: StructuredOutput[A]): AgentBuilder[Any, String, A, Any] =
    AgentBuilder.withTransform(
      typed[A](claudeAgent, baseOptions),
      claudeTransform[A](claudeAgent, baseOptions)
    )

  /** Creates a transform that rebuilds the Claude-backed agent with capability
    * restrictions applied to AgentOptions.
    */
  private def claudeTransform[O](
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions
  )(using codec: OutputCodec[O]): (Agent[Any, String, O], ToolSurface, Int) => Agent[Any, String, O] =
    (_, toolSurface, _) =>
      if toolSurface.isEmpty then make[O](claudeAgent, baseOptions)
      else
        val restrictedOptions = baseOptions.copy(
          allowedTools = Some(toolSurface.distinctAllowedTools)
        )
        make[O](claudeAgent, restrictedOptions)

  /** Create an agent with any output type that has an OutputCodec. */
  def make[O](
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions = AgentOptions.default
  )(using codec: OutputCodec[O]): Agent[Any, String, O] =
    new Agent[Any, String, O]:
      def run(principal: Any, input: String, policy: ExecutionPolicy): AgentRun[Any, O] =
        val options = overlayPolicy(baseOptions, policy, codec)
        val stateRef: Ref[SharedState[O]] = Unsafe.unsafe { implicit u =>
          Ref.unsafe.make[SharedState[O]](SharedState.Empty())
        }

        def getShared: UIO[SharedRun[O]] =
          Promise.make[Nothing, SharedRun[O]].flatMap { myPromise =>
            stateRef.modify {
              case SharedState.Ready(shared) =>
                (ZIO.succeed(shared), SharedState.Ready(shared))
              case SharedState.Initializing(existing) =>
                (existing.await, SharedState.Initializing(existing))
              case SharedState.Empty() =>
                val init = buildSharedRun(claudeAgent, input, options, policy, codec).flatMap { shared =>
                  myPromise.succeed(shared) *> stateRef.set(SharedState.Ready(shared)).as(shared)
                }
                (init, SharedState.Initializing(myPromise))
            }.flatten
          }

        AgentRun(
          events = ZStream.unwrap(getShared.map(_.events)),
          result = getShared.flatMap(_.result)
        )

  private def buildSharedRun[O](
      claudeAgent: ClaudeAgent,
      input: String,
      options: AgentOptions,
      policy: ExecutionPolicy,
      codec: OutputCodec[O]
  ): UIO[SharedRun[O]] =
    for
      queue <- Queue.unbounded[Take[AgentError, AgentEvent]]
      resultPromise <- Promise.make[AgentError, O]
      sink = (message: AgentMessage) =>
        ZIO.foreachDiscard(EventMapper.mapMessage(message)) { event =>
          queue.offer(Take.single(event)).unit
        }
      query = applyDeadline(
        claudeAgent.queryComplete(
          input,
          options,
          collectionPolicy = CollectionPolicy.ResultOnly,
          sink = sink
        ),
        policy
      )
      runner = query.exit.flatMap {
        case Exit.Success(result) =>
          extractOutput(result.outcome, codec).foldZIO(
            error => resultPromise.fail(error).unit *> queue.offer(Take.end).unit,
            output => resultPromise.succeed(output).unit *> queue.offer(Take.end).unit
          )
        case Exit.Failure(cause) =>
          val error = cause.failureOption.getOrElse(AgentError.Unknown(cause.prettyPrint))
          resultPromise.fail(error).ignore *> queue.offer(Take.fail(error)).unit
      }
      _ <- runner.forkDaemon
    yield SharedRun(
      events = ZStream.fromQueue(queue).flattenTake,
      result = resultPromise.await
    )

  /** Overlay ExecutionPolicy onto base AgentOptions. */
  private def overlayPolicy[O](
      base: AgentOptions,
      policy: ExecutionPolicy,
      codec: OutputCodec[O]
  ): AgentOptions =
    var opts = base

    // Budget
    policy.budget match
      case Budget.Usd(amount) =>
        opts = opts.copy(maxBudgetUsd = Some(amount))
      case Budget.Unlimited => ()

    // Max turns
    policy.maxTurns.foreach { turns =>
      opts = opts.copy(maxTurns = Some(turns))
    }

    // StopStrategy.FirstResponse -> maxTurns = 1
    policy.stopStrategy match
      case StopStrategy.FirstResponse if policy.maxTurns.isEmpty =>
        opts = opts.copy(maxTurns = Some(1))
      case _ => ()

    // Structured output format from codec
    codec.structuredOutputFormat.foreach { so =>
      opts = opts.copy(outputFormat = Some(OutputFormat(so.jsonSchema)))
    }

    opts

  /** Extract typed output from ResultOutcome using OutputCodec. */
  private def extractOutput[O](
      outcome: ResultOutcome,
      codec: OutputCodec[O]
  ): IO[AgentError, O] =
    outcome match
      case s: ResultOutcome.Success =>
        ZIO.fromEither(
          codec.decode(s.result, s.structuredOutput)
        ).mapError(msg => AgentError.MessageParseError(msg))
      case e: ResultOutcome.Error =>
        ZIO.fail(AgentError.ApiError(500, e.reason.toRaw, Some(e.errors.mkString("; "))))

  /** Apply deadline enforcement to the provider run. */
  private def applyDeadline[A](
      effect: IO[AgentError, A],
      policy: ExecutionPolicy
  ): IO[AgentError, A] =
    policy.deadline match
      case Some(duration) =>
        effect
          .timeoutFail(AgentError.Interrupted(s"Deadline exceeded: $duration"))(duration)
      case None => effect
