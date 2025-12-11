package com.tjclp.claude.agent.examples

import zio._
import zio.stream._
import com.tjclp.claude.agent._
import com.tjclp.claude.agent.config._
import com.tjclp.claude.agent.messages._
import com.tjclp.claude.agent.session._

/** Example demonstrating the V2 Session API for multi-turn conversations.
  *
  * The Session API provides explicit send/receive semantics which is more ergonomic for multi-turn conversations
  * compared to the V1 generator-based approach.
  *
  * Key features:
  *   - Create sessions with `ClaudeSession.create()`
  *   - Resume sessions with `ClaudeSession.resume(sessionId)`
  *   - Send messages and receive streaming responses with `session.send(message)`
  *   - Proper resource cleanup with `session.close`
  *
  * Run with: mill examples.runMain com.tjclp.claude.agent.examples.SessionExample
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  *
  * @note
  *   This uses the unstable V2 API from the TypeScript SDK. The API may change.
  */
object SessionExample extends ZIOAppDefault:

  val run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for
        _ <- Console.printLine("Starting V2 Session API example...")
        _ <- Console.printLine("---")

        // Configure options
        options = AgentOptions.default
          .withModel("claude-sonnet-4-20250514")
          .withPermissionMode(PermissionMode.BypassPermissions)
          .withMaxTurns(5)

        // Create a session (automatically cleaned up by scoped)
        session <- ClaudeSession.create(options).withFinalizer(s =>
          s.close.ignoreLogged
        )

        _ <- Console.printLine(s"Session created: ${session.sessionId}")
        _ <- Console.printLine("")

        // First turn - ask a question
        _ <- Console.printLine("User: What is 2 + 2?")
        _ <- session.send("What is 2 + 2?").tap(handleMessage).runDrain
        _ <- Console.printLine("")

        // Second turn - follow-up question (session maintains context)
        _ <- Console.printLine("User: Now multiply that by 3")
        _ <- session.send("Now multiply that by 3").tap(handleMessage).runDrain
        _ <- Console.printLine("")

        // Third turn - another follow-up
        _ <- Console.printLine("User: What was my first question?")
        _ <- session.send("What was my first question?").tap(handleMessage).runDrain

        _ <- Console.printLine("")
        _ <- Console.printLine("--- Session complete ---")
      yield ()
    }

  private def handleMessage(msg: AgentMessage): Task[Unit] =
    msg match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        val text = message.content.collect { case ContentBlock.Text(t) => t }.mkString
        if text.nonEmpty then Console.printLine(s"Claude: $text")
        else ZIO.unit

      case AgentMessage.Result(ResultOutcome.Success(_, _, turns, _, cost, _, _, _, _), _, _) =>
        Console.printLine(s"[Turn completed, total cost so far: $$${cost}]")

      case AgentMessage.Result(ResultOutcome.Error(reason, _, _, _, _, _, _, _, errors), _, _) =>
        Console.printLine(s"[Error: $reason - ${errors.mkString(", ")}]")

      case _ =>
        ZIO.unit
