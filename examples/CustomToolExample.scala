package com.tjclp.claude.agent.examples

import zio._
import zio.stream._
import zio.json._
import com.tjclp.claude.agent._
import com.tjclp.claude.agent.config.{AgentOptions, Model, PermissionMode}
import com.tjclp.claude.agent.messages._
import com.tjclp.claude.agent.tools._
import com.tjclp.claude.agent.mcp._

/** Example demonstrating custom MCP tool creation.
  *
  * This example shows how to:
  *   1. Define custom tools with type-safe input schemas
  *   2. Create an in-process MCP server with those tools
  *   3. Use the custom tools in a Claude agent query
  *
  * Run with: mill examples.runMain com.tjclp.claude.agent.examples.CustomToolExample
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object CustomToolExample extends ZIOAppDefault:

  // Define input types for our custom tools
  case class WeatherInput(location: String, unit: Option[String])
  object WeatherInput:
    given JsonDecoder[WeatherInput] = DeriveJsonDecoder.gen[WeatherInput]

  case class CalculatorInput(operation: String, a: Double, b: Double)
  object CalculatorInput:
    given JsonDecoder[CalculatorInput] = DeriveJsonDecoder.gen[CalculatorInput]

  case class LookupInput(query: String)
  object LookupInput:
    given JsonDecoder[LookupInput] = DeriveJsonDecoder.gen[LookupInput]

  val run: ZIO[Any, Throwable, Unit] =
    for
      runtime <- ZIO.runtime[Any]
      _ <- runWithCustomTools(runtime)
    yield ()

  private def runWithCustomTools(runtime: Runtime[Any]): ZIO[Any, Throwable, Unit] =
    // Define a weather tool
    val weatherTool = ToolBuilder[WeatherInput]("get_weather")
      .description("Get the current weather for a location")
      .schema(
        JsonSchema
          .obj(
            "location" -> JsonSchema.string,
            "unit" -> JsonSchema.enumOf("celsius", "fahrenheit")
          )
          .required("location")
      )
      .handler { input =>
        val unit = input.unit.getOrElse("celsius")
        val temp = if unit == "celsius" then "22" else "72"
        val symbol = if unit == "celsius" then "C" else "F"
        ZIO.succeed(
          ToolResult.Success(s"Weather in ${input.location}: Sunny, $temp°$symbol, humidity 45%")
        )
      }

    // Define a calculator tool
    val calculatorTool = ToolBuilder[CalculatorInput]("calculator")
      .description("Perform basic arithmetic operations")
      .schema(
        JsonSchema
          .obj(
            "operation" -> JsonSchema.enumOf("add", "subtract", "multiply", "divide"),
            "a" -> JsonSchema.number,
            "b" -> JsonSchema.number
          )
          .required("operation", "a", "b")
      )
      .handler { input =>
        input.operation match
          case "add" =>
            ZIO.succeed(ToolResult.Success(s"${input.a} + ${input.b} = ${input.a + input.b}"))
          case "subtract" =>
            ZIO.succeed(ToolResult.Success(s"${input.a} - ${input.b} = ${input.a - input.b}"))
          case "multiply" =>
            ZIO.succeed(ToolResult.Success(s"${input.a} * ${input.b} = ${input.a * input.b}"))
          case "divide" =>
            if input.b == 0 then ZIO.succeed(ToolResult.Error("Division by zero"))
            else ZIO.succeed(ToolResult.Success(s"${input.a} / ${input.b} = ${input.a / input.b}"))
          case _ =>
            ZIO.succeed(ToolResult.Error(s"Unknown operation: ${input.operation}"))
      }

    // Define a knowledge lookup tool
    val lookupTool = ToolBuilder[LookupInput]("knowledge_lookup")
      .description("Look up information from a knowledge base")
      .schema(
        JsonSchema.obj("query" -> JsonSchema.string).required("query")
      )
      .handler { input =>
        // Simulated knowledge base
        val knowledge = Map(
          "scala" -> "Scala is a programming language that combines object-oriented and functional programming.",
          "zio" -> "ZIO is a type-safe, composable library for async and concurrent programming in Scala.",
          "claude" -> "Claude is an AI assistant made by Anthropic."
        )

        val result = knowledge
          .find { case (k, _) => input.query.toLowerCase.contains(k) }
          .map(_._2)
          .getOrElse(s"No information found for: ${input.query}")

        ZIO.succeed(ToolResult.Success(result))
      }

    // Create an MCP server with our custom tools
    val mcpServer = McpServer.create(
      name = "custom-tools",
      tools = List(weatherTool, calculatorTool, lookupTool),
      runtime = runtime
    )

    val options = AgentOptions.default
      .withModel(Model.Sonnet4)
      .withPermissionMode(PermissionMode.DontAsk)
      .withMaxTurns(10)
      .withMcpServer("custom", mcpServer)

    Console.printLine("Starting agent with custom tools: get_weather, calculator, knowledge_lookup") *>
      Console.printLine("---") *>
      ClaudeAgent
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
