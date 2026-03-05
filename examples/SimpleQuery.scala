package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import com.tjclp.scalagent.*

/** Simple example demonstrating the simplified Claude entry point.
  *
  * This example shows the most ergonomic patterns for using the SDK:
  * - Claude.ask() for one-shot questions
  * - Claude.query() for streaming responses
  * - Claude.conversation() for multi-turn chats
  *
  * Run with: mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object SimpleQuery extends ZIOAppDefault:

  val run: ZIO[Any, AgentError, Unit] =
    val options = AgentOptions.default
      .withModel(Model.haiku)
      .withPermissionMode(PermissionMode.DontAsk)
      .withMaxTurns(PositiveInt.literal(5)) // compile-time validated positive integer

    for
      _ <- Console.printLine("=== One-shot question with Claude.ask ===").orDie
      answer <- Claude.ask("What is 2 + 2? Reply with just the number.", options)
      _ <- Console.printLine(s"Answer: $answer").orDie

      _ <- Console.printLine("\n=== Streaming with Claude.query ===").orDie
      result <- Claude.query("Count from 1 to 5, one number per line.", options)
        .textOnly
        .tap(text => Console.printLine(s"  >> $text").orDie)
        .runCollect
        .map(_.mkString)
      _ <- Console.printLine(s"Collected: $result").orDie

      _ <- Console.printLine("\n=== Multi-turn conversation ===").orDie
      finalAnswer <- Claude.conversation(options) { session =>
        for
          first <- session.ask("What is 10 + 5? Just the number.")
          _ <- Console.printLine(s"First answer: $first").orDie
          second <- session.ask(s"Now double that number ($first). Just the number.")
          _ <- Console.printLine(s"Second answer: $second").orDie
        yield second
      }
      _ <- Console.printLine(s"Final: $finalAnswer").orDie
    yield ()
