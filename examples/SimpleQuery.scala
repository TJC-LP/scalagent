package com.tjclp.claude.agent.examples

import zio._
import zio.stream._
import com.tjclp.claude.agent._
import com.tjclp.claude.agent.config._
import com.tjclp.claude.agent.errors._
import com.tjclp.claude.agent.messages._

/** Simple example demonstrating basic Claude Agent SDK usage.
  *
  * This example shows both the verbose pattern-matching approach and
  * the new ergonomic extension methods for comparison.
  *
  * Run with: mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object SimpleQuery extends ZIOAppDefault:

  val run: ZIO[Any, AgentError, Unit] =
    val options = AgentOptions.default
      .withModel("claude-sonnet-4-20250514")
      .withPermissionMode(PermissionMode.DontAsk)
      .withMaxTurns(5)

    val program = for
      _ <- Console.printLine("=== Using new ergonomic APIs ===").orDie

      // New: Use collectResult to get QueryResult with all messages
      result <- ClaudeAgent
        .query("What is 2 + 2? Reply with just the number.", options)
        .tap(handleMessageErgonomic)
        .collectResult

      // New: Use QueryResult combinators
      _ <- Console.printLine(s"\n--- QueryResult ---").orDie
      _ <- Console.printLine(s"Success: ${result.isSuccess}").orDie
      _ <- Console.printLine(s"Cost: $$${result.cost}").orDie
      _ <- Console.printLine(s"Turns: ${result.turns}").orDie
      _ <- Console.printLine(s"All text: ${result.allText}").orDie

      // New: Get typed result or fail
      text <- result.textOrFail
      _ <- Console.printLine(s"Final answer: $text").orDie
    yield ()

    program.provide(ClaudeAgent.live)

  /** Handle messages using new ergonomic extension methods */
  private def handleMessageErgonomic(msg: AgentMessage): IO[AgentError, Unit] =
    // Use extension methods instead of verbose pattern matching
    val effect = msg.text match
      case Some(text) if msg.isAssistant =>
        Console.printLine(s"Claude: $text")
      case _ =>
        // For Result messages, use the outcome extension methods
        msg.asResult match
          case Some(outcome) =>
            val status = if outcome.isSuccess then "Success" else "Error"
            Console.printLine(s"[$status] Cost: $$${outcome.totalCostUsd}, Turns: ${outcome.numTurns}")
          case None =>
            ZIO.unit
    effect.mapError(e => AgentError.Unknown(e.getMessage, Some(e)))
