package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

/** DSL basic example: one-shot query and streaming with the provider-independent Agent trait.
  *
  * Demonstrates:
  * - ClaudeInterpreter.string() — wraps ClaudeAgent as Agent[Any, String, String]
  * - ExecutionPolicy — semantic constraints (budget, turns, deadline)
  * - AgentRun — event stream + typed result from a single execution
  * - AgentEvent — normalized event ADT with typed cases
  * - TraceLogger — composable event logging
  *
  * Run with: ./mill examples.go --example dsl-basic
  *
  * Requires ANTHROPIC_API_KEY environment variable.
  */
object DslBasicExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    val baseOptions = AgentOptions.default
      .withModel(Model.haiku)
      .withPermissionMode(PermissionMode.DontAsk)

    val policy = ExecutionPolicy(
      budget = Budget.usd(1.00),
      maxTurns = Some(3),
      stopStrategy = StopStrategy.FirstResponse
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]
      agent = ClaudeInterpreter.string(claudeAgent, baseOptions)

      // --- One-shot query ---
      _ <- Console.printLine("=== DSL One-Shot Query ===").orDie
      answer <- ZIO.scoped {
        agent.run("user", "What is the capital of France? Reply with just the city name.", policy).result
      }
      _ <- Console.printLine(s"Answer: $answer").orDie

      // --- Streaming with event logging ---
      _ <- Console.printLine("\n=== DSL Streaming ===").orDie
      logger = TraceLogger.console
      collectedAndOutput <- ZIO.scoped {
        val agentRun = agent.run("user", "Count from 1 to 5, one number per line.", policy)
        for
          events <- agentRun
            .tapEvents(logger.logEvent)
            .events
            .runCollect
            .map(_.toList)
          output <- agentRun.result
        yield (events, output)
      }
      (collected, streamOutput) = collectedAndOutput

      // --- TraceSummary from collected events ---
      _ <- Console.printLine("\n=== Trace Summary ===").orDie
      trace = TraceSummary.fromEvents(collected)
      _ <- Console.printLine(s"  Events: ${trace.totalEvents}").orDie
      _ <- Console.printLine(s"  Tool calls: ${trace.numToolCalls}").orDie
      _ <- Console.printLine(s"  Success: ${trace.isSuccess}").orDie
      _ <- Console.printLine(s"  Cost: $$${trace.costUsd}").orDie
      _ <- Console.printLine(s"  Duration: ${trace.durationMs}ms").orDie

      // --- Evaluation scoring ---
      _ <- Console.printLine("\n=== Evaluation ===").orDie
      utility = Utility.weighted[String, String](
        Utility.reliability      -> 0.5,
        Utility.costMinimizing   -> 0.3,
        Utility.simplicityBiased -> 0.2
      )
      eval = Evaluation.fromTrace("user", streamOutput, trace, utility)
      _ <- Console.printLine(s"  Score: ${eval.score}").orDie
      _ <- Console.printLine(s"  Complexity: ${eval.complexity.totalNodes} nodes").orDie
      _ <- Console.printLine(s"  Graph density: ${eval.complexity.graphDensity}").orDie
      _ <- logger.logEvaluation(eval)
    yield ()

    program.provide(ClaudeAgent.live)
