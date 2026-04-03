package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.codex.*
import com.tjclp.scalagent.interop.codex.CodexInterpreter

/** DSL Codex example: same Agent trait, different provider.
  *
  * Demonstrates:
  * - CodexClient.create() — wraps the OpenAI Codex SDK
  * - CodexInterpreter.string() — Agent[Any, String, String] backed by Codex
  * - CodexInterpreter.builder() — capability-typed Codex agent
  * - Same ExecutionPolicy, AgentEvent, TraceSummary, Evaluation pipeline
  *
  * Run with: ./mill examples.go --example dsl-codex
  *
  * Requires: codex CLI installed (brew install openai-codex or npm i -g @openai/codex)
  * Requires: OPENAI_API_KEY environment variable
  */
object DslCodexExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    val client = CodexClient.create(CodexClientOptions(
      // Codex picks up OPENAI_API_KEY from env automatically
    ))

    val threadOptions = CodexThreadOptions(
      sandboxMode = Some(SandboxMode.ReadOnly),
      approvalPolicy = Some(ApprovalMode.Never),
      skipGitRepoCheck = true
    )

    val policy = ExecutionPolicy(
      maxTurns = Some(3),
      stopStrategy = StopStrategy.FirstResponse
    )

    for
      // --- One-shot query via DSL ---
      _ <- Console.printLine("=== Codex DSL One-Shot Query ===").orDie
      agent = CodexInterpreter.string(client, threadOptions)

      logger = TraceLogger.console
      collected <- ZIO.scoped {
        val agentRun = agent.run("user", "What is 7 * 8? Reply with just the number.", policy)
        for
          events <- agentRun
            .tapEvents(logger.logEvent)
            .events
            .runCollect
            .map(_.toList)
          output <- agentRun.result
        yield (events, output)
      }
      (events, answer) = collected
      _ <- Console.printLine(s"\nAnswer: $answer").orDie

      // --- TraceSummary + Evaluation (same pipeline as Claude) ---
      _ <- Console.printLine("\n=== Evaluation (same as Claude pipeline) ===").orDie
      trace = TraceSummary.fromEvents(events)
      utility = Utility.weighted[String, String](
        Utility.reliability      -> 0.5,
        Utility.costMinimizing   -> 0.3,
        Utility.simplicityBiased -> 0.2
      )
      eval = Evaluation.fromTrace("user", answer, trace, utility)
      _ <- Console.printLine(s"  Score: ${eval.score}").orDie
      _ <- Console.printLine(s"  Events: ${trace.totalEvents}").orDie
      _ <- Console.printLine(s"  Tool calls: ${trace.numToolCalls}").orDie
      _ <- Console.printLine(s"  Tools used: ${trace.toolNames}").orDie
      _ <- Console.printLine(s"  Success: ${trace.isSuccess}").orDie
      _ <- logger.logEvaluation(eval)

      // --- Builder with read-only sandbox ---
      _ <- Console.printLine("\n=== Codex Builder (ReadOnly sandbox) ===").orDie
      readOnlyAgent = CodexInterpreter.builder(client, threadOptions)
        .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
        .withBudget
        .build
      // Type: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & HasBudget]

      builderResult <- ZIO.scoped {
        readOnlyAgent
          .run("analyst", "Read CLAUDE.md and tell me in one sentence what build tool this project uses.", policy)
          .result
      }
      _ <- Console.printLine(s"  Result: $builderResult").orDie

      // --- Provider interchangeability proof ---
      _ <- Console.printLine("\n=== Provider Independence ===").orDie
      _ <- Console.printLine("  Both agents implement Agent[Any, String, String]").orDie
      _ <- Console.printLine("  Same ExecutionPolicy, AgentEvent, TraceSummary, Evaluation").orDie
      _ <- Console.printLine("  Same AgentBuilder with CanUseTools[ReadOnlyTools] & HasBudget").orDie
      _ <- Console.printLine("  Zero changes to core/ — only interop/codex/ is new").orDie
    yield ()
