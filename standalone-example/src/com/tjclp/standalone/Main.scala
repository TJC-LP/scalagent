package com.tjclp.standalone

import com.tjclp.scalagent.*
import zio.*

// Standalone example: depends only on the published Scalagent JAR.
// All npm dependencies (claude-agent-sdk, zod, etc.) are resolved
// automatically from the JAR's embedded bun-dependencies.json manifest.
// No package.json, no manual `bun install`, no bunDeps declaration.
object Main extends ZIOAppDefault:

  val options = AgentOptions.default
    .withModel(Model.haiku)
    .withPermissionMode(PermissionMode.DontAsk)
    .withMaxTurns(PositiveInt.literal(1))

  val run: ZIO[Any, AgentError, Unit] =
    for
      _ <- Console.printLine("--- Standalone Scalagent Consumer ---").orDie
      _ <- Console.printLine("No package.json. No bunDeps. Just a JAR dependency.").orDie
      _ <- Console.printLine("").orDie
      answer <- Claude.ask("What is 2 + 2? Reply with just the number.", options)
      _ <- Console.printLine(s"Agent response: $answer").orDie
    yield ()
