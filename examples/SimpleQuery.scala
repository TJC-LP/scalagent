package com.tjclp.claude.agent.examples

import zio._
import zio.stream._
import com.tjclp.claude.agent._
import com.tjclp.claude.agent.config._
import com.tjclp.claude.agent.messages._

/** Simple example demonstrating basic Claude Agent SDK usage.
  *
  * This example:
  *   1. Creates a ClaudeAgent instance
  *   2. Sends a simple query
  *   3. Streams and prints the responses
  *   4. Shows the final result with cost information
  *
  * Run with: mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object SimpleQuery extends ZIOAppDefault:

  val run: ZIO[Any, Throwable, Unit] =
    val options = AgentOptions.default
      .withModel("claude-sonnet-4-20250514")
      .withPermissionMode(PermissionMode.DontAsk)
      .withMaxTurns(5)

    val program = ClaudeAgent
      .query("What is 2 + 2? Reply with just the number.", options)
      .tap(handleMessage)
      .runDrain

    program.provide(ClaudeAgent.live)

  private def handleMessage(msg: AgentMessage): Task[Unit] =
    msg match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        val text = extractText(message)
        Console.printLine(s"Claude: $text")

      case AgentMessage.Result(ResultOutcome.Success(_, _, _, result, cost, _, _, _, _), _, _) =>
        Console.printLine(s"\n--- Result ---") *>
          Console.printLine(s"Output: $result") *>
          Console.printLine(s"Cost: $$${cost}")

      case AgentMessage.Result(ResultOutcome.Error(reason, _, _, _, _, _, _, _, errors), _, _) =>
        Console.printLine(s"\n--- Error ---") *>
          Console.printLine(s"Reason: $reason") *>
          Console.printLine(s"Errors: ${errors.mkString(", ")}")

      case AgentMessage.System(event, _, _) =>
        event match
          case SystemEvent.Init(_, version, cwd, tools, _, model, _, _, _, _, _, _, _) =>
            Console.printLine(s"[System] Initialized v$version") *>
              Console.printLine(s"[System] Model: $model, CWD: $cwd") *>
              Console.printLine(s"[System] Tools: ${tools.take(5).mkString(", ")}...")
          case _ =>
            ZIO.unit

      case AgentMessage.StreamEvent(_, _, _, _) =>
        ZIO.unit // Ignore partial streaming events in this example

      case AgentMessage.ToolProgress(_, toolName, _, elapsed, _, _) =>
        Console.printLine(s"[Tool] $toolName running (${elapsed}s)")

      case _ =>
        ZIO.unit

  /** Extract text content from an assistant message */
  private def extractText(msg: ApiAssistantMessage): String =
    msg.content.collect { case ContentBlock.Text(text) => text }.mkString
