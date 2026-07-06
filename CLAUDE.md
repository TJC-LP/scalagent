---
description: Scalagent — type-safe Scala DSL for AI agent orchestration
alwaysApply: true
---

# Scalagent

Type-safe Scala.js facades over the Claude Agent SDK, with a provider-independent
DSL for building observable, capability-bounded AI agents.

## The Zen of Scalagent

Everything flows through `Agent[-P, -I, +O]`.
No tools by default — declare what you need, nothing more.
Types encode capabilities — if it compiles, it's safe.
Events are the source of truth — trace, score, review.
Provider independence — write once, run on Claude, Codex, or A2A.
Budget is a type, not a number.
Delegation has depth — Peano types prevent infinite recursion at compile time.
Start simple with `Claude.ask`. Graduate to the DSL when you need guarantees.
One import gets you everything: `import com.tjclp.scalagent.*`

## Architecture

### The Type Algebra

```
Agent[-P, -I, +O]         The universal agent contract
  P = Principal            WHO runs the agent (contravariant)
  I = Input                WHAT it receives (contravariant)
  O = Output               WHAT it produces (covariant — String or structured)

AgentRun[R, O]             A running agent: events + result sharing one execution
  .events: ZStream[AgentEvent]    Normalized event stream
  .result: ZIO[O]                 Typed output (scope-bounded)

ExecutionPolicy            Semantic constraints on a run
  .budget: Budget          Usd(amount) | Unlimited
  .maxTurns: Option[Int]   Turn limit
  .deadline: Option[Duration]
  .stopStrategy: StopStrategy   Natural | FirstResponse

TypedAgent[P, I, O, C]    Agent + phantom capability evidence C
  C accumulates: CanUseTools[T] & CanSpawn[D] & HasBudget & HasDirectoryScope
```

### The Flow

```
                    ┌─────────────────────────────────────┐
 Convenience API    │  Claude.ask / query / conversation   │
                    └──────────────┬──────────────────────┘
                                   │ graduates to
                    ┌──────────────▼──────────────────────┐
 DSL Core           │  Agent  →  AgentRun  →  AgentEvent  │
                    │    ↑                        │        │
                    │  ClaudeInterpreter          ▼        │
                    │  CodexInterpreter      TraceSummary   │
                    │  A2AInterpreter            │        │
                    │                        Evaluation    │
                    └─────────────────────────────────────┘
```

### Interpreters Bridge Core to Runtime

`ClaudeInterpreter` translates the DSL into `AgentOptions` + SDK calls:
- `ExecutionPolicy.budget` → `AgentOptions.maxBudgetUsd`
- `ExecutionPolicy.maxTurns` → `AgentOptions.maxTurns`
- `StopStrategy.FirstResponse` → forces `maxTurns = 1`
- `ToolSurface` → `AgentOptions.allowedTools` + implicit MCP server
- `OutputCodec[O].structuredOutputFormat` → `AgentOptions.outputFormat`

Same contract for `CodexInterpreter` (Codex sandbox) and `A2AInterpreter` (agent-to-agent).

## The DSL in Practice

### Convenience API — Start Here

```scala
import com.tjclp.scalagent.*

// One-shot question
val answer <- Claude.ask("What is 2 + 2?", options)

// Streaming
val chunks <- Claude.query("Count to 5.", options).textOnly.runCollect

// Multi-turn conversation
val result <- Claude.conversation(options) { session =>
  for
    first  <- session.ask("What is 10 + 5?")
    second <- session.ask(s"Double $first.")
  yield second
}
```

### DSL Basic — Agent + Policy + Events

```scala
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

val options = AgentOptions.default
  .withModel(Model.haiku)
  .withPermissionMode(PermissionMode.DontAsk)

val policy = ExecutionPolicy(
  budget = Budget.usd(1.00),
  maxTurns = Some(3),
  stopStrategy = StopStrategy.FirstResponse
)

val program = for
  claudeAgent <- ZIO.service[ClaudeAgent]
  agent = ClaudeInterpreter.string(claudeAgent, options)
  answer <- ZIO.scoped {
    agent.run("user", "What is the capital of France?", policy).result
  }
yield answer

program.provide(ClaudeAgent.live)
```

### Builder Pattern — Phantom Capability Accumulation

```scala
val agent = ClaudeInterpreter.builder(claudeAgent, options)
  .withReadOnlyTools(ToolSurface.readOnlyBuiltins)   // + CanUseTools[ReadOnlyTools]
  .withBudget                                         // + HasBudget
  .withSpawnDepth[Depth2]                             // + CanSpawn[S[S[Z]]]
  .withWorkingDirectory("/safe/path")                 // + HasDirectoryScope
  .build
// Type: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & HasBudget & CanSpawn[Depth2] & HasDirectoryScope]
```

Capabilities are checked at compile time. An agent without `CanSpawn` cannot call
`.delegateTyped()`. An agent without `HasBudget` won't satisfy policy constraints.

**Tool surface presets:**
- `ToolSurface.none` — no tools (the default)
- `ToolSurface.readOnlyBuiltins` — Read, Glob, Grep
- `ToolSurface.readOnlyAll` — above + WebFetch, WebSearch, LSP
- `ToolSurface.standard` — readOnlyBuiltins + Write, Edit, NotebookEdit, Task* (no Bash)
- `ToolSurface.allBuiltins` — everything including Bash and Task* (explicit opt-in)

### Event Streaming → Trace → Evaluation

```scala
val (events, output) <- ZIO.scoped {
  val agentRun = agent.run("user", "Analyze this code.", policy)
  for
    evts <- agentRun.tapEvents(TraceLogger.console.logEvent).events.runCollect.map(_.toList)
    out  <- agentRun.result
  yield (evts, out)
}

// Pure operational scoring
val trace = TraceSummary.fromEvents(events)
val utility = Utility.weighted[String, String](
  Utility.reliability      -> 0.5,
  Utility.costMinimizing   -> 0.3,
  Utility.simplicityBiased -> 0.2
)
val eval = Evaluation.fromTrace("user", output, trace, utility)
// eval.score, eval.breakdown.components, eval.complexity.totalNodes
```

`AgentEvent` is a normalized ADT: `TextDelta`, `ToolCall`, `ToolResult`,
`DelegationStarted`, `DelegationFinished`, `Status`, `Completed`, `Native`.
Provider-specific events pass through via `Native(tag, payload)`.

### Structured Output — Type-Safe End-to-End

```scala
case class RiskAssessment(
  @description("Risk severity") severity: String,
  @description("Score from 0.0 to 1.0") score: Double,
  findings: List[String],
  recommendation: String
) derives JsonDecoder

given StructuredOutput[RiskAssessment] = StructuredOutput.derive[RiskAssessment]

val agent = ClaudeInterpreter.typed[RiskAssessment](claudeAgent, options)
val assessment: RiskAssessment <- ZIO.scoped { agent.run("user", prompt, policy).result }
```

Macro generates JSON Schema from the case class at compile time.
The agent returns typed output, not a string to parse.

### Macro-Derived Tools — @Tool / @Param

```scala
@Tool("get_weather", "Get current weather for a location")
def getWeather(
  @Param("City name") location: String,
  @Param("Temperature unit") unit: Option[TempUnit] = None
): Task[ToolResult] =
  ZIO.succeed(ToolResult.json(WeatherData(location, 22, "Celsius", "Sunny")))

val server = ToolMacros.createServer[MyTools.type]("my-tools", runtime)
```

Annotations drive schema generation. Scala 3 enums become JSON Schema enums.
`ToolResult.json(...)` returns structured data; `.text(...)` for plain text;
`.multi.text(...).image(...).build` for rich content.

### Delegation — Depth Types + Budget Slicing

```scala
val parent = ClaudeInterpreter.builder(claudeAgent, options)
  .withSpawnDepth[Depth2]
  .withBudget
  .build

val delegation = DelegationPolicy(budgetFraction = 0.3, maxChildTurns = Some(5))

val childResult <- ZIO.scoped {
  parent.delegateTyped(child, "supervisor", "Summarize CLAUDE.md", policy, delegation)
    .result
}
```

Depth is Peano-encoded: `Depth0 = Z`, `Depth1 = S[Z]`, `Depth2 = S[S[Z]]`.
`DepthLTE[ChildDepth, ParentDepth-1]` is checked at compile time.
Budget is sliced: child gets 30%, parent retains 70%.

### Directory Scoping

```scala
val agent = ClaudeInterpreter.builder(claudeAgent, options)
  .withWorkingDirectory("/project/sandbox")
  .withAdditionalDirectory("/project/shared")
  .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
  .build
```

A `PreToolUse` hook intercepts all file-operation tools (Read, Write, Edit, Glob, Grep)
and validates paths via lexical check + symlink resolution. Bash is blocked entirely
(commands can't be reliably path-checked). Files outside scope are denied.

## Build & Test

```bash
./mill agent.test          # Scala/MUnit test suite (runs via Bun/ScalaJS)
./mill agent.compile       # Compile main library
./mill examples.compile    # Compile all examples
./mill agent.publishLocal  # Publish JAR to ~/.ivy2/local
```

## Build Dependencies

JS dependencies are declared in `build.mill` using the `bun""` string interpolator from
mill-bun-plugin v0.2.0, which validates package specifiers at compile time:

```scala
import mill.bun.bun

def bunDeps = Task { Seq(
  bun"@anthropic-ai/claude-agent-sdk@^0.3.201",
  bun"@openai/codex-sdk@^0.142.5",
  bun"zod@^4.0.0"
)}
```

The `agent` module mixes in `BunPublishModule`, which embeds
`META-INF/bun/bun-dependencies.json` in the published JAR. Downstream consumers
get all npm deps resolved automatically with no manual `bun install` or `bunDeps`.

## Examples

```bash
./mill examples.run dsl-basic        # One-shot + streaming + eval
./mill examples.run dsl-builder      # Builder pattern + tools
./mill examples.run dsl-delegation   # Hierarchical depth control
./mill examples.run dsl-review       # Pure + effectful evaluation
./mill examples.run dsl-cells        # Zero-trust compartmentalization
./mill examples.run dsl-structured   # Typed structured output
./mill examples.run dsl-dirscope     # Directory scoping
./mill examples.run dsl-codex        # Codex interpreter
./mill examples.run dsl-cross        # Claude + Codex cross-provider
./mill examples.run simple           # Convenience API (ask/query/conversation)
./mill examples.run macro            # @Tool macro-derived tools
./mill examples.run -- --help        # List all available examples
```

## Bun / JS Context

Default to Bun instead of Node.js for any JS/TS work in this repo.

- `bun <file>` not `node <file>`, `bun test` not `jest`, `bun install` not `npm install`
- `Bun.serve()` for HTTP/WS servers, not `express`
- `bun:sqlite` not `better-sqlite3`, `Bun.file` not `node:fs`
- Bun loads `.env` automatically — no dotenv
- HTML imports with `Bun.serve()` for frontend, not vite
- Bun API docs: `node_modules/bun-types/docs/**.md`
