package com.tjclp.scalagent.examples

import zio.*
import zio.json.*
import com.tjclp.scalagent.*

/** Example demonstrating structured outputs with compile-time schema derivation.
  *
  * This example shows the simplified API:
  * - Define case classes with `derives JsonDecoder`
  * - Use @description annotations for field documentation
  * - Use StructuredOutput.derive[T] to generate JSON Schema
  * - Parse typed responses with parseAs[T]
  *
  * Run with: ./mill examples.structured.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set when Claude Code auth is not already available.
  */
object StructuredOutputExample extends ZIOAppDefault:

  // Define the structured output type - much simpler now!
  // Just add `derives JsonDecoder` and optional @description annotations
  case class CodeAnalysis(
      @description("A brief summary of the code's purpose and functionality")
      summary: String,
      @description("Complexity level: 'low', 'medium', or 'high'")
      complexity: String,
      @description("List of specific improvement suggestions")
      suggestions: List[String],
      @description("Quality score from 0-100")
      score: Int
  ) derives JsonDecoder

  // Single line to derive StructuredOutput - generates JSON Schema with descriptions
  given StructuredOutput[CodeAnalysis] = StructuredOutput.derive[CodeAnalysis]

  val run: ZIO[Any, Any, Unit] =
    val options = AgentOptions.default
      .withModel(Model.sonnet)
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
