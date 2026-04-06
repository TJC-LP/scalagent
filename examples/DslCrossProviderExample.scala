package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.codex.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter
import com.tjclp.scalagent.interop.codex.CodexInterpreter

/** Cross-provider example: Claude and Codex agents interacting via the DSL.
  *
  * Demonstrates the ultimate proof of provider independence:
  * - Both agents implement the same `Agent[Any, String, String]` trait
  * - Claude and Codex chain: one generates, the other answers
  * - Same TraceSummary, Evaluation, TraceLogger pipeline for both
  * - The DSL doesn't care which provider backs the agent
  *
  * Run with: ./mill examples.go --example dsl-cross
  *
  * Requires: ANTHROPIC_API_KEY and OPENAI_API_KEY environment variables
  * Requires: codex CLI installed
  */
object DslCrossProviderExample extends ZIOAppDefault:

  /** Run an agent, collect events, evaluate, and return the output. */
  private def runAndEval(
      label: String,
      agent: Agent[Any, String, String],
      input: String,
      policy: ExecutionPolicy,
      logger: TraceLogger,
      utility: Utility[String, String]
  ): ZIO[Any, Any, String] =
    ZIO.scoped {
      val agentRun = agent.run(label, input, policy)
      for
        events <- agentRun
          .tapEvents(logger.logEvent)
          .events
          .runCollect
          .map(_.toList)
        output <- agentRun.result
        trace = TraceSummary.fromEvents(events)
        eval = Evaluation.fromTrace(label, output, trace, utility)
        _ <- logger.logEvaluation(eval)
        _ <- Console.printLine(s"  [$label] Result: $output").orDie
        _ <- Console.printLine(s"  [$label] Score: ${eval.score} | Events: ${trace.totalEvents} | Tools: ${trace.toolNames}").orDie
      yield output
    }

  val run: ZIO[Any, Any, Unit] =
    // --- Set up both providers ---
    val codexClient = CodexClient.create()
    val codexOptions = CodexThreadOptions(
      sandboxMode = Some(SandboxMode.ReadOnly),
      approvalPolicy = Some(ApprovalMode.Never),
      skipGitRepoCheck = true
    )

    val claudeOptions = AgentOptions.default
      .withModel(Model.haiku)
      .withPermissionMode(PermissionMode.DontAsk)

    val policy = ExecutionPolicy(
      maxTurns = Some(5),
      stopStrategy = StopStrategy.FirstResponse
    )

    val logger = TraceLogger.console
    val utility = Utility.weighted[String, String](
      Utility.reliability      -> 0.5,
      Utility.costMinimizing   -> 0.3,
      Utility.simplicityBiased -> 0.2
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent].map { ca =>
        ClaudeInterpreter.string(ca, claudeOptions)
      }
      codexAgent = CodexInterpreter.string(codexClient, codexOptions)

      // --- Round 1: Ask both the same question ---
      _ <- Console.printLine("=== Round 1: Same question, two providers ===").orDie
      question = "What are the three primary colors? Reply with just the colors, comma-separated."
      _ <- Console.printLine(s"\nQuestion: $question").orDie

      _ <- Console.printLine("\n--- Claude (Haiku) ---").orDie
      _ <- runAndEval("claude", claudeAgent, question, policy, logger, utility)

      _ <- Console.printLine("\n--- Codex ---").orDie
      _ <- runAndEval("codex", codexAgent, question, policy, logger, utility)

      // --- Round 2: Chain them — Claude asks, Codex answers ---
      _ <- Console.printLine("\n=== Round 2: Claude → Codex chain ===").orDie
      _ <- Console.printLine("Claude generates a question, Codex answers it.\n").orDie

      claudeQuestion <- ZIO.scoped {
        claudeAgent
          .run("orchestrator", "Generate a short trivia question about space. Just the question, nothing else.", policy)
          .result
      }
      _ <- Console.printLine(s"Claude's question: $claudeQuestion").orDie

      _ <- Console.printLine("\nCodex answers:").orDie
      _ <- runAndEval("codex", codexAgent, claudeQuestion, policy, logger, utility)

      // --- Round 3: Codex asks, Claude answers ---
      _ <- Console.printLine("\n=== Round 3: Codex → Claude chain ===").orDie
      _ <- Console.printLine("Codex generates a question, Claude answers it.\n").orDie

      codexQuestion <- ZIO.scoped {
        codexAgent
          .run("orchestrator", "Generate a short trivia question about music. Just the question, nothing else.", policy)
          .result
      }
      _ <- Console.printLine(s"Codex's question: $codexQuestion").orDie

      _ <- Console.printLine("\nClaude answers:").orDie
      _ <- runAndEval("claude", claudeAgent, codexQuestion, policy, logger, utility)

      // --- Summary ---
      _ <- Console.printLine("\n=== Cross-Provider Summary ===").orDie
      _ <- Console.printLine("Both providers used the same:").orDie
      _ <- Console.printLine("  - Agent[Any, String, String] trait").orDie
      _ <- Console.printLine("  - ExecutionPolicy, AgentEvent, TraceSummary, Evaluation").orDie
      _ <- Console.printLine("  - TraceLogger.console + Utility.weighted for scoring").orDie
      _ <- Console.printLine("The DSL is truly provider-independent.").orDie
    yield ()

    program.provide(ClaudeAgent.live)
