package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import zio.json.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.config.{AgentOptions, Model, PermissionMode}
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.tools.*
import com.tjclp.scalagent.mcp.*

/** Example demonstrating custom MCP tool creation.
  *
  * This example shows how to:
  *   1. Define custom tools with type-safe input schemas
  *   2. Create an in-process MCP server with those tools
  *   3. Use the custom tools in a Claude agent query
  *
  * Run with: mill examples.runMain com.tjclp.scalagent.examples.CustomToolExample
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object CustomToolExample extends ZIOAppDefault:

  // Define input types for our custom tools
  case class WeatherInput(location: String, unit: Option[String])
  object WeatherInput:
    given JsonDecoder[WeatherInput] = DeriveJsonDecoder.gen[WeatherInput]
    given ToolInput[WeatherInput] = ToolInput.derive[WeatherInput]

  case class CalculatorInput(operation: String, a: Double, b: Double)
  object CalculatorInput:
    given JsonDecoder[CalculatorInput] = DeriveJsonDecoder.gen[CalculatorInput]
    given ToolInput[CalculatorInput] = ToolInput.derive[CalculatorInput]

  case class LookupInput(query: String)
  object LookupInput:
    given JsonDecoder[LookupInput] = DeriveJsonDecoder.gen[LookupInput]
    given ToolInput[LookupInput] = ToolInput.derive[LookupInput]

  enum RichContentMode derives JsonDecoder:
    case Ok, Media, Error

  case class RichContentInput(mode: RichContentMode)
  object RichContentInput:
    given JsonDecoder[RichContentInput] = DeriveJsonDecoder.gen[RichContentInput]
    given ToolInput[RichContentInput] = ToolInput.derive[RichContentInput]

  val run: ZIO[Any, Throwable, Unit] =
    for
      runtime <- ZIO.runtime[Any]
      _ <- runWithCustomTools(runtime)
    yield ()

  private def runWithCustomTools(runtime: Runtime[Any]): ZIO[Any, Throwable, Unit] =
    // Define a weather tool
    val weatherTool = ToolDef
      .fromInput[WeatherInput]("get_weather", "Get the current weather for a location") { input =>
        val unit = input.unit.getOrElse("celsius")
        val temp = if unit == "celsius" then "22" else "72"
        val symbol = if unit == "celsius" then "C" else "F"
        ZIO.succeed(
          ToolResult.Success(s"Weather in ${input.location}: Sunny, $temp°$symbol, humidity 45%")
        )
      }

    // Define a calculator tool
    val calculatorTool = ToolDef
      .fromInput[CalculatorInput]("calculator", "Perform basic arithmetic operations") { input =>
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
    val lookupTool = ToolDef
      .fromInput[LookupInput]("knowledge_lookup", "Look up information from a knowledge base") { input =>
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

    // Define a rich content tool (text + image + audio + resources)
    val richContentTool = ToolDef
      .fromInput[RichContentInput]("rich_content_demo", "Return rich MCP content blocks") { input =>
        input.mode match
          case RichContentMode.Error =>
            ZIO.succeed(
              ToolResult.errorContents(
                ToolContent.Text("Unable to render rich content."),
                ToolContent.ResourceLink(
                  uri = "https://example.com/troubleshooting",
                  description = Some("Troubleshooting guide")
                )
              )
            )
          case RichContentMode.Media =>
            val pngBase64 = ToolFiles.readBase64("examples/resources/demo.png")
            val wavBase64 = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAACJWAAACABAAZGF0YQAAAAA="
            ZIO.succeed(
              ToolResult.multi
                .text("Here is rich content: image, audio, resource link, and embedded text.")
                .image(pngBase64, mime = "image/png")
                .audio(wavBase64, mime = "audio/wav")
                .resourceLink("https://example.com/spec", description = Some("Spec link"))
                .resourceText("urn:example:note", "Embedded resource text", mimeType = Some("text/plain"))
                .build
            )
          case RichContentMode.Ok =>
            ZIO.succeed(
              ToolResult.multi
                .text("Here is rich content: a resource link and embedded text.")
                .resourceLink("https://example.com/spec", description = Some("Spec link"))
                .resourceText("urn:example:note", "Embedded resource text", mimeType = Some("text/plain"))
                .build
            )
      }

    // Create an MCP server with our custom tools
    val mcpServer = McpServer.create(
      name = "custom-tools",
      tools = List(weatherTool, calculatorTool, lookupTool, richContentTool),
      runtime = runtime
    )

    val options = AgentOptions.default
      .withModel(Model.Sonnet4_5)
      .withPermissionMode(PermissionMode.BypassPermissions)
      .withMaxTurns(10)
      .withMcpServer("custom", mcpServer)

    Console.printLine("Starting agent with custom tools: get_weather, calculator, knowledge_lookup, rich_content_demo") *>
      Console.printLine("---") *>
      ClaudeAgent
        .query(
          """Using the available tools:
            |1. What's the weather in Tokyo?
            |2. What is 15 multiplied by 7?
            |3. Tell me about ZIO
            |4. Show a rich content response using rich_content_demo (mode=Media) and summarize the image.
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
