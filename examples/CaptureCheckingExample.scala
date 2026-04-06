package com.tjclp.scalagent.examples

import zio.*

/** Example demonstrating capture-checked capabilities for agent sandboxing.
  *
  * Scala 3's capture checking prevents capability leakage at compile time.
  * Capabilities (filesystem access, budget, spawn permits) cannot escape
  * their authorized scope.
  *
  * The actual capture-checked logic lives in `CaptureCheckedOps` (a separate
  * file with `language.experimental.captureChecking` enabled). This file
  * is the ZIO app shell that calls into it.
  *
  * Run with: EXAMPLE=captureChecking ./mill examples.bun
  */
object CaptureCheckingExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    for
      _ <- Console.printLine("=== Capture Checking: Sandboxed Agent Capabilities ===").orDie
      _ <- Console.printLine("").orDie

      _ <- Console.printLine("--- FileSandbox ---").orDie
      _ <- ZIO.attempt(CaptureCheckedOps.sandboxDemo()).orDie

      _ <- Console.printLine("").orDie
      _ <- Console.printLine("--- BudgetSlice ---").orDie
      _ <- ZIO.attempt(CaptureCheckedOps.budgetDemo()).orDie

      _ <- Console.printLine("").orDie
      _ <- Console.printLine("--- SpawnPermit ---").orDie
      _ <- ZIO.attempt(CaptureCheckedOps.permitDemo()).orDie

      _ <- Console.printLine("").orDie
      _ <- Console.printLine("--- Combined Sandbox ---").orDie
      _ <- ZIO.attempt(CaptureCheckedOps.combinedDemo()).orDie

      _ <- Console.printLine("").orDie
      _ <- Console.printLine("=== All capability scopes enforced at compile time ===").orDie
    yield ()
