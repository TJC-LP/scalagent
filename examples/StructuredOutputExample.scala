package com.tjclp.scalagent.examples

import zio._
import zio.json._
import zio.schema._
import com.tjclp.scalagent._
import com.tjclp.scalagent.config._
import com.tjclp.scalagent.errors._
import com.tjclp.scalagent.messages._

/** Example demonstrating structured outputs with compile-time schema derivation.
  *
  * This example shows how to:
  * - Define case classes with zio-schema derivation
  * - Use withStructuredOutput[T] on AgentOptions
  * - Parse typed responses with parseAs[T]
  *
  * Run with: EXAMPLE=structured mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object StructuredOutputExample extends ZIOAppDefault:

  // Define the structured output type with all required derivations
  case class CodeAnalysis(
      summary: String,
      complexity: String,
      suggestions: List[String],
      score: Int
  )

  object CodeAnalysis:
    // zio-schema for JSON Schema generation
    given Schema[CodeAnalysis] = DeriveSchema.gen[CodeAnalysis]
    // zio-json for parsing
    given JsonDecoder[CodeAnalysis] = DeriveJsonDecoder.gen[CodeAnalysis]
    given JsonEncoder[CodeAnalysis] = DeriveJsonEncoder.gen[CodeAnalysis]
    // Combine them for structured output
    given StructuredOutput[CodeAnalysis] = StructuredOutput.derive[CodeAnalysis]

  val run: ZIO[Any, Any, Unit] =
    val options = AgentOptions.default
      .withModel(Model.Sonnet4)
      .withPermissionMode(PermissionMode.DontAsk)
      .withMaxTurns(3)
      .withStructuredOutput[CodeAnalysis]

    val codeSnippet = """
      |def fibonacci(n: Int): Int =
      |  if n <= 1 then n
      |  else fibonacci(n - 1) + fibonacci(n - 2)
      """.stripMargin

    for
      _ <- Console.printLine("=== Structured Output Example ===").orDie
      _ <- Console.printLine("Analyzing code snippet with structured output...\n").orDie

      // Query with structured output enabled
      result <- Claude.queryComplete(
        s"""Analyze this Scala code and provide a structured analysis.
           |Rate complexity as "low", "medium", or "high".
           |Score from 0-100 based on code quality.
           |
           |```scala
           |$codeSnippet
           |```""".stripMargin,
        options
      )

      // Extract typed result
      _ <- result.outcome match
        case success: ResultOutcome.Success =>
          for
            _ <- Console.printLine(s"Raw result: ${success.result}").orDie
            _ <- Console.printLine("\nParsing structured output...").orDie
            parsed = success.parseAs[CodeAnalysis]
            _ <- parsed match
              case Right(analysis) =>
                Console.printLine(s"""
                  |Parsed Analysis:
                  |  Summary: ${analysis.summary}
                  |  Complexity: ${analysis.complexity}
                  |  Score: ${analysis.score}/100
                  |  Suggestions:
                  |${analysis.suggestions.map(s => s"    - $s").mkString("\n")}
                  |
                  |Cost: $$${success.totalCostUsd}
                  |""".stripMargin).orDie
              case Left(error) =>
                Console.printLine(s"Parse error: $error").orDie
          yield ()

        case error: ResultOutcome.Error =>
          Console.printLine(s"Query failed: ${error.errors.mkString(", ")}").orDie
    yield ()
