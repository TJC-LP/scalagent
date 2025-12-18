package com.tjclp.scalagent.examples

import zio._
import zio.json._
import com.tjclp.scalagent._
import com.tjclp.scalagent.config.{AgentOptions, Model, PermissionMode}
import com.tjclp.scalagent.messages._
import com.tjclp.scalagent.tools._
import com.tjclp.scalagent.macros._

/** Example demonstrating macro-based tool definition.
  *
  * This example shows how to define MCP tools with minimal boilerplate using @Tool and @Param
  * annotations. Compare with CustomToolExample.scala to see the ~60% reduction in boilerplate.
  *
  * Key features:
  *   - @Tool annotation for defining tools
  *   - @Param annotation for parameter descriptions
  *   - Automatic JSON schema generation
  *   - Support for structured output with ToolResult.json()
  *   - Support for multimodal output with ToolResult.multi
  *
  * Run with: mill examples.runMain com.tjclp.scalagent.examples.MacroToolExample
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object MacroToolExample extends ZIOAppDefault:

  // Define tools with minimal ceremony using @Tool and @Param annotations
  object MyTools:

    // Structured output data class - Claude can parse and use fields directly
    case class WeatherData(location: String, temp: Int, unit: String, condition: String)
    object WeatherData:
      given JsonEncoder[WeatherData] = DeriveJsonEncoder.gen[WeatherData]

    // Scala 3 enums for type-safe tool parameters!
    enum TempUnit:
      case Celsius, Fahrenheit

    enum Operation:
      case Add, Subtract, Multiply, Divide

    @Tool("get_weather", "Get the current weather for a location")
    def getWeather(
        @Param("City or location name") location: String,
        @Param("Temperature unit") unit: Option[TempUnit] = None
    ): Task[ToolResult] =
      val u = unit.getOrElse(TempUnit.Celsius)
      val (temp, symbol) = u match
        case TempUnit.Fahrenheit => (72, "F")
        case TempUnit.Celsius    => (22, "C")
      // Return structured JSON - Claude can parse and use the fields!
      ZIO.succeed(ToolResult.json(WeatherData(location, temp, u.toString, "Sunny")))

    // Type-safe calculator using Scala 3 enum for operations
    @Tool("calculator", "Perform basic arithmetic operations")
    def calculate(
        @Param("Arithmetic operation") operation: Operation,
        @Param("First number") a: Double,
        @Param("Second number") b: Double
    ): Task[ToolResult] =
      operation match
        case Operation.Add =>
          ZIO.succeed(ToolResult.text(s"$a + $b = ${a + b}"))
        case Operation.Subtract =>
          ZIO.succeed(ToolResult.text(s"$a - $b = ${a - b}"))
        case Operation.Multiply =>
          ZIO.succeed(ToolResult.text(s"$a * $b = ${a * b}"))
        case Operation.Divide =>
          if b == 0 then ZIO.succeed(ToolResult.error("Division by zero"))
          else ZIO.succeed(ToolResult.text(s"$a / $b = ${a / b}"))

    @Tool("knowledge_lookup", "Look up information from a knowledge base")
    def lookup(
        @Param("Search query") query: String
    ): Task[ToolResult] =
      // Simulated knowledge base
      val knowledge = Map(
        "scala" -> "Scala is a programming language that combines object-oriented and functional programming.",
        "zio" -> "ZIO is a type-safe, composable library for async and concurrent programming in Scala.",
        "claude" -> "Claude is an AI assistant made by Anthropic."
      )

      val result = knowledge
        .find { case (k, _) => query.toLowerCase.contains(k) }
        .map(_._2)
        .getOrElse(s"No information found for: $query")

      ZIO.succeed(ToolResult.text(result))

  val run: ZIO[Any, Throwable, Unit] =
    for
      runtime <- ZIO.runtime[Any]

      // One-liner server creation from annotated object!
      // This scans MyTools for @Tool-annotated methods and creates ToolDefs automatically
      server = ToolMacros.createServer[MyTools.type]("macro-tools", runtime)

      options = AgentOptions.default
        .withModel(Model.Sonnet4)
        .withPermissionMode(PermissionMode.BypassPermissions)
        .withMaxTurns(10)
        .withMcpServer("tools", server)

      _ <- Console.printLine(
        "Starting agent with macro-defined tools: get_weather, calculator, knowledge_lookup"
      )
      _ <- Console.printLine("---")
      _ <- ClaudeAgent
        .query(
          """Using the available tools:
            |1. What's the weather in Tokyo?
            |2. What is 15 multiplied by 7?
            |3. Tell me about ZIO
            |
            |Please use the tools to answer these questions.""".stripMargin,
          options
        )
        .tap(handleMessage)
        .runDrain
        .provide(ClaudeAgent.live)
    yield ()

  private def handleMessage(msg: AgentMessage): Task[Unit] =
    msg match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        val text = message.content.collect { case ContentBlock.Text(t) => t }.mkString
        val toolCalls = message.content.collect { case ContentBlock.ToolUse(id, name, _) =>
          s"[Calling $name]"
        }
        for
          _ <- ZIO.foreach(toolCalls)(call => Console.printLine(call))
          _ <- if text.nonEmpty then Console.printLine(s"Claude: $text") else ZIO.unit
        yield ()

      case AgentMessage.User(message, _, _, toolResult, _, _) =>
        toolResult match
          case Some(_) =>
            val results = message.content.collect { case ContentBlock.ToolResult(_, content, isError) =>
              if isError then s"[Tool Error] $content" else s"[Tool Result] $content"
            }
            ZIO.foreach(results)(r => Console.printLine(r)).unit
          case None => ZIO.unit

      case AgentMessage.Result(ResultOutcome.Success(_, _, turns, result, cost, _, _, _, _), _, _) =>
        Console.printLine(s"\n--- Completed in $turns turns ---") *>
          Console.printLine(s"Cost: $$${cost}")

      case AgentMessage.Result(ResultOutcome.Error(reason, _, _, _, _, _, _, _, errors), _, _) =>
        Console.printLine(s"\n--- Error: $reason ---") *>
          Console.printLine(s"Errors: ${errors.mkString(", ")}")

      case _ =>
        ZIO.unit
