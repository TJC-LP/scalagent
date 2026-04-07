package com.tjclp.scalagent.interop.claude

import zio.*
import zio.stream.*
import zio.json.ast.Json
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.core.mcp.McpToolSurface
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import com.tjclp.scalagent.config.{AgentOptions, OutputFormat, StructuredOutput}
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.hooks.HookEvent
import com.tjclp.scalagent.interop.mcp.McpToolLoader
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

  /** Create a string-output agent backed by ClaudeAgent.
    *
    * No tool access by default. To grant tools, use
    * `builder().withTools().build` or pass options with explicit `withAllowedTools`.
    */
  def string(
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions = AgentOptions.default
  ): Agent[Any, String, String] =
    make[String](claudeAgent, baseOptions.withSafeToolDefault)

  /** Create a typed-output agent backed by ClaudeAgent.
    *
    * No tool access by default. To grant tools, use
    * `typedBuilder().withTools().build` or pass options with explicit `withAllowedTools`.
    */
  def typed[A](
      claudeAgent: ClaudeAgent,
      baseOptions: AgentOptions = AgentOptions.default
  )(using StructuredOutput[A]): Agent[Any, String, A] =
    make[A](claudeAgent, baseOptions.withSafeToolDefault)

  /** Start building a capability-typed string agent.
    *
    * No tool access until `.withTools()`, `.withReadOnlyTools()`, or
    * `.withAllTools()` is called. The builder's `agentTransform` wires
    * tool surface declarations into `AgentOptions` at build time.
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
  )(using codec: OutputCodec[O]): (Agent[Any, String, O], BuilderConfig) => Agent[Any, String, O] =
    (_, cfg) =>
      val toolSurface = cfg.tools
      val mcpToolSurfaces = cfg.mcpToolSurfaces

      val opts =
        if toolSurface.isEmpty && mcpToolSurfaces.isEmpty then baseOptions.withSafeToolDefault
        else
          val runtime = Runtime.default
          val allMcpToolSurfaces =
            mergeMcpToolSurfaces(localToolSurface(toolSurface, mcpToolSurfaces).toList ++ mcpToolSurfaces)
          allMcpToolSurfaces.foldLeft(baseOptions.copy(
            allowedTools = Some(toolSurface.distinctAllowedTools)
          )) { (o, surface) =>
            o.withMcpServer(surface.serverName, McpToolLoader.toServerFactory(surface, runtime))
          }

      val withDir = cfg.directoryScope.fold(opts) { scope =>
        opts.copy(
          cwd = Some(scope.cwd),
          additionalDirectories = scope.additionalDirectories
        ).withHook(HookEvent.PreToolUse, scope.toHook)
      }

      make[O](claudeAgent, withDir)

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

        def getShared: URIO[Scope, SharedRun[O]] =
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
  ): URIO[Scope, SharedRun[O]] =
    for
      queue <- Queue.unbounded[Take[AgentError, AgentEvent]]
      resultPromise <- Promise.make[AgentError, O]
      cancelledError = AgentError.Interrupted("Agent run scope closed")
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
      }.onInterrupt(
        resultPromise.fail(cancelledError).ignore *>
          queue.offer(Take.fail(cancelledError)).ignore
      )
      _ <- runner.forkScoped
    yield SharedRun(
      events = ZStream.fromQueue(queue).flattenTake,
      result = resultPromise.await
    )

  private def localToolSurface(
      toolSurface: ToolSurface,
      mcpToolSurfaces: List[McpToolSurface]
  ): Option[McpToolSurface] =
    val explicitMcpToolNames = mcpToolSurfaces.flatMap(_.tools.map(_.name)).toSet
    val localTools = toolSurface.tools.filterNot(tool => explicitMcpToolNames.contains(tool.name)).distinctBy(_.name)
    Option.when(localTools.nonEmpty)(McpToolSurface(ToolSurface.localToolServerName, localTools))

  private def mergeMcpToolSurfaces(
      surfaces: List[McpToolSurface]
  ): List[McpToolSurface] =
    surfaces
      .groupBy(_.serverName)
      .toList
      .sortBy(_._1)
      .map { case (serverName, grouped) =>
        McpToolSurface(serverName, grouped.flatMap(_.tools).distinctBy(_.name))
      }

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
