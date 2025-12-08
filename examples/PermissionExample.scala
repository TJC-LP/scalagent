package com.tjclp.claude.agent.examples

import zio._
import zio.stream._
import zio.json.ast.Json
import com.tjclp.claude.agent._
import com.tjclp.claude.agent.config._
import com.tjclp.claude.agent.messages._
import com.tjclp.claude.agent.permissions._

/** Example demonstrating the permission callback system.
  *
  * This example shows how to:
  *   1. Implement custom permission logic with CanUseTool
  *   2. Allow/deny tools based on context
  *   3. Use permission utilities for common patterns
  *
  * Run with: mill examples.runMain com.tjclp.claude.agent.examples.PermissionExample
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object PermissionExample extends ZIOAppDefault:

  val run: ZIO[Any, Throwable, Unit] =
    for
      runtime <- ZIO.runtime[Any]
      _ <- runWithPermissions(runtime)
    yield ()

  private def runWithPermissions(runtime: Runtime[Any]): ZIO[Any, Throwable, Unit] =
    // Custom permission handler with logging
    val customPermissionHandler: CanUseTool = (toolName, input, context) =>
      for
        _ <- Console.printLine(s"[Permission] Tool request: $toolName")
        _ <- Console.printLine(s"[Permission] Input: ${input.toString.take(100)}...")
        result <- toolName match
          // Always allow read operations
          case "Read" | "Glob" | "Grep" =>
            Console.printLine(s"[Permission] Allowing read tool: $toolName") *>
              ZIO.succeed(PermissionResult.Allow())

          // Allow Bash only for safe commands
          case "Bash" =>
            val inputStr = input.toString
            if inputStr.contains("ls") || inputStr.contains("pwd") || inputStr.contains("echo") then
              Console.printLine(s"[Permission] Allowing safe Bash command") *>
                ZIO.succeed(PermissionResult.Allow())
            else
              Console.printLine(s"[Permission] Denying Bash command (not in allowlist)") *>
                ZIO.succeed(
                  PermissionResult.Deny(
                    message = "Only ls, pwd, and echo commands are allowed",
                    interrupt = false
                  )
                )

          // Deny write operations
          case "Write" | "Edit" =>
            Console.printLine(s"[Permission] Denying write tool: $toolName") *>
              ZIO.succeed(
                PermissionResult.Deny(
                  message = "Write operations are not allowed in this session",
                  interrupt = false
                )
              )

          // Allow everything else with a warning
          case other =>
            Console.printLine(s"[Permission] Allowing other tool: $other") *>
              ZIO.succeed(PermissionResult.Allow())
      yield result

    val options = AgentOptions.default
      .withModel("claude-sonnet-4-20250514")
      .withPermissionMode(PermissionMode.Default)
      .withMaxTurns(5)
      .withCanUseTool(customPermissionHandler)

    Console.printLine("Starting agent with custom permission handler") *>
      Console.printLine("- Read/Glob/Grep: Always allowed") *>
      Console.printLine("- Bash: Only ls/pwd/echo allowed") *>
      Console.printLine("- Write/Edit: Always denied") *>
      Console.printLine("---") *>
      ClaudeAgent
        .query(
          "Please list the files in the current directory, then show me the current working directory",
          options
        )
        .tap(handleMessage)
        .runDrain
        .provide(ClaudeAgent.live)

  private def handleMessage(msg: AgentMessage): Task[Unit] =
    msg match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        val text = message.content.collect { case ContentBlock.Text(t) => t }.mkString
        val toolCalls = message.content.collect { case ContentBlock.ToolUse(_, name, _) =>
          s"[Tool Call] $name"
        }
        for
          _ <- ZIO.foreach(toolCalls)(call => Console.printLine(call))
          _ <- if text.nonEmpty then Console.printLine(s"Claude: $text") else ZIO.unit
        yield ()

      case AgentMessage.User(message, _, _, toolResult, _, _) =>
        toolResult match
          case Some(_) =>
            val results = message.content.collect { case ContentBlock.ToolResult(_, content, isError) =>
              val preview = content.take(200) + (if content.length > 200 then "..." else "")
              if isError then s"[Tool Error] $preview" else s"[Tool Result] $preview"
            }
            ZIO.foreach(results)(r => Console.printLine(r)).unit
          case None => ZIO.unit

      case AgentMessage.Result(ResultOutcome.Success(_, _, turns, result, cost, _, _, denials, _), _, _) =>
        Console.printLine(s"\n--- Completed in $turns turns ---") *>
          Console.printLine(s"Cost: $$${cost}") *>
          (if denials.nonEmpty then
             Console.printLine(s"Permission denials: ${denials.map(_.toolName).mkString(", ")}")
           else ZIO.unit)

      case AgentMessage.Result(ResultOutcome.Error(reason, _, _, _, _, _, _, denials, errors), _, _) =>
        Console.printLine(s"\n--- Error: $reason ---") *>
          Console.printLine(s"Errors: ${errors.mkString(", ")}") *>
          (if denials.nonEmpty then
             Console.printLine(s"Permission denials: ${denials.map(_.toolName).mkString(", ")}")
           else ZIO.unit)

      case _ =>
        ZIO.unit
