package com.tjclp.scalagent.examples

import zio.*
import com.tjclp.scalagent.*

/** Example demonstrating custom subagent definitions.
  *
  * This example shows how to:
  * - Define custom agents with AgentDefinition
  * - Use factory methods (readOnly, fullAccess)
  * - Restrict agent tool access
  * - Set agent-specific models with AgentModel
  *
  * Run with: EXAMPLE=subagent mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object SubagentExample extends ZIOAppDefault:

  // Define a code reviewer agent with limited tools
  val codeReviewer = AgentDefinition(
    description = "Expert code reviewer for security and quality analysis",
    prompt = """You are a senior code reviewer specializing in:
      |1. Security vulnerabilities (injection, XSS, etc.)
      |2. Code quality and maintainability
      |3. Performance issues
      |4. Best practices
      |
      |When reviewing code, provide specific line references and
      |actionable suggestions. Be concise but thorough.""".stripMargin,
    tools = Some(List(ToolName.Read, ToolName.Grep, ToolName.Glob)),
    model = Some(AgentModel.Haiku) // Use faster model for quick reviews
  )

  // Define a documentation writer with full access
  val docWriter = AgentDefinition.fullAccess(
    description = "Technical documentation specialist",
    prompt = """You are a technical writer who creates clear, comprehensive
      |documentation. Focus on:
      |- Clear explanations for developers
      |- Code examples where helpful
      |- Proper formatting and structure""".stripMargin,
    model = Some(AgentModel.Sonnet)
  )

  // Define a read-only researcher using factory method
  val researcher = AgentDefinition.readOnly(
    description = "Research assistant for exploring codebases",
    prompt = "You research and summarize code patterns and architecture."
  )

  val run: ZIO[Any, Any, Unit] =
    // Configure options with multiple agents
    val options = AgentOptions.default
      .withModel(Model.sonnet)
      .withPermissionMode(PermissionMode.DontAsk)
      .withMaxTurns(5)
      // Add agents using different methods
      .withAgent("code-reviewer", codeReviewer)
      .withAgent("doc-writer", docWriter)
      // Convenience method for read-only agents
      .withReadOnlyAgent(
        "analyzer",
        "Analyzes code patterns and structure",
        "You analyze code structure and identify patterns."
      )

    for
      _ <- Console.printLine("=== Subagent Configuration Example ===").orDie
      _ <- Console.printLine("\nConfigured agents:").orDie

      // Display configured agents
      _ <- ZIO.foreach(options.agents.toList) { case (name, agent) =>
        Console.printLine(s"""
          |Agent: $name
          |  Description: ${agent.description.take(50)}...
          |  Model: ${agent.model.map(_.raw).getOrElse("inherit")}
          |  Tools: ${agent.tools.map(_.map(_.raw).mkString(", ")).getOrElse("all (inherited)")}
          |""".stripMargin).orDie
      }

      _ <- Console.printLine("\n--- Querying with subagent-aware prompt ---").orDie

      // Query that might use subagents
      result <- Claude.queryComplete(
        """Review the following code snippet for potential issues.
          |Use the code-reviewer agent if available.
          |
          |```scala
          |def processInput(input: String): String =
          |  val query = s"SELECT * FROM users WHERE name = '$input'"
          |  database.execute(query)
          |```""".stripMargin,
        options
      )

      _ <- result.outcome match
        case success: ResultOutcome.Success =>
          Console.printLine(s"""
            |Review Result:
            |${success.result}
            |
            |Turns: ${success.numTurns}
            |Cost: $$${success.totalCostUsd}
            |""".stripMargin).orDie
        case error: ResultOutcome.Error =>
          Console.printLine(s"Error: ${error.errors.mkString(", ")}").orDie
    yield ()
