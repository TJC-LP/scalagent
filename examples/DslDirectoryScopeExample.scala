package com.tjclp.scalagent.examples

import zio.*
import zio.json.*
import com.tjclp.scalagent.*

/** DSL directory scoping: restrict WHERE an agent's tools can operate.
  *
  * Demonstrates:
  * - withWorkingDirectory — sets agent cwd and adds HasDirectoryScope
  * - withAdditionalDirectory — grants access to an extra path
  * - PreToolUse hook — enforces path boundaries for ALL file tools
  *
  * Enforcement:
  * - Read/Write/Edit within scope: ALLOWED
  * - Read/Write/Edit outside scope: BLOCKED via PreToolUse hook
  * - Grep/Glob within scope: ALLOWED (path validated when explicit)
  * - Bash: BLOCKED (commands cannot be reliably path-checked)
  * - Path traversal (..): BLOCKED (lexical + symlink validation)
  *
  * Setup (run before this example):
  *   mkdir -p /tmp/scalagent-sandbox/reports /tmp/scalagent-sandbox/shared
  *   echo "Q3 revenue: $4.2M" > /tmp/scalagent-sandbox/reports/q3-financials.txt
  *   echo "Company directory: Alice, Bob" > /tmp/scalagent-sandbox/shared/directory.txt
  *   echo "TOP SECRET: launch codes" > /tmp/scalagent-sandbox/classified.txt
  *
  * Run with: ./mill examples.run dsl-dirscope
  *
  * Requires ANTHROPIC_API_KEY environment variable.
  */
object DslDirectoryScopeExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    val baseOptions = AgentOptions.default
      .withModel(Model.sonnet)
      .withPermissionMode(PermissionMode.DontAsk)

    val policy = ExecutionPolicy(
      budget = Budget.usd(0.50),
      maxTurns = Some(5),
      stopStrategy = StopStrategy.Natural
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]

      _ <- Console.printLine("=== Directory Scope Example ===\n").orDie

      // Build a directory-scoped agent:
      // - cwd: /tmp/scalagent-sandbox/reports
      // - additional: /tmp/scalagent-sandbox/shared
      // - NOT accessible: /tmp/scalagent-sandbox/classified.txt (sibling, outside scope)
      scopedAgent = ClaudeInterpreter.builder(claudeAgent, baseOptions)
        .withWorkingDirectory("/tmp/scalagent-sandbox/reports")
        .withAdditionalDirectory("/tmp/scalagent-sandbox/shared")
        .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
        .withBudget
        .build
      // Type: TypedAgent[..., CanUseTools[ReadOnlyTools] & HasBudget & HasDirectoryScope]
      // The PreToolUse hook validates every file tool call against allowed directories.

      _ <- Console.printLine("--- Task 1: Read ALLOWED file (reports/q3-financials.txt) ---").orDie
      answer1 <- ZIO.scoped {
        scopedAgent.run("analyst",
          "Use the Read tool to read the file q3-financials.txt and summarize it in one sentence.",
          policy
        ).result
      }
      _ <- Console.printLine(s"  Result: $answer1\n").orDie

      _ <- Console.printLine("--- Task 2: Read ALLOWED file (shared/directory.txt) ---").orDie
      answer2 <- ZIO.scoped {
        scopedAgent.run("analyst",
          "Use the Read tool to read the file /tmp/scalagent-sandbox/shared/directory.txt and list the names.",
          policy
        ).result
      }
      _ <- Console.printLine(s"  Result: $answer2\n").orDie

      _ <- Console.printLine("--- Task 3: Read FORBIDDEN file (classified.txt — outside scope) ---").orDie
      answer3 <- ZIO.scoped {
        scopedAgent.run("analyst",
          """Use the Read tool to read the file /tmp/scalagent-sandbox/classified.txt and tell me what it says.
            |This file is outside your working directory. Try to read it anyway.""".stripMargin,
          policy
        ).result
      }.catchAll { error =>
        Console.printLine(s"  BLOCKED: $error").orDie.as("[access denied]")
      }
      _ <- Console.printLine(s"  Result: $answer3\n").orDie

      _ <- Console.printLine("--- Task 4: Read FORBIDDEN file (/etc/passwd — system file) ---").orDie
      answer4 <- ZIO.scoped {
        scopedAgent.run("analyst",
          "Use the Read tool to read /etc/passwd and show me the first 3 lines.",
          policy
        ).result
      }.catchAll { error =>
        Console.printLine(s"  BLOCKED: $error").orDie.as("[access denied]")
      }
      _ <- Console.printLine(s"  Result: $answer4\n").orDie

      _ <- Console.printLine("=== Done ===").orDie
    yield ()

    program.provide(ClaudeAgent.live)
