package com.tjclp.scalagent.examples

import zio.*
import zio.json.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.hooks.HookConfig

/** Example demonstrating agent-level hooks and the HookConfig system.
  *
  * This example shows how to:
  *   1. Use HookConfig.Shell for serializable shell command hooks
  *   2. Use HookConfig.Callback for runtime callback hooks
  *   3. Add hooks directly to AgentDefinition
  *   4. Use the permissionMode field in AgentDefinition
  *   5. Use the fluent extension methods (withShellHook, withCallbackHook)
  *
  * Run with: EXAMPLE=agent-hooks mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set when Claude Code auth is not already available.
  */
object AgentHooksExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    for
      _ <- Console.printLine("=== Agent Hooks Example ===").orDie
      _ <- Console.printLine("Demonstrating HookConfig types and agent-level hooks\n").orDie

      // Part 1: Show the HookConfig types
      _ <- demonstrateHookConfigTypes

      // Part 2: Run a query with an agent that has callback hooks
      _ <- runWithAgentHooks
    yield ()

  /** Demonstrate the two HookConfig styles and their properties */
  private def demonstrateHookConfigTypes: Task[Unit] =
    for
      _ <- Console.printLine("--- HookConfig Types ---")

      // Shell hooks are JSON serializable - great for config files
      shellHook = HookConfig.shell(
        matcher = "Bash|Edit|Write",
        command = "echo 'Tool: $TOOL_NAME'",
        timeout = Some(5000)
      )
      _ <- Console.printLine(s"Shell hook: matcher=${shellHook.matcherPattern.getOrElse("*")}, isShell=${shellHook.isShell}")

      // One-time shell hook
      oneTimeHook = HookConfig.shellOnce("Write", "./audit.sh")
      _ <- Console.printLine(s"One-time hook: isOnce=${oneTimeHook.isOnce}")

      // Callback hooks are runtime-only
      callbackHook: HookConfig = HookConfig.callback { _ =>
        ZIO.succeed(HookOutput.continue)
      }
      _ <- Console.printLine(s"Callback hook: isCallback=${callbackHook.isCallback}")

      // Show JSON serialization
      shellJson = shellHook.toJson
      _ <- Console.printLine(s"Shell hook JSON: $shellJson")

      callbackJson = callbackHook.toJson
      _ <- Console.printLine(s"Callback hook JSON: $callbackJson")
      _ <- Console.printLine("")
    yield ()

  /** Run a real query with an agent that has hooks attached */
  private def runWithAgentHooks: Task[Unit] =
    for
      _ <- Console.printLine("--- Running Query with Hooked Agent ---")

      // Create a logging callback that fires on every tool use
      loggingCallback: HookCallback = {
        case input: HookInput.PreToolUse =>
          Console.printLine(s"  [Agent Hook] Pre-tool: ${input.toolName.raw}") *>
            ZIO.succeed(HookOutput.continue)
        case input: HookInput.PostToolUse =>
          Console.printLine(s"  [Agent Hook] Post-tool: ${input.toolName.raw} completed") *>
            ZIO.succeed(HookOutput.continue)
        case _ =>
          ZIO.succeed(HookOutput.continue)
      }

      // Create an agent with hooks using extension methods
      hookedAgent = AgentDefinition
        .readOnly(
          description = "Code analyzer with logging hooks",
          prompt = "You analyze code and report findings concisely."
        )
        .withPermissionMode(PermissionMode.DontAsk)
        .withCallbackHook(HookEvent.PreToolUse, loggingCallback)
        .withCallbackHook(HookEvent.PostToolUse, loggingCallback)

      _ <- Console.printLine(s"Agent configured: hasHooks=${hookedAgent.hasHooks}, permissionMode=${hookedAgent.permissionMode}")

      // Also add global hooks to see both in action
      globalLoggingHook: HookCallback = {
        case input: HookInput.PreToolUse =>
          Console.printLine(s"  [Global Hook] About to use: ${input.toolName.raw}") *>
            ZIO.succeed(HookOutput.continue)
        case _ =>
          ZIO.succeed(HookOutput.continue)
      }

      options = AgentOptions.default
        .withModel(Model.haiku)
        .withPermissionMode(PermissionMode.DontAsk)
        .withMaxTurns(3)
        .withHook(HookEvent.PreToolUse, globalLoggingHook)
        .withAgent("analyzer", hookedAgent)

      _ <- Console.printLine("Querying Claude to list files (will trigger Read/Glob hooks)...\n")

      // Run the query
      result <- Claude.queryComplete(
        "Use the Glob tool to find *.scala files in the current directory. Just list the first 3 files you find.",
        options
      )

      _ <- result.outcome match
        case success: ResultOutcome.Success =>
          Console.printLine(s"\n--- Result ---") *>
            Console.printLine(s"${success.result.take(500)}") *>
            Console.printLine(s"Turns: ${success.numTurns}, Cost: $$${success.totalCostUsd}")
        case error: ResultOutcome.Error =>
          Console.printLine(s"\n--- Error: ${error.reason} ---") *>
            Console.printLine(s"${error.errors.mkString(", ")}")

    yield ()
