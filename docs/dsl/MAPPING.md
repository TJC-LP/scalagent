# Type Mapping

Status: exploratory.

This document bridges the existing codebase types to the proposed DSL concepts. It is the concrete foundation for Phase 1 and 2 work — every DSL type should have a clear relationship to what already exists.

## Current Type Inventory

The codebase already models most of the concepts the DSL needs. The gap is not missing features — it is that these features are organized around a single provider rather than around semantic roles.

| Existing Type | Package | DSL Concept |
|---|---|---|
| `ClaudeAgent` | root | `Agent` (one-shot interpreter) |
| `ClaudeSession[S]` | session | `Conversation` / `Agent` with turn state |
| `QueryStream` | streaming | `AgentRun` (event stream + control surface) |
| `AgentMessage` | messages | `AgentEvent` (normalized event ADT) |
| `AgentOptions` | config | `ExecutionPolicy` + `Capability` bundle |
| `AgentDefinition` | config | `Agent` definition (delegation target) |
| `AgentError` | errors | `AgentError` (preserved, possibly extended) |
| `ToolDef[A]` | tools | typed tool capability |
| `ToolInput.derive[A]` | tools (macros) | tool input schema derivation |
| `StructuredOutput.derive[A]` | config (macros) | typed output `O` derivation |
| `CanUseTool` | permissions | `Capability` / permission policy |
| `PermissionResult` | permissions | authorization decision |
| `HookEvent` | hooks | lifecycle event (maps to `AgentEvent` subset) |
| `HookConfig` / `HookCallback` | hooks | lifecycle interceptor |
| `ResultOutcome` | messages | terminal event within `AgentRun` |
| `A2AClient` / `A2AServer` | a2a | remote `Agent` interpreter |
| `A2ATool` | a2a | delegation-as-tool adapter |
| `ContentBlock` | messages | event payload types |

## Mapping Details

### Agent

The core `Agent[-P, -I, +O]` trait wraps a provider-specific runtime.

Today this is `ClaudeAgent`:

```scala
// Current: provider-shaped
trait ClaudeAgent:
  def query(prompt: String, options: AgentOptions): ZStream[Any, AgentError, AgentMessage]
  def queryComplete(prompt: String, options: AgentOptions): IO[AgentError, QueryResult]

// Proposed: semantics-shaped
trait Agent[-P, -I, +O]:
  type Requirements
  def run(principal: P, input: I, policy: ExecutionPolicy): AgentRun[Requirements, O]
```

The Claude interpreter would delegate to `ClaudeAgent` internally. The mapping is:

| DSL parameter | Current equivalent |
|---|---|
| `principal: P` | implicit (whoever holds the runtime) |
| `input: I` | `prompt: String` |
| `policy: ExecutionPolicy` | fields scattered across `AgentOptions` |
| `AgentRun.events` | `ZStream[..., AgentMessage]` |
| `AgentRun.result` | `QueryResult.outcome` |

### AgentRun ↔ QueryStream

`QueryStream` is already close to `AgentRun`. It provides:

- `messages: ZStream[Any, AgentError, AgentMessage]` → `AgentRun.events`
- `interrupt: Task[Unit]` → run cancellation
- `setPermissionMode`, `setModel`, `mcpServerStatus` → control surface

The gap: `QueryStream` couples the event stream with provider-specific control methods. `AgentRun` separates the normalized event stream from provider-native handles.

```scala
// Proposed relationship
final case class AgentRun[-R, +O](
    events: ZStream[R & Scope, AgentError, AgentEvent],
    result: ZIO[R & Scope, AgentError, O]
):
  // Extension point: native handle access
  def native[H](using HasNative[H]): H
```

The `native` accessor lets callers reach `QueryStream` when they need provider-specific control without polluting the core type.

### AgentEvent ↔ AgentMessage

`AgentMessage` is a 20+ case sealed enum tracking SDK-level events. `AgentEvent` is the normalized subset.

| AgentEvent | AgentMessage source |
|---|---|
| `TextDelta(value)` | `Assistant` → `ContentBlock.Text` |
| `ToolCall(name, args)` | `Assistant` → `ContentBlock.ToolUse` |
| `ToolResult(name, value, isError)` | `User` → `ContentBlock.ToolResult` |
| `DelegationStarted(label, childId)` | `StreamEvent` (SubagentStart) |
| `DelegationFinished(childId, status)` | `StreamEvent` (SubagentStop) |
| `Status(value)` | `System`, `StreamEvent` |
| `Completed(summary)` | `Result(Success(...))` |
| `Native(tag, payload)` | everything else, losslessly |

The `Native` case is critical: it preserves provider-specific events that don't map to a normalized concept. This is what prevents the DSL from being lossy.

### ExecutionPolicy ↔ AgentOptions (subset)

`AgentOptions` has 40+ fields mixing semantic policy with provider-native knobs. `ExecutionPolicy` extracts the semantic subset:

| ExecutionPolicy field | AgentOptions field |
|---|---|
| `budget: Budget` | `maxBudgetUsd` (currently just a Double) |
| `deadline: Option[Deadline]` | not present today |
| `maxTurns: Option[Int]` | `maxTurns: Option[PositiveInt]` |
| `stopStrategy` | `stopReason` (partially, in error handling) |
| `fallback` | not present today |

Provider-native options that stay in `AgentOptions`:

- `model`, `maxTokens`, `systemPrompt` → model configuration
- `permissionMode` → provider-level permission
- `mcpServers` → server configuration
- `thinking`, `outputFormat` → response shaping

The interpreter is responsible for translating `ExecutionPolicy` fields into the appropriate `AgentOptions` values.

### Capabilities ↔ Permissions + Tools + AgentDefinition

The existing permission system is already capability-like:

```scala
// Current
type CanUseTool = (ToolName, Json, PermissionContext) => Task[PermissionResult]

// Proposed capability traits
trait CanUseTools[T]     // T constrains which tools
trait CanSpawn[D]        // D constrains delegation depth
trait CanReadMemory[S]
trait CanWriteMemory[S]
trait CanEscalateHuman
trait HasBudget
trait HasClock
```

The mapping:

| DSL Capability | Current mechanism |
|---|---|
| `CanUseTools[T]` | `CanUseTool` handler + `allowedTools` / `disallowedTools` in options |
| `CanSpawn[D]` | `agents: Map[String, AgentDefinition]` in options |
| `CanEscalateHuman` | `permissionMode` (implicit) |
| `HasBudget` | `maxBudgetUsd` field |
| `CanReadMemory` / `CanWriteMemory` | `memoryEnabled` / `memoryScope` in options |

The DSL lifts these from scattered config fields into composable type-level requirements.

### StructuredOutput ↔ Output Type O

`StructuredOutput.derive[A]` already provides:

- compile-time JSON schema derivation
- typed parsing of model output
- `@description` annotation support

The DSL output type `O` should leverage this directly:

```scala
// O = String for unstructured
// O = MyType for structured (requires StructuredOutput[MyType] in scope)
trait Agent[-P, -I, +O]:
  def run(...)(using OutputCodec[O]): AgentRun[Requirements, O]
```

Where `OutputCodec` is either a trivial `String` passthrough or delegates to `StructuredOutput[A]`.

### ToolDef ↔ DSL Tool Surface

The existing `ToolDef[A]` is already well-typed:

```scala
case class ToolDef[A](
  name: String,
  description: String,
  inputSchema: JsonSchema,
  handler: A => Task[ToolResult]
)(using decoder: JsonDecoder[A])
```

The DSL should not replace this. Instead, a collection of `ToolDef` values should be expressible as a `CanUseTools` capability:

```scala
// Proposed: tools as a capability value
val tools: ToolSurface = ToolSurface(
  ToolDef.fromInput[SearchInput]("search", "Search the web")(doSearch),
  ToolDef.fromInput[CalcInput]("calc", "Calculate expressions")(doCalc)
)

// Capability is satisfied when tool surface is provided
type MyAgentCaps = CanUseTools[tools.type] & HasBudget
```

### Error Model

`AgentError` is already semantic and provider-independent:

| Error case | Semantic role |
|---|---|
| `MaxTurnsExceeded` | policy limit |
| `BudgetExceeded` | policy limit |
| `PermissionDenied` | capability violation |
| `ToolExecutionFailed` | effect failure |
| `SessionClosed` | lifecycle |
| `RateLimited` | provider backpressure |
| `Interrupted` | cancellation |
| `ApiError` | provider error |
| `ConfigurationError` | setup error |
| `MessageParseError` | deserialization |
| `Unknown` | catch-all |

The DSL should preserve this ADT. The only extension needed is distinguishing policy errors (budget, turns, deadline) from provider errors (API, rate limit) from effect errors (tool, permission) at the type level if needed for recovery strategies.

### Sessions / Multi-Turn ↔ Conversation

`ClaudeSession[S <: SessionState]` uses phantom types for typestate:

```scala
trait ClaudeSession[S <: SessionState]:
  def send(message: String): ZStream[Any, AgentError, AgentMessage]  // S = Open
  def ask(message: String): IO[AgentError, String]                     // S = Open
  def close(): IO[AgentError, ClaudeSession[Closed]]                  // S = Open → Closed
```

The DSL equivalent is an `Agent` that carries conversation state:

```scala
// One-shot: Agent[-P, -I, +O]
// Multi-turn: Agent[-P, -I, +O] where I includes conversation history
//   or: Conversation[-P, +O] as a stateful wrapper around Agent

trait Conversation[-P, +O]:
  def turn(principal: P, message: String, policy: ExecutionPolicy): AgentRun[?, O]
  def history: IO[AgentError, List[AgentEvent]]
  def close: IO[AgentError, Unit]
```

Open question: should `Conversation` be a separate trait, or should `Agent` itself handle multi-turn via `ContextKernel`?

### Hooks ↔ Lifecycle Interceptors

The existing 28 `HookEvent` types map to three DSL concepts:

1. **Pre/post effect hooks** (`PreToolUse`, `PostToolUse`, `PostToolUseFailure`) → capability-level interceptors
2. **Session lifecycle hooks** (`SessionStart`, `SessionEnd`, `SubagentStart`, `SubagentStop`) → `AgentEvent` subset
3. **System hooks** (`PreCompact`, `PostCompact`, `ConfigChange`, `CwdChanged`) → provider-native, pass through as `Native` events

The DSL should support lifecycle interception as middleware on `AgentRun`:

```scala
trait RunMiddleware:
  def wrap[R, O](run: AgentRun[R, O]): AgentRun[R, O]

// Example: logging middleware
val logging: RunMiddleware = new RunMiddleware:
  def wrap[R, O](run: AgentRun[R, O]): AgentRun[R, O] =
    run.copy(events = run.events.tap(e => ZIO.logInfo(e.toString)))
```

## What This Mapping Tells Us

1. **Most DSL concepts already exist in the codebase.** The work is reorganization and lifting, not invention.
2. **The escape hatch pattern (`Native`, `native[H]`) is load-bearing.** Without it, the DSL becomes a straitjacket.
3. **`AgentOptions` is doing too many jobs.** It mixes policy, capability, provider config, and tool surface. The DSL should decompose it.
4. **`StructuredOutput` and `ToolDef` are already DSL-quality.** They should be reused, not replaced.
5. **Sessions are not just "stateful agents."** They have their own lifecycle, identity, and resumability. The DSL needs a position on this.
6. **Hooks are middleware, not just events.** The DSL should model lifecycle interception as composable middleware, not just event emission.
