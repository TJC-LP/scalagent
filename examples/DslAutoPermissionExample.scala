package com.tjclp.scalagent.examples

import zio.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

/**
 * DSL auto permission mode: the SDK's model classifier approves tool calls.
 *
 * Demonstrates:
 * - PermissionMode.Auto — no pre-listed tools needed, no human prompts
 * - The classifier allows safe tools (Read, Grep) and denies risky ones
 * - Compared to DontAsk (must pre-list) and BypassPermissions (allows all)
 *
 * Run with: ./mill examples.run dsl-auto
 *
 * Requires ANTHROPIC_API_KEY environment variable.
 */
object DslAutoPermissionExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    val policy = ExecutionPolicy(
      budget = Budget.usd(0.50),
      maxTurns = Some(5),
      stopStrategy = StopStrategy.Natural,
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]

      _ <- Console.printLine("=== Auto Permission Mode ===\n").orDie

      // --- Auto mode: classifier decides per tool call ---
      _ <- Console.printLine("--- Auto mode (model classifier) ---").orDie
      autoOptions = AgentOptions.default
        .withModel(Model.sonnet)
        .withPermissionMode(PermissionMode.Auto)

      autoAgent = ClaudeInterpreter.builder(claudeAgent, autoOptions).withAllTools.withBudget.build

      autoResult <- ZIO.scoped {
        val agentRun = autoAgent.run(
          "analyst",
          "Read the file CLAUDE.md and tell me in one sentence what build tool this project uses.",
          policy,
        )
        for
          events <- agentRun.events.runCollect.map(_.toList)
          output <- agentRun.result
          trace = TraceSummary.fromEvents(events)
          _ <- Console.printLine(s"  Tools used: ${trace.toolNames}").orDie
          _ <- Console.printLine(s"  Turns: ${trace.numTurns}, Cost: $$${trace.costUsd}").orDie
        yield output
      }
      _ <- Console.printLine(s"  Result: $autoResult\n").orDie

      // --- DontAsk mode: same task, must pre-list allowed tools ---
      _ <- Console.printLine("--- DontAsk mode (explicit allowlist) ---").orDie
      dontAskOptions = AgentOptions.default
        .withModel(Model.sonnet)
        .withPermissionMode(PermissionMode.DontAsk)

      dontAskAgent = ClaudeInterpreter
        .builder(claudeAgent, dontAskOptions)
        .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
        .withBudget
        .build

      dontAskResult <- ZIO.scoped {
        val agentRun = dontAskAgent.run(
          "analyst",
          "Read the file CLAUDE.md and tell me in one sentence what build tool this project uses.",
          policy,
        )
        for
          events <- agentRun.events.runCollect.map(_.toList)
          output <- agentRun.result
          trace = TraceSummary.fromEvents(events)
          _ <- Console.printLine(s"  Tools used: ${trace.toolNames}").orDie
          _ <- Console.printLine(s"  Turns: ${trace.numTurns}, Cost: $$${trace.costUsd}").orDie
        yield output
      }
      _ <- Console.printLine(s"  Result: $dontAskResult\n").orDie

      _ <- Console.printLine("=== Done ===").orDie
      _ <- Console.printLine("Auto mode: no tool pre-listing needed, classifier decides.").orDie
      _ <- Console.printLine("DontAsk mode: must explicitly declare allowed tools.").orDie
    yield ()

    program.provide(ClaudeAgent.live)
  end run
end DslAutoPermissionExample
