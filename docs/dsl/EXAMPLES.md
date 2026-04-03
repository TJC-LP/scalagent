# DSL Usage Examples

Status: post-implementation. All examples use the actual DSL API on `dsl/core-exploration`. Two providers (Claude + Codex) prove the DSL is provider-independent.

Each example shows a current SDK pattern ("before") and the DSL equivalent ("after") using real implemented types. Every code snippet references types that exist in the codebase.

## Running Live Examples

```bash
./mill examples.run dsl-basic        # One-shot + streaming + TraceSummary + Evaluation
./mill examples.run dsl-builder      # Builder + read-only tools + JSONL logging
./mill examples.run dsl-delegation   # Typed parent/child with Peano depth enforcement
./mill examples.run dsl-codex        # Same DSL, Codex provider (requires codex CLI)
./mill examples.run dsl-cross        # Claude ↔ Codex cross-provider chain
./mill examples.run -- --help        # List all 19 available examples
```

All DSL examples are in `examples/Dsl*.scala`. The `ExampleRunner` dispatcher selects the right example at runtime.

---

## Example 1: One-Shot Query

Current SDK pattern (from `SimpleQuery.scala`):

```scala
val options = AgentOptions.default
  .withModel(Model.haiku)
  .withPermissionMode(PermissionMode.DontAsk)
  .withMaxTurns(PositiveInt.literal(5))

val answer: IO[AgentError, String] =
  Claude.ask("What is 2 + 2? Reply with just the number.", options)
```

DSL equivalent:

```scala
import com.tjclp.scalagent.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

val agent: Agent[Any, String, String] =
  ClaudeInterpreter.string(claudeAgent, baseOptions)

val policy = ExecutionPolicy(
  budget = Budget.usd(0.01),
  maxTurns = Some(5)
)

val answer: IO[AgentError, String] =
  agent.run("user", "What is 2 + 2? Reply with just the number.", policy).result
```

What changed:
- `ExecutionPolicy` replaces scattered config fields — budget, turns, deadline in one semantic object
- Principal is explicit (first arg to `run`)
- Budget is `Budget.Usd(0.01)`, not a raw `Double`
- Model selection stays on `baseOptions` (interpreter concern, not call-site concern)
- Return type is still `IO[AgentError, String]`

What did not change:
- `ClaudeAgent` service is still needed (the interpreter wraps it)
- `AgentOptions` still exists for provider-specific config
- `ExecutionPolicy.simple(0.01, 5)` is a shorthand for the common case

---

## Example 2: Streaming Events

Current SDK pattern:

```scala
Claude.query("Count from 1 to 5.", options)
  .textOnly
  .tap(text => Console.printLine(s">> $text").orDie)
  .runCollect
```

DSL equivalent:

```scala
val run = agent.run("user", "Count from 1 to 5.", policy)

// Built-in helper for text deltas
run.textDeltas
  .tap(text => Console.printLine(s">> $text").orDie)
  .runCollect

// Or pattern match for full control
run.events
  .collect { case AgentEvent.TextDelta(text) => text }
  .tap(text => Console.printLine(s">> $text").orDie)
  .runCollect
```

What changed:
- Events use the normalized `AgentEvent` ADT with 8 typed cases
- `.textDeltas` replaces `.textOnly` — semantically equivalent
- `.toolCalls`, `.nativeEvents`, `.normalizedEvents` are also available

The `Native` escape hatch preserves provider-specific data:

```scala
run.nativeEvents
  .tap(n => Console.printLine(s"provider: ${n.tag}").orDie)
  .runDrain
```

---

## Example 3: Structured Output

Current SDK pattern (from `StructuredOutputExample.scala`):

```scala
case class Analysis(
  sentiment: String,
  confidence: Double,
  keywords: List[String]
)
given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]

val options = AgentOptions.default.withStructuredOutput[Analysis]
val result = Claude.queryComplete("Analyze: 'Great product!'", options)
// result.structuredOutput: Option[Analysis]
```

DSL equivalent:

```scala
case class Analysis(
  @description("positive, negative, or neutral")
  sentiment: String,
  confidence: Double,
  keywords: List[String]
)
given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]

// Output type is in the agent's signature
val typedAgent: Agent[Any, String, Analysis] =
  ClaudeInterpreter.typed[Analysis](claudeAgent, baseOptions)

val analysis: IO[AgentError, Analysis] =
  typedAgent.run("user", "Analyze: 'Great product!'", policy).result
```

What changed:
- Output type `Analysis` is part of the agent's signature: `Agent[Any, String, Analysis]`
- No runtime `withStructuredOutput` option — the type is wired at construction
- `StructuredOutput` derivation is unchanged (already DSL-quality)
- `run.result` returns `IO[AgentError, Analysis]` directly — no `.structuredOutput` unwrapping

---

## Example 4: Builder + Custom Tools

Current SDK pattern (from `CustomToolExample.scala`):

```scala
val weatherTool = ToolDef.fromInput[WeatherInput](
  name = "get_weather",
  description = "Get current weather for a location"
)(input => ZIO.succeed(ToolResult.Success(s"72F in ${input.location}")))

val options = AgentOptions.default.withTools(List(weatherTool))
```

DSL equivalent:

```scala
val weatherTool = ToolDef.fromInput[WeatherInput](
  name = "get_weather",
  description = "Get current weather for a location"
)(input => ZIO.succeed(ToolResult.Success(s"72F in ${input.location}")))

val agent: TypedAgent[Any, String, String, CanUseTools[CustomTools] & HasBudget] =
  ClaudeInterpreter.builder(claudeAgent, baseOptions)
    .withTools(ToolSurface(weatherTool))
    .withBudget
    .build
```

What changed:
- `ToolDef` is identical — it was already the right abstraction
- `ToolSurface(weatherTool)` wraps tools with a provider allowlist
- `AgentBuilder` accumulates phantom types: `CanUseTools[CustomTools] & HasBudget`
- `ClaudeInterpreter.builder()` provides `agentTransform` that wires `ToolSurface.distinctAllowedTools` into `AgentOptions.allowedTools` at build time
- The type signature tells you what this agent can do

---

## Example 5: Read-Only Agent

Current SDK pattern (from `SubagentExample.scala`):

```scala
val researcher = AgentDefinition.readOnly(
  description = "Research assistant for exploring codebases",
  prompt = "You research and summarize code patterns."
)
// Implicitly restricts to Read, Grep, Glob
val options = AgentOptions.default.withAgent("researcher", researcher)
```

DSL equivalent:

```scala
val agent: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools]] =
  ClaudeInterpreter.builder(claudeAgent, baseOptions)
    .withReadOnlyTools(ToolSurface(readToolDefs))
    .build
```

What changed:
- `CanUseTools[ReadOnlyTools]` vs `CanUseTools[CustomTools]` is visible in the type
- `.withReadOnlyTools` validates that all provided tools are read-only compatible (`isReadOnlyCompatible`)
- Automatically adds `ToolSurface.readOnlyBuiltins` (Read, Grep, Glob)
- If you try to add a write tool via `withReadOnlyTools`, it fails at construction time

---

## Example 6: Typed Delegation with Depth Enforcement

Current SDK pattern:

```scala
val codeReviewer = AgentDefinition(
  description = "Expert code reviewer",
  prompt = "You review code for security and quality...",
  tools = Some(List(ToolName.Read, ToolName.Grep, ToolName.Glob)),
  model = Some(AgentModel.Haiku)
)

val options = AgentOptions.default
  .withAgent("code-reviewer", codeReviewer)
```

DSL equivalent:

```scala
// Parent: read-only tools, can spawn up to depth 2
val parent: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & CanSpawn[Depth2] & HasBudget] =
  ClaudeInterpreter.builder(claudeAgent, baseOptions)
    .withReadOnlyTools(readTools)
    .withSpawnDepth[Depth2]
    .withBudget
    .build

// Child: read-only tools, depth 1
val child: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools] & CanSpawn[Depth1]] =
  ClaudeInterpreter.builder(claudeAgent, baseOptions)
    .withReadOnlyTools(readTools)
    .withSpawnDepth[Depth1]
    .build

// Delegation with budget slicing
val delegation = DelegationPolicy(budgetFraction = 0.3, maxChildTurns = Some(10))
val childRun: AgentRun[Any, String] =
  parent.delegateTyped(child, "supervisor", "review this code", parentPolicy, delegation)
// Compiles: child depth (1) <= parent depth - 1 (1) via DepthLTE[S[Z], S[Z]]
```

What the compiler prevents:

```scala
// Won't compile: Depth2 child under Depth2 parent
val tooDeep = ClaudeInterpreter.builder(claudeAgent)
  .withSpawnDepth[Depth2]
  .build

// parent.delegateTyped(tooDeep, ...)
// Error: no given instance of DepthLTE[S[S[Z]], S[Z]]
```

What changed:
- Delegation is a typed relationship, not a string key in a config map
- Peano depth types (`Z`, `S[N]`) enforce delegation hierarchy at compile time
- `DelegationPolicy` controls budget fraction (0.3 = 30% of parent's remaining budget)
- `delegateTyped` requires 3 pieces of evidence: `HasSpawn[C]` for parent, `HasSpawn[CC]` for child, `DepthLTE[CD, PD]` for depth fit
- Runtime defense-in-depth: `require(child.maxRuntimeDepth < parent.maxRuntimeDepth)`

---

## Example 7: Evaluation Pipeline

Current SDK pattern:

```scala
val result = Claude.queryComplete(prompt, options)
result.outcome match
  case success: ResultOutcome.Success =>
    println(s"Turns: ${success.numTurns}, Cost: ${success.totalCostUsd}")
  case error: ResultOutcome.Error =>
    println(s"Error: ${error.errors.mkString(", ")}")
```

DSL equivalent:

```scala
// Define observer-dependent utility scoring
val utility = Utility.weighted[String, String](
  Utility.reliability     -> 0.5,
  Utility.costMinimizing  -> 0.3,
  Utility.latencyMinimizing -> 0.2
)

// Collect events from a run
val run = agent.run("analyst", prompt, policy)
val events: List[AgentEvent] = ... // collected from run.events

// Evaluate the run
val eval: Evaluation[String, String] =
  Evaluation.evaluate("analyst", output, events, utility)

eval.score          // 0.0 - 1.0, weighted composite
eval.trace          // TraceSummary: numTurns, costUsd, toolNames, delegationIds, ...
eval.complexity     // Complexity: totalNodes, toolCallNodes, delegationNodes, graphDensity
```

What changed:
- Scoring is observer-dependent: different principals evaluate the same run differently
- `TraceSummary.fromEvents` folds the event stream into rich metrics (not just turns + cost)
- `Complexity` measures execution graph size (from the formalization: `C(alpha) = E[|G(x)| | x]`)
- Built-in utilities: `costMinimizing`, `reliability`, `latencyMinimizing`, `simplicityBiased`
- `Utility.weighted` composes multiple scoring functions with weights
- `Utility.from { (principal, output, trace) => ... }` for custom scoring logic

---

## Example 8: Composable Trace Logging

Current SDK pattern:

```scala
val options = AgentOptions.default
  .withHook(HookEvent.PreToolUse, myHook)
  .withHook(HookEvent.PostToolUse, myOtherHook)
```

DSL equivalent:

```scala
// Fan-out to multiple sinks
val logger = TraceLogger.all(
  TraceLogger.console,                                 // human-readable to stdout
  TraceLogger.callbackZIO(line => appendToFileZIO(line)) // effectful JSONL sink
)

// Tap the event stream through the logger
val run = agent.run("analyst", prompt, policy)
run.tapEvents(logger.logEvent).events.runDrain

// After completion, log the evaluation
val eval = Evaluation.evaluate("analyst", output, events, utility)
logger.logEvaluation(eval)
```

What changed:
- `TraceLogger` is composable (`.all` fans out to multiple loggers)
- Operates on normalized `AgentEvent`, not provider-specific hook types
- JSONL output via `callback` for observability pipelines (each event is a JSON line)
- `logEvaluation` emits structured scoring data (score, cost, turns, complexity)
- Provider-specific hooks (`HookCallback`) still exist for lifecycle interception that needs provider context

---

## Example 9: A2A Remote Agent as Delegation Target

Current SDK pattern:

```scala
val client = A2AClient.discover("https://remote-agent.example.com")
val tool = A2ATool.discover("https://remote-agent.example.com", "analyst")
val options = AgentOptions.default.withTools(List(tool))
```

DSL equivalent:

```scala
import com.tjclp.scalagent.interop.a2a.A2AInterpreter

for
  // Remote agent implements the same Agent trait as local agents
  remoteAgent <- A2AInterpreter.discover("https://remote-agent.example.com")

  // Use as a delegation target — same as delegating to a local agent
  childRun = parent.delegate(remoteAgent, "supervisor", "analyze this data", policy)
yield childRun
```

What changed:
- A2A agents implement `Agent[Any, String, String]` — same trait as Claude-backed agents
- Delegation is identical for local and remote: `parent.delegate(child, ...)`
- Transport is an interpreter detail, not visible to the caller
- `A2AInterpreter.discover(url)` handles card fetch + client setup + event normalization
- Events stream through the same `AgentEvent` ADT (via `A2AEventMapper`)

---

## Example 10: Full Pipeline

End-to-end: build, run, stream, evaluate, log.

```scala
import com.tjclp.scalagent.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

// 1. Build a capability-typed agent
val agent = ClaudeInterpreter.builder(claudeAgent, baseOptions)
  .withTools(ToolSurface(readTool, grepTool))
  .withSpawnDepth[Depth1]
  .withBudget
  .build
// : TypedAgent[Any, String, String, CanUseTools[CustomTools] & CanSpawn[Depth1] & HasBudget]

// 2. Define semantic policy
val policy = ExecutionPolicy(
  budget = Budget.usd(0.50),
  maxTurns = Some(20),
  deadline = Some(zio.Duration.fromSeconds(120))
)

// 3. Define utility scoring
val utility = Utility.weighted[String, String](
  Utility.reliability     -> 0.5,
  Utility.costMinimizing  -> 0.3,
  Utility.simplicityBiased -> 0.2
)

// 4. Set up logging
val logger = TraceLogger.all(TraceLogger.console, jsonlLogger)

// 5. Run with streaming
val run = agent.run("analyst", "Find all TODO comments in src/", policy)

for
  // Stream events through the logger, collect for evaluation
  collected <- run.tapEvents(logger.logEvent).events.runCollect.map(_.toList)

  // Get the typed result
  output <- run.result

  // Evaluate the run
  eval = Evaluation.evaluate("analyst", output, collected, utility)
  _ <- logger.logEvaluation(eval)

  // Inspect results
  _ <- Console.printLine(s"Score: ${eval.score}").orDie
  _ <- Console.printLine(s"Tools used: ${eval.trace.toolNames}").orDie
  _ <- Console.printLine(s"Graph density: ${eval.complexity.graphDensity}").orDie
yield output
```

---

## Example 11: Capture-Checked Sandbox (Experimental)

```scala
import com.tjclp.scalagent.experimental.*

// Capabilities are scoped — compiler prevents them from escaping
SandboxedRun.withAll(
  root = "/safe/workspace",
  budgetUsd = 0.10,
  maxDepth = 2
) { (sandbox, budget, permit) =>
  // sandbox: FileSandbox^ — capture-checked, cannot outlive this scope
  // budget: BudgetSlice^ — spending authority, tracks remaining balance
  // permit: SpawnPermit^ — delegation authority, limits depth

  // Read within sandbox (safe)
  val content = sandbox.read("data/input.txt")

  // sandbox.read("../../etc/passwd")  // throws SecurityException at runtime
  // AND the compiler prevents sandbox from leaking outside withAll

  // Budget tracks spending
  budget.spend(0.02)
  val childBudget = budget.childSlice(0.5) // deducts from parent

  // Permit limits delegation
  permit.canSpawn       // true (maxDepth = 2)
  permit.childPermit    // Some(SpawnPermit(1))
}
```

Two approaches exist:
- **Capture checking** (`experimental/Capabilities.scala`) — Scala 3 `language.experimental.captureChecking` with `SharedCapability`. The compiler tracks capability lifetimes.
- **zio-blocks/scope** (`experimental/ScopedCapabilities.scala`) — Non-experimental alternative using opaque `$[A]` types and `Unscoped` type class. Works today without experimental flags.

---

## Example 12: Codex Interpreter — Same DSL, Different Provider

The DSL is provider-independent. The same `Agent`, `AgentRun`, `AgentEvent`, `ExecutionPolicy`, and `AgentBuilder` types work identically with the OpenAI Codex SDK.

```scala
import com.tjclp.scalagent.codex.*
import com.tjclp.scalagent.interop.codex.CodexInterpreter

// Create a Codex client (picks up OPENAI_API_KEY from env)
val client = CodexClient.create()
val threadOptions = CodexThreadOptions(
  sandboxMode = Some(SandboxMode.ReadOnly),
  approvalPolicy = Some(ApprovalMode.Never)
)

// Same Agent trait as Claude
val agent: Agent[Any, String, String] =
  CodexInterpreter.string(client, threadOptions)

// Same ExecutionPolicy
val policy = ExecutionPolicy(maxTurns = Some(3))

// Same run → events → result pattern
val run = agent.run("user", "What is 7 * 8?", policy)

// Same TraceSummary → Evaluation pipeline
val trace = TraceSummary.fromEvents(events)
val eval = Evaluation.fromTrace("user", output, trace, utility)
```

Builder works too — `sandboxMode` maps from capability types:

```scala
val readOnlyAgent = CodexInterpreter.builder(client, threadOptions)
  .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
  .withBudget
  .build
// sandboxMode = ReadOnly (derived from CanUseTools[ReadOnlyTools])
```

**Live example:** `./mill examples.run dsl-codex`

---

## Example 13: Cross-Provider Chain — Claude and Codex Cooperating

Both providers implement `Agent[Any, String, String]`. They can chain: one generates, the other answers.

```scala
val claudeAgent: Agent[Any, String, String] =
  ClaudeInterpreter.string(claudeAgent, claudeOptions)

val codexAgent: Agent[Any, String, String] =
  CodexInterpreter.string(codexClient, codexOptions)

// Claude generates a question
val question = claudeAgent.run("orchestrator", "Generate a trivia question about space.", policy).result

// Codex answers it — same Agent trait, same policy, same evaluation
val answer = codexAgent.run("orchestrator", question, policy).result

// Both runs produce AgentEvent streams, both fold into TraceSummary,
// both score via Utility.weighted — the DSL doesn't care which provider.
```

**Live example:** `./mill examples.run dsl-cross`

---

## Future: Multi-Turn Conversation (Not Yet Implemented)

The `Conversation` DSL is Priority 6 in NEXT.md, deferred until `ContextKernel` exists:

```scala
// Proposed API (not yet implemented)
trait Conversation[-P, +O]:
  def turn(principal: P, message: String, policy: ExecutionPolicy): AgentRun[?, O]
  def history: IO[AgentError, List[AgentEvent]]
  def close: IO[AgentError, Unit]
```

Until then, multi-turn is still supported via the existing `Claude.conversation` / `ClaudeSession[S]` API.

---

## Anti-Examples: What the DSL Should NOT Look Like

### Anti-example: universal config bag

```scala
// BAD: just renaming AgentOptions
val agent = Agent(
  model = "haiku",
  maxTurns = 5,
  maxBudgetUsd = 1.0,
  tools = List(...),
  mcpServers = Map(...),
  permissionMode = "dontAsk",
  structuredOutput = Some(schema),
  hooks = Map(...)
)
```

This is `AgentOptions` with a different name. It mixes semantic policy with provider config. The DSL separates these: `ExecutionPolicy` for semantics, `AgentOptions` for provider config, `AgentBuilder` for capability declaration.

### Anti-example: lowest-common-denominator events

```scala
// BAD: losing provider-specific information
enum AgentEvent:
  case Text(value: String)
  case Done
```

This throws away tool calls, delegation, native events. The normalized event set must be rich enough to fold into a `TraceSummary`. The `Native(tag, payload)` case is the lossless escape hatch for anything the ADT doesn't cover.

### Anti-example: forced abstraction over providers

```scala
// BAD: pretending all providers work the same way
trait Agent:
  def run(prompt: String): Stream[Event]
  // No principal, no policy, no typed output, no capabilities
```

This collapses everything into a string-in, events-out pipe. It erases the semantic content (principal, policy, capabilities, budget, depth) that makes the DSL valuable.

### Anti-example: phantom types without runtime enforcement

```scala
// BAD: type says CanUseTools but nothing actually restricts tools at runtime
val agent: TypedAgent[Any, String, String, CanUseTools[ReadOnlyTools]] =
  AgentBuilder(rawAgent)          // bare builder — no agentTransform
    .withReadOnlyTools(surface)
    .build
// Type is correct, but rawAgent still has full tool access at the provider level
```

The `agentTransform` callback is what makes phantom types real. `ClaudeInterpreter.builder()` provides a transform that wires `ToolSurface.distinctAllowedTools` into `AgentOptions.allowedTools`. Bare `AgentBuilder(agent)` uses an identity transform — types check, but nothing is enforced.

Always use `ClaudeInterpreter.builder()` (or another interpreter's builder) to get runtime enforcement backing the type-level claims.

---

## Design Validation Checklist

Each DSL type passes these checks:

- [x] Can express the equivalent current-API pattern without loss of power
- [x] Makes at least one currently-implicit concept explicit (principal, policy, capability, budget)
- [x] Preserves access to provider-native features via `AgentEvent.Native` escape hatch
- [x] Does not require users to learn the DSL to use existing `Claude*` APIs
- [x] Composes with other DSL types (e.g., capabilities + policy + evaluation + logging)
- [x] The type signature tells you something useful that prose documentation currently has to explain
