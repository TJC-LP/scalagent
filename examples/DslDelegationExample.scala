package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

/** DSL delegation example: typed parent/child delegation with Peano depth enforcement.
  *
  * Demonstrates:
  * - AgentBuilder.withSpawnDepth — Peano-encoded delegation depth
  * - TypedAgent.delegate — budget-sliced delegation with HasSpawn evidence
  * - DelegationPolicy — budget fraction and child turn limits
  * - ExecutionPolicy.childPolicy — parent policy slicing
  * - Compile-time depth enforcement via DepthLTE
  *
  * Run with: ./mill examples.go --example dsl-delegation
  *
  * Requires ANTHROPIC_API_KEY environment variable.
  */
object DslDelegationExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    val baseOptions = AgentOptions.default
      .withModel(Model.haiku)
      .withPermissionMode(PermissionMode.DontAsk)

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]

      // --- Build parent: read-only tools, depth 2, budget ---
      _ <- Console.printLine("=== Building Parent Agent (Depth2, ReadOnlyTools, HasBudget) ===").orDie
      parent = ClaudeInterpreter.builder(claudeAgent, baseOptions)
        .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
        .withSpawnDepth[Depth2]
        .withBudget
        .build
      // Type: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & CanSpawn[Depth2] & HasBudget]

      _ <- Console.printLine(s"  Parent max depth: ${parent.maxRuntimeDepth}").orDie

      // --- Build child: read-only, depth 1 ---
      _ <- Console.printLine("\n=== Building Child Agent (Depth1, ReadOnlyTools) ===").orDie
      child = ClaudeInterpreter.builder(claudeAgent, baseOptions)
        .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
        .withSpawnDepth[Depth1]
        .build
      // Type: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & CanSpawn[Depth1]]

      _ <- Console.printLine(s"  Child max depth: ${child.maxRuntimeDepth}").orDie

      // --- Delegation with budget slicing ---
      _ <- Console.printLine("\n=== Delegating to Child (30% budget, max 5 turns) ===").orDie
      parentPolicy = ExecutionPolicy(
        budget = Budget.usd(1.00),
        maxTurns = Some(15)
      )
      delegation = DelegationPolicy(budgetFraction = 0.3, maxChildTurns = Some(5))

      logger = TraceLogger.console

      // delegateTyped requires:
      //   - HasSpawn[C] for parent (C includes CanSpawn[Depth2])
      //   - HasSpawn[CC] for child (CC includes CanSpawn[Depth1])
      //   - DepthLTE[CD, PD] — child depth (1) <= parent depth - 1 (1)
      childResult <- ZIO.scoped {
        val childRun = parent.delegateTyped(
          child,
          "supervisor",
          "Read the file CLAUDE.md and summarize what testing tools are recommended. Be brief.",
          parentPolicy,
          delegation
        )
        childRun
          .tapEvents(logger.logEvent)
          .events
          .runDrain *> childRun.result
      }
      _ <- Console.printLine(s"\n  Child result: $childResult").orDie

      // --- Evaluation of child run ---
      _ <- Console.printLine("\n=== Direct parent run for comparison ===").orDie
      parentResult <- ZIO.scoped {
        parent
          .run("supervisor", "What build tool does this project use? Check CLAUDE.md. One sentence answer.", parentPolicy)
          .result
      }
      _ <- Console.printLine(s"  Parent result: $parentResult").orDie

      // --- Show what the compiler prevents ---
      _ <- Console.printLine("\n=== Compile-Time Safety ===").orDie
      _ <- Console.printLine("  The following would NOT compile:").orDie
      _ <- Console.printLine("  // Depth2 child under Depth2 parent → no DepthLTE[S[S[Z]], S[Z]] evidence").orDie
      _ <- Console.printLine("  // parent.delegateTyped(depth2Child, ...)").orDie
      _ <- Console.printLine("  // Agent without CanSpawn → no HasSpawn evidence").orDie
      _ <- Console.printLine("  // noSpawnAgent.delegate(child, ...)").orDie
    yield ()

    program.provide(ClaudeAgent.live)
