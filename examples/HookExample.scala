package com.tjclp.claude.agent.examples

import zio._
import zio.stream._
import com.tjclp.claude.agent._
import com.tjclp.claude.agent.config._
import com.tjclp.claude.agent.messages._
import com.tjclp.claude.agent.hooks._
import com.tjclp.claude.agent.tools.ToolName

/** Example demonstrating the hook system for tool interception.
  *
  * This example shows how to:
  *   1. Log all tool calls before and after execution
  *   2. Block specific tools (e.g., Bash commands)
  *   3. Modify tool behavior based on custom logic
  *
  * Run with: mill examples.runMain com.tjclp.claude.agent.examples.HookExample
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object HookExample extends ZIOAppDefault:

  val run: ZIO[Any, Throwable, Unit] =
    for
      runtime <- ZIO.runtime[Any]
      _ <- runWithHooks(runtime)
    yield ()

  private def runWithHooks(runtime: Runtime[Any]): ZIO[Any, Throwable, Unit] =
    // Hook that logs all tool calls before execution
    val loggingHook: HookCallback = {
      case input: HookInput.PreToolUse =>
        Console.printLine(s"[Hook] About to call tool: ${input.toolName.raw}").as(HookOutput.Continue())
      case input: HookInput.PostToolUse =>
        Console.printLine(s"[Hook] Tool completed: ${input.toolName.raw}").as(HookOutput.Continue())
      case _ =>
        ZIO.succeed(HookOutput.Continue())
    }

    // Hook that blocks dangerous Bash commands
    val securityHook: HookCallback = {
      case input: HookInput.PreToolUse if input.toolName == ToolName.Bash =>
        val inputStr = input.toolInput.toString
        if inputStr.contains("rm ") || inputStr.contains("sudo") then
          Console.printLine(s"[Security] Blocked dangerous command: $inputStr") *>
            ZIO.succeed(HookOutput.Block("Dangerous commands are not allowed"))
        else ZIO.succeed(HookOutput.Continue())
      case _ =>
        ZIO.succeed(HookOutput.Continue())
    }

    // Hook for permission requests - auto-approve Read, deny Write
    val permissionHook: HookCallback = {
      case input: HookInput.PermissionRequest =>
        input.toolName match
          case ToolName.Read =>
            Console.printLine(s"[Permission] Auto-approving Read tool") *>
              ZIO.succeed(HookOutput.Decision(approve = true, reason = Some("Read is safe")))
          case ToolName.Write =>
            Console.printLine(s"[Permission] Denying Write tool") *>
              ZIO.succeed(HookOutput.Decision(approve = false, reason = Some("Write not allowed")))
          case other =>
            Console.printLine(s"[Permission] Asking user for ${other.raw}") *>
              ZIO.succeed(HookOutput.Continue())
      case _ =>
        ZIO.succeed(HookOutput.Continue())
    }

    val options = AgentOptions.default
      .withModel("claude-sonnet-4-20250514")
      .withPermissionMode(PermissionMode.Default)
      .withMaxTurns(5)
      .withHook(HookEvent.PreToolUse, loggingHook)
      .withHook(HookEvent.PreToolUse, securityHook)
      .withHook(HookEvent.PostToolUse, loggingHook)
      .withHook(HookEvent.PermissionRequest, permissionHook)

    val program = ClaudeAgent
      .query("List the files in the current directory using ls", options)
      .tap(handleMessage)
      .runDrain

    program.provide(ClaudeAgent.live)

  private def handleMessage(msg: AgentMessage): Task[Unit] =
    msg match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        val text = message.content.collect { case ContentBlock.Text(t) => t }.mkString
        if text.nonEmpty then Console.printLine(s"Claude: $text")
        else ZIO.unit

      case AgentMessage.Result(ResultOutcome.Success(_, _, _, result, cost, _, _, _, _), _, _) =>
        Console.printLine(s"\n--- Result ---") *>
          Console.printLine(s"Output: $result") *>
          Console.printLine(s"Cost: $$${cost}")

      case AgentMessage.Result(ResultOutcome.Error(reason, _, _, _, _, _, _, _, errors), _, _) =>
        Console.printLine(s"\n--- Error: $reason ---") *>
          Console.printLine(s"Errors: ${errors.mkString(", ")}")

      case _ =>
        ZIO.unit
