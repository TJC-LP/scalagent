# Scalagent

A type-safe Scala.js wrapper for `@anthropic-ai/claude-agent-sdk`, with idiomatic ZIO, forward-compatible message modeling, and policy-driven collection for long-running agent workloads.

## Status

- SDK baseline: `@anthropic-ai/claude-agent-sdk` `0.2.90`
- Current repo build version: `0.5.0`
- Scala version: `3.8.3`
- Preferred JS runtime: `bun`
- Runtime requirement: `ANTHROPIC_API_KEY`

For a compatibility inventory against the installed SDK baseline, see `docs/COMPATIBILITY.md`.

## Features

- `ZStream`-based query and session APIs
- Forward-compatible message ADT with `UnknownEnvelope` fallbacks
- Deterministic query/session cleanup with idempotent `close()` behavior
- Policy-driven collection via `CollectionPolicy` and `QueryCollector`
- Semantic `ask()` helpers that prefer final result payloads over transcript concatenation
- Main-thread skill preloading via `AgentOptions.withSkills(...)`
- Structured outputs with compile-time schema derivation
- Tool DSL, hooks, MCP server config, and multi-turn sessions

## Installation

Use the current published release when consuming from Maven Central. For local development against this repo, the build version is `0.5.0`.

### Mill

```scala
def ivyDeps = Seq(
  mvn"com.tjclp::scalagent::0.5.0"
)
```

### SBT

```scala
libraryDependencies += "com.tjclp" %%% "scalagent" % "0.5.0"
```

### Maven

```xml
<dependency>
  <groupId>com.tjclp</groupId>
  <artifactId>scalagent_sjs1_3</artifactId>
  <version>0.5.0</version>
</dependency>
```

## Requirements

- Mill
- Bun or Node.js 18+
- Scala `3.7.4`
- `ANTHROPIC_API_KEY`

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
import zio.*

object StreamingApp extends ZIOAppDefault:
  val run =
    Claude.query("Count from 1 to 5, one number per line")
      .textOnly
      .foreach(text => Console.print(text).orDie)
```

### Multi-Turn Conversation

```scala
import com.tjclp.scalagent.*
import zio.*

object ConversationApp extends ZIOAppDefault:
  val run =
    for
      session <- ClaudeSession.create(AgentOptions.default.withModel(Model.Sonnet4_6))
      _       <- session.send("Remember the number 42").runDrain
      answer  <- session.ask("What number did I ask you to remember?")
      _       <- Console.printLine(s"Claude remembered: $answer")
    yield ()
```

### Structured Output

```scala
import com.tjclp.scalagent.*
import zio.*
import zio.json.*

case class Analysis(summary: String, score: Int, suggestions: List[String]) derives JsonDecoder
given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]

object AnalysisApp extends ZIOAppDefault:
  val run =
    val options = AgentOptions.default
      .withModel(Model.Sonnet4_6)
      .withStructuredOutput[Analysis]

    for
      result   <- Claude.queryComplete("Analyze this code...", options)
      analysis <- ZIO.fromEither(result.outcome.parseAs[Analysis])
      _        <- Console.printLine(s"Analysis: $analysis")
    yield ()
```

## Collection Policies

`queryComplete()` and session collection are policy-driven so callers can control transcript retention explicitly.

```scala
import com.tjclp.scalagent.*
import zio.*

val options = AgentOptions.default.withPromptSuggestions

val effect =
  for
    result <- Claude.queryComplete(
      prompt = "Summarize the repository status",
      options = options,
      collectionPolicy = CollectionPolicy.BoundedRecent(
        limit = 12,
        includeStreamingDeltas = false,
        stopAtResult = true
      )
    )
    answer <- result.semanticTextOrFail
  yield answer
```

Available policies include:

- `CollectionPolicy.Full`
- `CollectionPolicy.NoStreamingDeltas`
- `CollectionPolicy.UntilResult`
- `CollectionPolicy.ResultOnly`
- `CollectionPolicy.SummaryOnly`
- `CollectionPolicy.Disabled`
- `CollectionPolicy.BoundedRecent(...)`

## Skill Preloading

Top-level preloaded skills are exposed directly on `AgentOptions`.

```scala
val options = AgentOptions.default
  .withSkills("slides", "spreadsheets")
  .withSettingSources(SettingSource.User, SettingSource.Project)
```

Compatibility behavior:

- preferred path: augment or synthesize a main-thread agent using SDK-native `agent` / `agents` / `AgentDefinition.skills`
- fallback path: resolve `SKILL.md` files from configured setting sources and inject them into the effective system prompt
- existing runtime skill loading via `withSkillsEnabled` remains available

## Message Compatibility

`scalagent` preserves known SDK message variants and degrades gracefully when the SDK evolves.

Important properties:

- unknown top-level messages become `AgentMessage.Unknown`
- unknown system events become `SystemEvent.Unknown`
- unknown content blocks become `ContentBlock.Unknown`
- unknown deltas become `StreamDelta.Unknown`
- unknown variants preserve raw JSON and envelope metadata in `UnknownEnvelope`
- image content blocks are parsed into `ContentBlock.Image`
- unrecoverable malformed payloads become `AgentError.MessageParseError`

## Query and Session Lifecycle

`QueryStream` and `ClaudeSession` are hardened for long-running use:

- stream termination runs cleanup automatically
- early consumer termination triggers generator cleanup
- `interrupt()` attempts interruption and then cleanup
- `close()` is idempotent
- cleanup failures are recorded and surfaced as warnings without masking the primary outcome

## Configuration

Use `AgentOptions` to configure queries:

```scala
val options = AgentOptions.default
  .withModel(Model.Sonnet4_6)
  .withMaxTurns(10)
  .withMaxBudgetUsd(0.50)
  .withPermissionMode(PermissionMode.AcceptEdits)
  .withMcpServer("myserver", McpServerConfig.stdio("node", "server.js"))
```

Common builder methods include:

- `withModel(...)`
- `withSystemPrompt(...)`
- `withMaxTurns(...)`
- `withMaxBudgetUsd(...)`
- `withPermissionMode(...)`
- `withAllowedTools(...)`
- `withMcpServer(...)`
- `withStructuredOutput[T]`
- `withMainAgent(...)`
- `withSkills(...)`
- `withSkillsEnabled`
- `withFileCheckpointing`
- `withFallbackModel(...)`

## Advanced Usage

### Raw Query Access

```scala
for
  queryStream <- ClaudeAgent.queryRaw("Complex task...")
  fiber       <- queryStream.messages.foreach(handleMessage).fork
  _           <- ZIO.sleep(30.seconds)
  _           <- queryStream.interrupt
  _           <- fiber.join
yield ()
```

### QueryStream Control Methods

| Method | Description |
|--------|-------------|
| `interrupt` | Interrupt the running query |
| `close()` | Abort the query and terminate resources |
| `setPermissionMode(...)` | Change permission handling |
| `setModel(...)` | Change the active model |
| `supportedModels` | Inspect SDK-supported models |
| `mcpServerStatus` | Inspect MCP connection state |
| `rewindFiles(...)` | Rewind tracked files |
| `setMcpServers(...)` | Reconfigure MCP servers dynamically |

### Macro Tool Definitions

```scala
import com.tjclp.scalagent.*

object MyTools:
  @Tool("get_weather", "Get the current weather for a location")
  def getWeather(
      @Param("City or location name") location: String,
      @Param("Temperature unit") unit: Option[String] = None
  ): String =
    s"Weather in $location: 22°${unit.getOrElse("C")}"
```

## Building and Testing

```bash
bun install
./mill agent.compile
./mill agent.test
```

## Running Examples

```bash
./mill examples.run dsl-basic        # DSL one-shot + streaming + eval
./mill examples.run dsl-builder      # Builder + typed capabilities + JSONL logging
./mill examples.run dsl-delegation   # Parent/child with Peano depth enforcement
./mill examples.run dsl-codex        # Same DSL, OpenAI Codex provider
./mill examples.run dsl-cross        # Claude ↔ Codex cross-provider chain
./mill examples.run simple           # Simple Claude.ask() / query() / conversation()
./mill examples.run macro            # Macro-defined custom tools
./mill examples.run -- --help        # List all 19 available examples
```

## Project Structure

```text
scalagent/
├── build.mill
├── docs/dsl/                    # DSL design docs (ROADMAP, MAPPING, EXAMPLES, etc.)
├── examples/                    # 19 runnable examples (Dsl*, Claude, A2A, tools)
├── src/com/tjclp/scalagent/
│   ├── core/                    # Provider-independent DSL (Agent, AgentRun, Capability, etc.)
│   ├── interop/claude/          # Claude interpreter
│   ├── interop/codex/           # Codex interpreter
│   ├── interop/a2a/             # A2A interpreter
│   ├── interop/mcp/             # MCP tool loader
│   ├── codex/                   # Codex SDK facades + Scala types
│   ├── experimental/            # Capture checking + zio-blocks/scope
│   ├── config/                  # AgentOptions, Model, StructuredOutput
│   ├── messages/                # AgentMessage ADT
│   ├── streaming/               # AsyncIterator → ZStream bridge
│   ├── tools/                   # ToolDef, ToolName, ToolResult
│   └── ClaudeAgent.scala        # Claude SDK facade
└── test/src/com/tjclp/scalagent/ # 43 test suites
```

## License

MIT
