package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

/**
 * DSL builder example: capability-typed agents with tools and evaluation.
 *
 * Demonstrates:
 * - ClaudeInterpreter.builder() — fluent builder with phantom type accumulation
 * - TypedAgent — agent with compile-time capability evidence
 * - ToolSurface — tool declarations wired to provider allowlists
 * - AgentBuilder.withReadOnlyTools — validated read-only tool restriction
 * - Evaluation pipeline — TraceSummary + Utility + Complexity
 * - TraceLogger — JSONL callback logging
 *
 * Run with: ./mill examples.go --example dsl-builder
 *
 * Requires ANTHROPIC_API_KEY environment variable.
 */
object DslBuilderExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    val baseOptions = AgentOptions.default
      .withModel(Model.haiku)
      .withPermissionMode(PermissionMode.DontAsk)

    val policy = ExecutionPolicy(
      budget = Budget.usd(1.00),
      maxTurns = Some(10),
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]

      // --- Read-only agent with restricted tools ---
      _ <- Console.printLine("=== Read-Only Agent (type: CanUseTools[ReadOnlyTools]) ===").orDie
      readOnlyAgent = ClaudeInterpreter
        .builder(claudeAgent, baseOptions)
        .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
        .withBudget
        .build
      // Type: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & HasBudget]

      _ <- Console.printLine(s"  Tool surface: ${readOnlyAgent.toolSurface.distinctAllowedTools.map(_.raw)}").orDie
      _ <- Console.printLine(s"  Max depth: ${readOnlyAgent.maxRuntimeDepth}").orDie

      readOnlyResult <- ZIO.scoped {
        readOnlyAgent
          .run("analyst", "Use the Read tool to read the file ./CLAUDE.md and summarize it in one sentence.", policy)
          .result
      }
      _ <- Console.printLine(s"  Result: $readOnlyResult").orDie

      // --- Full-access agent with custom tools ---
      _ <- Console.printLine("\n=== Explicit Tool-Surface Agent (type: CanUseTools[CustomTools] & HasBudget) ===").orDie
      fullAgent = ClaudeInterpreter
        .builder(claudeAgent, baseOptions)
        .withTools(ToolSurface.readOnlyBuiltins)
        .withBudget
        .build
      // Type: TypedAgent[Any, String, String, CanUseTools[CustomTools] & HasBudget]

      // --- Run with JSONL logging ---
      jsonLines <- Ref.make(List.empty[String])
      jsonlLogger   = TraceLogger.callbackZIO(line => jsonLines.update(_ :+ line))
      consoleLogger = TraceLogger.console
      logger        = TraceLogger.all(consoleLogger, jsonlLogger)

      collectedAndOutput <- ZIO.scoped {
        val agentRun = fullAgent.run(
          "developer",
          "Use the Glob tool to find all .scala files in the examples/ directory. List them.",
          policy,
        )
        for
          events <- agentRun
            .tapEvents(logger.logEvent)
            .events
            .runCollect
            .map(_.toList)
          output <- agentRun.result
        yield (events, output)
      }
      (collected, fullOutput) = collectedAndOutput

      // --- Evaluation ---
      _ <- Console.printLine("\n=== Evaluation ===").orDie
      trace   = TraceSummary.fromEvents(collected)
      utility = Utility.weighted[String, String](
        Utility.reliability       -> 0.4,
        Utility.costMinimizing    -> 0.3,
        Utility.latencyMinimizing -> 0.2,
        Utility.simplicityBiased  -> 0.1,
      )
      eval = Evaluation.fromTrace("developer", fullOutput, trace, utility)
      _ <- logger.logEvaluation(eval)

      _ <- Console.printLine(s"\n  Score: ${eval.score}").orDie
      _ <- Console
        .printLine(
          s"  Breakdown: ${eval.breakdown.components.map(c => s"${c.name}=${"%.3f".format(c.raw)}").mkString(", ")}"
        )
        .orDie
      _ <- Console.printLine(s"  Tool calls: ${trace.numToolCalls}").orDie
      _ <- Console.printLine(s"  Tools used: ${trace.toolNames}").orDie
      _ <- Console
        .printLine(s"  Complexity: ${eval.complexity.totalNodes} nodes, density=${eval.complexity.graphDensity}")
        .orDie

      // --- Show JSONL output ---
      lines <- jsonLines.get
      _     <- Console.printLine(s"\n=== JSONL Log (${lines.size} entries) ===").orDie
      _     <- ZIO.foreach(lines.take(5))(line => Console.printLine(s"  $line").orDie)
      _     <- ZIO.when(lines.size > 5)(Console.printLine(s"  ... (${lines.size - 5} more)").orDie)
    yield ()

    program.provide(ClaudeAgent.live)
  end run
end DslBuilderExample
