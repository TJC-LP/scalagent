# Scalagent

Type-safe agent execution for mission-critical environments.
Scala 3 + ZIO on the battle-tested TypeScript agent ecosystem.

> SDK baseline `@anthropic-ai/claude-agent-sdk@^0.3.152` + `@openai/codex-sdk@^0.134.0` | Scalagent `0.9.0-SNAPSHOT` | Scala `3.8.3` | Bun or Node.js 18+

```scala
import com.tjclp.scalagent.*

// Capabilities are visible in the type AND enforced at runtime
val agent = ClaudeInterpreter.builder(claudeAgent)
  .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
  .withBudget
  .build
// Type: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & HasBudget]

val policy = ExecutionPolicy(
  budget       = Budget.usd(1.00),
  maxTurns     = Some(3),
  stopStrategy = StopStrategy.FirstResponse
)

ZIO.scoped {
  agent.run("analyst", "Summarize the risk report.", policy).result
}
```

Wrong tool? Type error. Budget exceeded? Runtime enforcement. Resource leak? Scope-bounded by ZIO.

## Why Scalagent

**Typed safety on the TS ecosystem.** Scala.js compiles to JavaScript. Your agents run on `@anthropic-ai/claude-agent-sdk`, the same battle-tested TypeScript library used in production. Scalagent adds type-level capability tracking, execution policies, and observable event streams on top — without replacing the runtime you already trust.

**Explicit effects.** Tools, delegation, memory access, filesystem access, and human escalation are named capabilities with observable traces. Side effects don't hide in implicit configuration. When an agent calls a tool or spawns a child, the type signature and event stream both say so.

**Provider-independent.** The same `Agent[P, I, O]` trait runs on Claude, Codex, or A2A remote agents. Switch interpreters, keep your pipeline. Evaluation, tracing, and utility scoring work identically across providers.

**Mission-critical.** Built for defense, critical infrastructure, and regulated environments where "what did the agent do and why?" must have a typed, auditable answer. See `docs/VISION.md` for the full positioning.

## Architecture

```
┌─────────────────────────────────────────┐
│  Your Code (Scala 3 / ZIO)             │
│  Agent, ExecutionPolicy, TypedAgent     │
├─────────────────────────────────────────┤
│  Scalagent Core (provider-independent)  │
│  AgentRun, AgentEvent, Capability       │
├─────────────────────────────────────────┤
│  Interpreters (Scala.js → JavaScript)   │
│  ClaudeInterpreter · CodexInterpreter   │
│  A2AInterpreter · McpToolLoader         │
├─────────────────────────────────────────┤
│  @anthropic-ai/claude-agent-sdk (TS)    │
│  Battle-tested production runtime       │
├─────────────────────────────────────────┤
│  Bun / Node.js                          │
└─────────────────────────────────────────┘
```

Scala.js compiles your code to JavaScript. At runtime it calls the official TypeScript SDK directly — no FFI overhead, no serialization boundary. You get Scala's type system and ZIO's effect model with the TS ecosystem's production maturity.

## Provider Independence

The same function works with any provider:

```scala
def execute(agent: Agent[Any, String, String], input: String, policy: ExecutionPolicy) =
  ZIO.scoped {
    val run = agent.run("user", input, policy)
    for
      events <- run.events.runCollect.map(_.toList)
      output <- run.result
    yield (events, output)
  }

val claude = ClaudeInterpreter.string(claudeAgent)
val codex  = CodexInterpreter.string(codexClient)

execute(claude, "What is 7 * 8?", policy)
execute(codex,  "What is 7 * 8?", policy)
```

Both are `Agent[Any, String, String]`. `AgentEvent`, `TraceSummary`, and `Evaluation` are provider-agnostic. Replace the interpreter, keep everything else.

## Compile-Time Safety

### Capability Types

The builder accumulates phantom intersection types. Each `.with*` call narrows what the agent can do — visible in the type signature, enforced at runtime by the interpreter.

```scala
val agent = ClaudeInterpreter.builder(claudeAgent)
  .withTools(ToolSurface(weatherTool))       // & CanUseTools[CustomTools]
  .withSpawnDepth[Depth2]                    // & CanSpawn[Depth2]
  .withBudget                                // & HasBudget
  .build
```

Attempting to delegate from an agent without `CanSpawn` is a compile error, not a runtime surprise.

### Delegation Depth

Peano-encoded depth types prevent unbounded agent nesting at compile time:

```scala
val parent = ClaudeInterpreter.builder(claudeAgent)
  .withSpawnDepth[Depth2]
  .withBudget
  .build

val child = ClaudeInterpreter.builder(claudeAgent)
  .withSpawnDepth[Depth1]
  .build

// Compile-time proof: DepthLTE[Depth1, Depth1] ✓
// Try Depth2 under Depth2? Type error.
parent.delegateTyped(child, "supervisor", prompt, policy,
  DelegationPolicy(budgetFraction = 0.3, maxChildTurns = Some(5)))
```

The runtime also asserts `child.maxRuntimeDepth < parent.maxRuntimeDepth` as defense-in-depth.

### Classified Review

Type-level visibility controls prevent information leakage across clearance boundaries:

```scala
val report: Classified[FieldReport, Secret] = classify(fieldReport)

// Only reviewers with sufficient clearance can see classified output
val reviewer: Reviewer[String, Classified[FieldReport, Secret]] = ...

// Requires CanSee[ReviewerLevel, Secret] evidence at compile time
AgenticReview.enrichClassified(permit, evaluation, reviewer)
```

### Structured Output

Define a case class. Derive a schema. The agent's output type becomes your type — no string parsing, no runtime casting:

```scala
case class RiskAssessment(
  severity: String,
  score: Double,
  findings: List[String],
  recommendation: String
) derives JsonDecoder

given StructuredOutput[RiskAssessment] = StructuredOutput.derive[RiskAssessment]

// Output type is RiskAssessment, not String
val agent = ClaudeInterpreter.typedBuilder[RiskAssessment](claudeAgent)
  .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
  .withBudget
  .build
// TypedAgent[Any, String, RiskAssessment, CanUseTools[ReadOnlyTools] & HasBudget]

ZIO.scoped {
  val assessment: RiskAssessment = agent
    .run("analyst", "Assess risk for Project Alpha.", policy)
    .result
  // assessment.score, assessment.findings — fully typed
}
```

The `StructuredOutput.derive` macro generates a JSON schema from the case class and wires it into the provider's native structured output mode. The `OutputCodec` type class handles dispatch: `String` output is passthrough, structured types use the schema to constrain the provider and parse the response.

### Directory Scoping

Restrict where an agent's file tools can operate:

```scala
val agent = ClaudeInterpreter.builder(claudeAgent)
  .withWorkingDirectory("/data/reports")
  .withAdditionalDirectory("/data/shared")
  .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
  .withBudget
  .build
// TypedAgent[..., CanUseTools[ReadOnlyTools] & HasBudget & HasDirectoryScope]
```

`HasDirectoryScope` in the type signature proves the agent was directory-scoped at build time. At runtime, the interpreter wires the paths into the provider's native directory restrictions (`AgentOptions.cwd` for Claude, `CodexThreadOptions.workingDirectory` for Codex).

## Installation

### Mill

```scala
ivy"com.tjclp::scalagent::0.9.0-SNAPSHOT"
```

### SBT

```scala
libraryDependencies += "com.tjclp" %%% "scalagent" % "0.9.0-SNAPSHOT"
```

### Maven

```xml
<dependency>
  <groupId>com.tjclp</groupId>
  <artifactId>scalagent_sjs1_3</artifactId>
  <version>0.9.0-SNAPSHOT</version>
</dependency>
```

### Requirements

- Scala 3.8.3+ with Scala.js
- Bun (preferred) or Node.js 18+
- `bun install` to fetch the TypeScript SDK and ZIO dependencies

### 0.9.0 Upgrade Notes

- The Claude SDK baseline is now 0.3.x. `AgentOptions.default` serializes `settingSources: []` to preserve Scalagent's default settings isolation; pass user/project sources explicitly when you want filesystem settings.
- `AgentMessage.Assistant` and `AgentMessage.User` carry new SDK metadata. Downstream positional pattern matches must be updated, or use accessor-based matching.
- `ToolSurface.allBuiltins` now uses the SDK Task tools (`TaskCreate`, `TaskUpdate`, `TaskGet`, `TaskList`) and no longer includes deprecated `TodoWrite`.

## Quick Start

### One-Shot Query

```scala
import com.tjclp.scalagent.*

val agent = ClaudeInterpreter.string(claudeAgent)

val answer = ZIO.scoped {
  agent.run("user", "What is the capital of France?", ExecutionPolicy.unbounded).result
}
```

### Streaming with Evaluation

```scala
val policy = ExecutionPolicy(budget = Budget.usd(0.50), maxTurns = Some(3))
val utility = Utility.reliability[String, String]

ZIO.scoped {
  val run = agent.run("analyst", "Analyze the quarterly report.", policy)
  for
    events <- run.events.runCollect.map(_.toList)
    output <- run.result
    trace   = TraceSummary.fromEvents(events)
    eval    = Evaluation.fromTrace("analyst", output, trace, utility)
    _      <- ZIO.succeed(println(s"Score: ${eval.score}, Turns: ${trace.numTurns}, Cost: $$${trace.costUsd}"))
  yield output
}
```

Events stream in real time. The trace captures timing, cost, tool calls, and completion status. Evaluation scores the output against your utility function.

## Examples

```bash
./mill examples.run dsl-basic        # One-shot + streaming + eval
./mill examples.run dsl-builder      # Builder + capability types + JSONL logging
./mill examples.run dsl-delegation   # Peano-bounded parent/child delegation
./mill examples.run dsl-review       # Explainable scoring + semantic review
./mill examples.run dsl-structured   # Typed structured output (RiskAssessment)
./mill examples.run dsl-cells        # Zero-trust clandestine cell simulation
./mill examples.run dsl-codex        # Codex interpreter
./mill examples.run dsl-cross        # Claude <> Codex cross-provider chain
./mill examples.run capture          # Capture-checked sandbox capabilities
./mill examples.run simple           # Simple Claude.ask() one-shot
./mill examples.run macro            # Macro-defined custom tools
./mill examples.run -- --help        # List all available examples
```

## Low-Level SDK Access

For direct SDK control, Scalagent provides full access to the underlying Claude Agent SDK:

```scala
// One-shot
val answer = Claude.ask("What is 2 + 2?")

// Multi-turn session
val session = ClaudeSession.open()
session.send("Remember: my name is Alice.")
session.send("What is my name?") // "Alice"
session.close()
```

The low-level API supports all `AgentOptions` configuration (model selection, permission mode, tool definitions, system prompts, structured output) and all collection policies. See `docs/COMPATIBILITY.md` for the full SDK surface coverage.

## A2A Execution

Scalagent's default A2A surface is native A2A v1. `A2AServer` serves the
well-known agent card, JSON-RPC at `/`, and REST `application/a2a+json` routes
such as `POST /message:send`, `POST /message:stream`, `GET /tasks`, and the
task push notification config endpoints.

The v1 agent card advertises `JSONRPC` first and `HTTP+JSON` second, both with
`protocolVersion = "1.0"`. `A2AClient.discover` fetches the well-known card and
selects a JSON-RPC endpoint.

`SendMessage` defaults to `returnImmediately = false`, so it waits until the
task reaches a terminal or interrupted state. Use `client.submit(...)` or set
`MessageSendConfiguration(returnImmediately = true)` to get the initial
`working` task immediately, then continue with `getTask`, `listTasks`,
`cancelTask`, or `resubscribe`.

Streaming uses `SendStreamingMessage` or `POST /message:stream` and yields
`A2AResponse.StreamResponse` events: task snapshots, status updates, artifact
updates, and messages. Live progress includes text status plus structured
tool-use and tool-result data.

Push notifications are supported when
`AgentCapabilities(pushNotifications = true)` is set. Supply inline config at
`MessageSendConfiguration.taskPushNotificationConfig`, or manage configs with
`createTaskPushNotificationConfig`, `getTaskPushNotificationConfig`,
`listTaskPushNotificationConfigs`, and `deleteTaskPushNotificationConfig`.
Callbacks POST a `StreamResponse` body as `application/a2a+json`. Callback URLs
use `PushNotificationUrlPolicy.externalOnly` by default, rejecting localhost and
private network targets; use `PushNotificationUrlPolicy.allowAll` only behind a
trusted boundary or in local tests.

`A2AServer.Config` also exposes `eventReplayLimit` and `maxRequestBodyBytes`.
`/extendedAgentCard` and any public A2A deployment should sit behind your
authenticating edge. Durable `A2ATaskStore` implementations are responsible for
escaping or validating task/config IDs before using them in external storage
keys, paths, or queries.

Legacy A2A 0.3 compatibility remains available explicitly as `A2AClientV03`,
`A2AServerV03`, and `A2AServerAppV03`; `com.tjclp.scalagent.*` defaults to v1.

## Project Structure

```text
scalagent/
├── build.mill
├── docs/VISION.md                   # Strategic direction
├── docs/dsl/                        # DSL design docs (foundations, roadmap, examples)
├── examples/                        # Runnable examples (DSL + SDK)
├── src/com/tjclp/scalagent/
│   ├── core/                        # Provider-independent kernel
│   ├── interop/claude/              # Claude interpreter
│   ├── interop/codex/               # Codex interpreter
│   ├── interop/a2a/                 # A2A interpreter + server adapter
│   ├── interop/mcp/                 # MCP tool loader
│   ├── experimental/                # Capture checking + scoped capabilities
│   ├── codex/                       # Codex client facades
│   ├── config/                      # AgentOptions, Model, StructuredOutput
│   ├── messages/                    # AgentMessage ADT
│   ├── streaming/                   # AsyncIterator → ZStream bridge
│   ├── tools/                       # ToolDef, ToolName, ToolResult
│   └── ClaudeAgent.scala            # Claude SDK facade
└── test/src/                        # MUnit test suites
```

## Building and Testing

```bash
bun install                          # Fetch TS SDK + ZIO dependencies
./mill agent.compile                 # Compile library
./mill agent.test                    # Run test suite
./mill examples.compile              # Compile all examples
./mill examples.run dsl-basic        # Run a specific example
```

## Documentation

- [`docs/VISION.md`](docs/VISION.md) — strategic direction and design principles
- [`docs/dsl/FOUNDATIONS.md`](docs/dsl/FOUNDATIONS.md) — formal model (`Agent = I → D(Eff[O])`)
- [`docs/dsl/EXAMPLES.md`](docs/dsl/EXAMPLES.md) — 13 detailed usage patterns with before/after
- [`docs/dsl/ROADMAP.md`](docs/dsl/ROADMAP.md) — implementation phases and remaining work
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — SDK surface coverage matrix

## License

MIT
