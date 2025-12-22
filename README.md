# Scalagent

A type-safe Scala.js SDK for the [`@anthropic-ai/claude-agent-sdk`](https://www.npmjs.com/package/@anthropic-ai/claude-agent-sdk), providing idiomatic ZIO-based access to Claude's agentic capabilities.

## Features

- **ZIO + ZStream** integration for purely functional streaming
- **Structured outputs** with compile-time JSON Schema generation
- **Type-safe message ADT** mirroring the SDK's discriminated unions
- **Fluent configuration builders** with Scala-native API
- **Multi-turn conversations** via session management
- **Tool definition DSL** for custom MCP tools

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/com.tjclp/scalagent_sjs1_3.svg)](https://central.sonatype.com/artifact/com.tjclp/scalagent_sjs1_3)

### Mill

```scala
def ivyDeps = Seq(
  mvn"com.tjclp::scalagent::0.1.0"
)
```

### SBT

```scala
libraryDependencies += "com.tjclp" %%% "scalagent" % "0.1.0"
```

### Maven

```xml
<dependency>
  <groupId>com.tjclp</groupId>
  <artifactId>scalagent_sjs1_3</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Requirements

- Mill build tool
- Bun runtime (or Node.js 18+)
- Scala 3.3.x
- `ANTHROPIC_API_KEY` environment variable

## Quick Start

### Simple One-Shot Query

```scala
import com.tjclp.scalagent.*
import zio.*

object MyApp extends ZIOAppDefault:
  val run =
    for
      answer <- Claude.ask("What is 2 + 2?")
      _      <- Console.printLine(s"Answer: $answer")
    yield ()
```

### Streaming Responses

```scala
import com.tjclp.scalagent.*
import com.tjclp.scalagent.config.*
import zio.*

object StreamingApp extends ZIOAppDefault:
  val run =
    for
      _ <- Console.printLine("Counting to 5...")
      _ <- Claude.query("Count from 1 to 5, one number per line")
             .textOnly
             .foreach(text => Console.print(text).orDie)
    yield ()
```

### Multi-Turn Conversation

```scala
import com.tjclp.scalagent.*
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.session.*
import zio.*

object ConversationApp extends ZIOAppDefault:
  val run =
    for
      session  <- ClaudeSession.create(AgentOptions.default.withModel(Model.Sonnet4_5))
      _        <- session.send("Remember the number 42").runDrain
      answer   <- session.ask("What number did I ask you to remember?")
      _        <- Console.printLine(s"Claude remembered: $answer")
    yield ()
```

### Structured Output

Get type-safe responses with compile-time JSON Schema generation:

```scala
import com.tjclp.scalagent.*
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.macros.description
import zio.*
import zio.json.*

// Define your output type with optional field descriptions
case class Analysis(
  @description("Brief summary of findings") summary: String,
  @description("Quality score from 0-100") score: Int,
  suggestions: List[String]
) derives JsonDecoder

// Single-line schema derivation
given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]

object AnalysisApp extends ZIOAppDefault:
  val run =
    val options = AgentOptions.default
      .withModel(Model.Sonnet4_5)
      .withStructuredOutput[Analysis]

    for
      result  <- Claude.queryComplete("Analyze this code...", options)
      analysis = result.outcome match
        case s: ResultOutcome.Success => s.parseAs[Analysis]
        case e: ResultOutcome.Error   => Left(e.errors.mkString(", "))
      _       <- Console.printLine(s"Analysis: $analysis")
    yield ()
```

## Building

```bash
# Install dependencies with Bun
bun install

# Compile the project
./mill agent.compile

# Run the example (compiles and runs with Bun)
bun run run

# Or manually:
./mill examples.fastLinkJS
bun run out/examples/fastLinkJS.dest/main.js
```

## Configuration

Use `AgentOptions` to configure queries:

```scala
val options = AgentOptions.default
  .withModel(Model.Sonnet4_5)
  .withMaxTurns(10)
  .withMaxBudgetUsd(0.50)
  .withPermissionMode(PermissionMode.AcceptEdits)
  .withMcpServer("myserver", McpServerConfig.stdio("node", "server.js"))
```

### Available Options

| Option | Description |
|--------|-------------|
| `withModel(m)` | Set the model to use |
| `withModelId(id)` | Set a custom/new model ID |
| `withMaxTurns(n)` | Limit number of conversation turns |
| `withMaxBudgetUsd(b)` | Set maximum cost budget |
| `withPermissionMode(pm)` | Control permission handling |
| `withMcpServer(name, config)` | Add an MCP server |
| `withBypassPermissions` | Bypass all permission checks (dangerous!) |
| `withIncludePartialMessages` | Include streaming partial messages |
| `withStructuredOutput[T]` | Enable structured output with type-safe parsing |

### Permission Modes

- `Default` - Prompt user for each tool use
- `AcceptEdits` - Auto-accept file edits
- `BypassPermissions` - Skip all permission checks
- `Plan` - Plan mode without execution
- `DontAsk` - Deny unpermitted tools without prompting

## Message Types

The `AgentMessage` enum represents all message types from the SDK:

```scala
enum AgentMessage:
  case Assistant(message, parentToolUseId, error, uuid, sessionId)
  case User(message, parentToolUseId, isSynthetic, toolUseResult, uuid, sessionId)
  case Result(outcome, uuid, sessionId)
  case System(event, uuid, sessionId)
  case StreamEvent(event, parentToolUseId, uuid, sessionId)
  case ToolProgress(toolUseId, toolName, parentToolUseId, elapsedTimeSeconds, uuid, sessionId)
```

### Result Outcomes

```scala
enum ResultOutcome:
  case Success(durationMs, durationApiMs, numTurns, result, totalCostUsd, usage, ...)
  case Error(reason, durationMs, durationApiMs, numTurns, totalCostUsd, usage, errors, ...)
```

## Advanced Usage

### Raw Query Access

For advanced control (interruption, permission mode changes):

```scala
for
  queryStream <- ClaudeAgent.queryRaw("Complex task...")
  fiber <- queryStream.messages.foreach(handleMessage).fork
  _ <- ZIO.sleep(30.seconds)
  _ <- queryStream.interrupt  // Cancel the query
  _ <- fiber.join
yield ()
```

### Custom Tool Definitions (Type-Safe)

```scala
import com.tjclp.scalagent.tools.*
import zio.json.*

case class WeatherInput(location: String, unit: Option[String]) derives JsonDecoder
object WeatherInput:
  given ToolInput[WeatherInput] = ToolInput.derive[WeatherInput]

val weatherTool = ToolDef.fromInput[WeatherInput](
  name = "get_weather",
  description = "Get current weather for a location"
) { input =>
  fetchWeather(input.location, input.unit.getOrElse("celsius")).map(ToolResult.Success(_))
}
```

### Rich Tool Results (Multimodal + Errors)

Tools can return arrays of content blocks (text, image, audio, resources), and you can emit
custom error content when something fails:

```scala
val richTool = ToolDef.fromInput[WeatherInput](
  name = "rich_content_demo",
  description = "Return rich MCP content blocks"
) { _ =>
  val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/wIAAgMBAp9W8Z8AAAAASUVORK5CYII="
  ToolResult.multi
    .text("Here is rich content.")
    .image(pngBase64, mime = "image/png")
    .resourceLink("https://example.com/spec", description = Some("Spec link"))
    .build
}

val errorTool = ToolDef.fromInput[WeatherInput](
  name = "rich_error_demo",
  description = "Return rich error content"
) { _ =>
  ToolResult.errorContents(
    ToolContent.Text("Something went wrong."),
    ToolContent.ResourceLink("https://example.com/help")
  )
}
```

### Macro Tool Definitions (@Tool)

Prefer the macro-based style when you want minimal boilerplate and inline parameter docs.
Return types can be `ToolResult`, `String`, `Task[ToolResult]`, or `Task[String]` (strings are wrapped as `ToolResult.text`).

```scala
import com.tjclp.scalagent.macros.*
import com.tjclp.scalagent.tools.*
import zio.*

object MyTools:
  enum Unit:
    case Celsius, Fahrenheit

  @Tool("get_weather", "Get the current weather for a location")
  def getWeather(
      @Param("City or location name") location: String,
      @Param("Temperature unit") unit: Option[Unit] = None
  ): String =
    val u = unit.getOrElse(Unit.Celsius)
    s"Weather in $location: 22°${u.toString.take(1)}"

// One-liner server creation from annotated object
val server = ToolMacros.createServer[MyTools.type]("macro-tools", runtime)
```

## Architecture

```
┌─────────────────────────────────────────┐
│         User Application                │
├─────────────────────────────────────────┤
│     Idiomatic Scala API (ZIO)           │
│  - ClaudeAgent service                  │
│  - ZStream[AgentMessage] streaming      │
│  - Sealed trait message ADT             │
│  - Type-safe config builders            │
├─────────────────────────────────────────┤
│     ScalablyTyped Raw Facades           │
│  - js.Promise, js.UndefOr, native types │
├─────────────────────────────────────────┤
│   @anthropic-ai/claude-agent-sdk        │
└─────────────────────────────────────────┘
```

## Project Structure

```
scalagent/
├── build.mill                    # Mill build configuration
├── package.json                  # NPM dependencies
├── src/
│   └── com/tjclp/scalagent/
│       ├── messages/             # Message ADT
│       ├── config/               # Configuration types
│       ├── macros/               # Compile-time schema derivation
│       ├── streaming/            # AsyncGenerator → ZStream
│       ├── tools/                # Tool DSL skeleton
│       └── ClaudeAgent.scala     # Main ZIO service
└── examples/
    └── SimpleQuery.scala         # Example applications
```

## License

MIT
