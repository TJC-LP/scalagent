# Scalagent

A type-safe Scala.js SDK for the [`@anthropic-ai/claude-agent-sdk`](https://www.npmjs.com/package/@anthropic-ai/claude-agent-sdk), providing idiomatic ZIO-based access to Claude's agentic capabilities.

## Features

- **ZIO + ZStream** integration for purely functional streaming
- **Type-safe message ADT** mirroring the SDK's discriminated unions
- **Fluent configuration builders** with Scala-native API
- **AsyncGenerator → ZStream bridge** for consuming streaming responses
- **Tool definition DSL** (skeleton for custom tools)

## Requirements

- Mill build tool
- Bun runtime (or Node.js 18+)
- Scala 3.3.x
- `ANTHROPIC_API_KEY` environment variable

## Quick Start

```scala
import com.tjclp.scalagent._
import com.tjclp.scalagent.config._
import com.tjclp.scalagent.messages._
import zio._

object MyApp extends ZIOAppDefault:
  val run =
    ClaudeAgent.query("What is the capital of France?")
      .tap {
        case AgentMessage.Assistant(msg, _, _, _, _) =>
          Console.printLine(s"Claude: ${extractText(msg)}")
        case AgentMessage.Result(ResultOutcome.Success(_, _, _, result, cost, _, _, _, _), _, _) =>
          Console.printLine(s"Done! Cost: $$${cost}")
        case _ => ZIO.unit
      }
      .runDrain
      .provide(ClaudeAgent.live)
```

## Building

```bash
# Install dependencies with Bun
bun install

# Compile the project
mill agent.compile

# Run the example (compiles and runs with Bun)
bun run run

# Or manually:
mill examples.fastLinkJS
bun run out/examples/fastLinkJS.dest/main.js
```

## Configuration

Use `AgentOptions` to configure queries:

```scala
val options = AgentOptions.default
  .withModel("claude-sonnet-4-20250514")
  .withMaxTurns(10)
  .withMaxBudgetUsd(0.50)
  .withPermissionMode(PermissionMode.AcceptEdits)
  .withMcpServer("myserver", McpServerConfig.stdio("node", "server.js"))
```

### Available Options

| Option | Description |
|--------|-------------|
| `withModel(m)` | Set the model to use |
| `withMaxTurns(n)` | Limit number of conversation turns |
| `withMaxBudgetUsd(b)` | Set maximum cost budget |
| `withPermissionMode(pm)` | Control permission handling |
| `withMcpServer(name, config)` | Add an MCP server |
| `withBypassPermissions` | Bypass all permission checks (dangerous!) |
| `withIncludePartialMessages` | Include streaming partial messages |

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

### Custom Tool Definitions (Skeleton)

```scala
import com.tjclp.scalagent.tools._

case class WeatherInput(location: String, unit: String)

val weatherTool = ToolBuilder[WeatherInput]("get_weather")
  .description("Get current weather for a location")
  .schema(JsonSchema.obj(
    "location" -> JsonSchema.string,
    "unit" -> JsonSchema.enum("celsius", "fahrenheit")
  ).required("location"))
  .handler { input =>
    fetchWeather(input.location, input.unit).map(ToolResult.Success(_))
  }
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
│       ├── streaming/            # AsyncGenerator → ZStream
│       ├── tools/                # Tool DSL skeleton
│       └── ClaudeAgent.scala     # Main ZIO service
└── examples/
    └── SimpleQuery.scala         # Example applications
```

## License

MIT
