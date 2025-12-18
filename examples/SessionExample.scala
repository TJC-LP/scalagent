package com.tjclp.scalagent.examples

import zio._
import zio.stream._
import com.tjclp.scalagent._
import com.tjclp.scalagent.config.{AgentOptions, Model, PermissionMode}
import com.tjclp.scalagent.errors._
import com.tjclp.scalagent.messages._
import com.tjclp.scalagent.session._

/** Example demonstrating the V2 Session API for multi-turn conversations.
  *
  * The Session API provides explicit send/receive semantics which is more ergonomic for multi-turn conversations
  * compared to the V1 generator-based approach.
  *
  * Key features:
  *   - Create sessions with `ClaudeSession.create()`
  *   - Resume sessions with `ClaudeSession.resume(sessionId)`
  *   - Send messages and receive streaming responses with `session.send(message)`
  *   - Simple text responses with `session.ask(message)` (no streaming)
  *   - Proper resource cleanup with `session.close`
  *
  * Run with: mill examples.runMain com.tjclp.scalagent.examples.SessionExample
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  *
  * @note
  *   This uses the unstable V2 API from the TypeScript SDK. The API may change.
  */
object SessionExample extends ZIOAppDefault:

  val run: ZIO[Any, AgentError, Unit] =
    ZIO.scoped {
      for
        _ <- Console.printLine("Starting V2 Session API example...").orDie
        _ <- Console.printLine("---").orDie

        // Configure options and create session
        options = AgentOptions.default
          .withModel(Model.Sonnet4)
          .withPermissionMode(PermissionMode.BypassPermissions)
          .withMaxTurns(5)
        session <- ClaudeSession.create(options).withFinalizer(s =>
          s.close.ignoreLogged
        )

        _ <- Console.printLine(s"Session created: ${session.sessionId}").orDie
        _ <- Console.printLine("").orDie

        // First turn - use ask() for simple text response
        _ <- Console.printLine("User: What is 2 + 2?").orDie
        answer1 <- session.ask("What is 2 + 2?")
        _ <- Console.printLine(s"Claude: $answer1").orDie
        _ <- Console.printLine("").orDie

        // Second turn - use ask() again (session maintains context)
        _ <- Console.printLine("User: Now multiply that by 3").orDie
        answer2 <- session.ask("Now multiply that by 3")
        _ <- Console.printLine(s"Claude: $answer2").orDie
        _ <- Console.printLine("").orDie

        // Third turn - streaming with ergonomic extension methods
        _ <- Console.printLine("User: What was my first question?").orDie
        _ <- session
          .send("What was my first question?")
          .tap(msg => handleMessage(msg))
          .runDrain

        _ <- Console.printLine("").orDie
        _ <- Console.printLine("--- Session complete ---").orDie
      yield ()
    }

  /** Handle messages using new ergonomic extension methods */
  private def handleMessage(msg: AgentMessage): IO[AgentError, Unit] =
    // Use extension methods instead of verbose pattern matching
    val effect = msg.text match
      case Some(text) if msg.isAssistant =>
        Console.printLine(s"Claude (streaming): $text")
      case _ =>
        msg.asResult.fold(ZIO.unit) { outcome =>
          Console.printLine(s"[Cost: $$${outcome.totalCostUsd}]")
        }
    effect.mapError(e => AgentError.Unknown(e.getMessage, Some(e)))

  // Compare: old verbose approach vs new ergonomic approach
  //
  // OLD (verbose pattern matching):
  // msg match
  //   case AgentMessage.Assistant(message, _, _, _, _) =>
  //     val text = message.content.collect { case ContentBlock.Text(t) => t }.mkString
  //     Console.printLine(s"Claude: $text")
  //   case AgentMessage.Result(ResultOutcome.Success(_, _, _, _, cost, _, _, _, _), _, _) =>
  //     Console.printLine(s"Cost: $cost")
  //   case _ => ZIO.unit
  //
  // NEW (extension methods):
  // msg.text.fold(ZIO.unit)(text => Console.printLine(s"Claude: $text"))
  // msg.asResult.fold(ZIO.unit)(o => Console.printLine(s"Cost: ${o.totalCostUsd}"))
