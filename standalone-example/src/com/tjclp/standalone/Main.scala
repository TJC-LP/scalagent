package com.tjclp.standalone

import zio.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

// Standalone example: depends only on the published Scalagent JAR.
// All npm dependencies (claude-agent-sdk, zod, etc.) are resolved
// automatically from the JAR's embedded bun-dependencies.json manifest.
// No package.json, no manual `bun install`, no bunDeps declaration.
object Main extends ZIOAppDefault:

  val run =
    val options = AgentOptions.default
      .withModel(Model.haiku)
      .withPermissionMode(PermissionMode.DontAsk)

    val policy = ExecutionPolicy(
      budget = Budget.usd(0.50),
      maxTurns = Some(1),
      stopStrategy = StopStrategy.FirstResponse
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]
      agent = ClaudeInterpreter.string(claudeAgent, options)

      _ <- Console.printLine("--- Standalone Scalagent Consumer ---").orDie
      _ <- Console.printLine("No package.json. No bunDeps. Just a JAR dependency.").orDie
      _ <- Console.printLine("").orDie

      // One-shot DSL query
      answer <- ZIO.scoped {
        agent.run("user", "What is 2 + 2? Reply with just the number.", policy).result
      }
      _ <- Console.printLine(s"Agent response: $answer").orDie

      // Trace summary from a streaming run
      _ <- Console.printLine("\n--- Streaming with trace ---").orDie
      (events, output) <- ZIO.scoped {
        val agentRun = agent.run("user", "Name three prime numbers under 20.", policy)
        for
          evts <- agentRun.events.runCollect.map(_.toList)
          out  <- agentRun.result
        yield (evts, out)
      }
      trace = TraceSummary.fromEvents(events)
      _ <- Console.printLine(s"Response: $output").orDie
      _ <- Console.printLine(s"Events: ${trace.totalEvents}, Cost: $$${trace.costUsd}").orDie
    yield ()

    program.provide(ClaudeAgent.live)
