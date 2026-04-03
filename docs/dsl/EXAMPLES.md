# DSL Usage Examples

Status: exploratory.

These examples show what the DSL should feel like in practice. Each example is grounded in a pattern that already exists in the codebase examples, rewritten to use the proposed semantic types.

The goal is not to specify exact syntax. It is to make the design testable: if a proposed type system cannot express these patterns cleanly, the design needs adjustment.

## Example 1: One-Shot Query

Current pattern (from `SimpleQuery.scala`):

```scala
val options = AgentOptions.default
  .withModel(Model.haiku)
  .withPermissionMode(PermissionMode.DontAsk)
  .withMaxTurns(PositiveInt.literal(5))

val answer: IO[AgentError, String] =
  Claude.ask("What is 2 + 2? Reply with just the number.", options)
```

Proposed DSL equivalent:

```scala
val policy = ExecutionPolicy(
  budget = Budget.usd(0.01),
  maxTurns = Some(5)
)

val answer: IO[AgentError, String] =
  agent.run(User.local, "What is 2 + 2?", policy).result
```

What changed:
- Policy is semantic, not provider-shaped
- Principal is explicit
- Model selection moves to the interpreter, not the call site
- `Budget` is a first-class object, not a raw Double

What did not change:
- The return type is still `IO[AgentError, String]`
- The ergonomic `ask`-style shorthand can still exist as sugar

## Example 2: Streaming

Current pattern:

```scala
Claude.query("Count from 1 to 5.", options)
  .textOnly
  .tap(text => Console.printLine(s">> $text").orDie)
  .runCollect
```

Proposed DSL equivalent:

```scala
val run = agent.run(User.local, "Count from 1 to 5.", policy)

run.events
  .collect { case AgentEvent.TextDelta(text) => text }
  .tap(text => Console.printLine(s">> $text").orDie)
  .runCollect
```

What changed:
- Events use the normalized `AgentEvent` ADT
- `.textOnly` becomes a collect over `TextDelta`

What should also work:

```scala
// Access native events when needed
run.events
  .collect { case AgentEvent.Native(tag, payload) => (tag, payload) }
  .tap((tag, payload) => Console.printLine(s"native: $tag").orDie)
  .runDrain
```

## Example 3: Structured Output

Current pattern (from `StructuredOutputExample.scala`):

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

Proposed DSL equivalent:

```scala
case class Analysis(
  @description("positive, negative, or neutral")
  sentiment: String,
  confidence: Double,
  keywords: List[String]
)
given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]

// Output type O = Analysis, enforced at compile time
val typedAgent: Agent[User, String, Analysis] = claudeInterpreter.typed[Analysis]

val run: AgentRun[?, Analysis] =
  typedAgent.run(User.local, "Analyze: 'Great product!'", policy)

val analysis: IO[AgentError, Analysis] = run.result
```

What changed:
- The output type is part of the agent's signature, not a runtime option
- `StructuredOutput` derivation is unchanged — it is already DSL-quality

## Example 4: Custom Tools

Current pattern (from `CustomToolExample.scala`):

```scala
val weatherTool = ToolDef.fromInput[WeatherInput](
  name = "get_weather",
  description = "Get current weather for a location"
)(input => ZIO.succeed(ToolResult.Success(s"72°F in ${input.location}")))

val options = AgentOptions.default.withTools(List(weatherTool))
```

Proposed DSL equivalent:

```scala
val weatherTool = ToolDef.fromInput[WeatherInput](
  name = "get_weather",
  description = "Get current weather for a location"
)(input => ZIO.succeed(ToolResult.Success(s"72°F in ${input.location}")))

// Tools become a capability requirement
val toolSurface = ToolSurface(weatherTool)

val agent = claudeInterpreter.withCapabilities(toolSurface)
// agent type now includes CanUseTools evidence
```

What changed:
- `ToolDef` is identical — it is already the right abstraction
- Tools are provided as capabilities, not config fields
- The interpreter carries the capability evidence

## Example 5: Multi-Turn Conversation

Current pattern (from `SessionExample.scala`):

```scala
Claude.conversation(options) { session =>
  for
    first  <- session.ask("What is 10 + 5?")
    second <- session.ask(s"Now double $first.")
  yield second
}
```

Proposed DSL equivalent:

```scala
agent.conversation(User.local, policy) { conv =>
  for
    first  <- conv.turn("What is 10 + 5?").result
    second <- conv.turn(s"Now double $first.").result
  yield second
}
```

What changed:
- Principal is explicit
- Policy governs the entire conversation, not each turn
- `conv.turn` returns an `AgentRun`, so you can stream or collect per turn

What should also work:

```scala
// Stream events from a single turn within a conversation
agent.conversation(User.local, policy) { conv =>
  val run = conv.turn("Explain quantum computing.")
  run.events
    .collect { case AgentEvent.TextDelta(t) => t }
    .tap(Console.print(_).orDie)
    .runDrain *>
  run.result
}
```

## Example 6: Subagent Delegation

Current pattern (from `SubagentExample.scala`):

```scala
val codeReviewer = AgentDefinition(
  description = "Expert code reviewer",
  prompt = "You are a senior code reviewer...",
  tools = Some(List(ToolName.Read, ToolName.Grep, ToolName.Glob)),
  model = Some(AgentModel.Haiku)
)

val options = AgentOptions.default
  .withAgent("code-reviewer", codeReviewer)
```

Proposed DSL equivalent:

```scala
val reviewerPolicy = ExecutionPolicy(
  budget = parentPolicy.budget.slice(0.3), // 30% of parent budget
  maxTurns = Some(10)
)

val reviewer: Agent[Supervisor, CodeReviewRequest, CodeReview] =
  claudeInterpreter
    .typed[CodeReview]
    .withCapabilities(readOnlyTools)
    .withPolicy(reviewerPolicy)

// Parent agent delegates to reviewer
val parentAgent = claudeInterpreter
  .withCapabilities(allTools, CanSpawn(reviewer))
```

What changed:
- Budget slicing is explicit: the child gets a bounded share
- The child's capability set is visible in its type
- Delegation appears as a typed relationship, not a string key in a config map

## Example 7: Permission / Capability Gating

Current pattern (from `PermissionExample.scala`):

```scala
val handler: CanUseTool = (toolName, input, ctx) =>
  if toolName == ToolName.Bash then
    ZIO.succeed(PermissionResult.deny("Bash disabled"))
  else
    ZIO.succeed(PermissionResult.allow)

val options = AgentOptions.default.withCanUseTool(handler)
```

Proposed DSL equivalent:

```scala
// Option A: static capability restriction (compile-time)
type SafeTools = CanUseTools[ReadOnlyTools]
val agent: Agent[User, String, String] { type Requirements = SafeTools } = ...

// Option B: dynamic permission policy (runtime, same as today)
val permissionPolicy: PermissionPolicy = PermissionPolicy.denyTools(ToolName.Bash)
val agent = claudeInterpreter.withPermissions(permissionPolicy)
```

Both should be expressible. Static capabilities are for architecture-level invariants. Dynamic permissions are for runtime policy that may change per principal or context.

## Example 8: Lifecycle Middleware

Current pattern (hooks):

```scala
val hooks = HookConfig(
  preToolUse = Some(hook => ZIO.logInfo(s"Before: ${hook.toolName}")),
  postToolUse = Some(hook => ZIO.logInfo(s"After: ${hook.toolName}"))
)

val options = AgentOptions.default.withHooks(hooks)
```

Proposed DSL equivalent:

```scala
val auditMiddleware: RunMiddleware = RunMiddleware.fromEvents {
  case AgentEvent.ToolCall(name, args) =>
    AuditLog.record(s"tool_call: $name")
  case AgentEvent.Completed(summary) =>
    AuditLog.record(s"completed: cost=${summary.costUsd}")
  case _ => ZIO.unit
}

val agent = claudeInterpreter.withMiddleware(auditMiddleware)
```

What changed:
- Lifecycle interception is composable middleware, not a fixed config struct
- Multiple middleware layers can be stacked
- Middleware operates on normalized events, not provider-specific hook types

## Example 9: Remote Agent via A2A

Current pattern (from `A2AExample.scala`):

```scala
val client = A2AClient(url = "http://remote-agent:8080")
val tool = A2ATool.fromClient("remote-analyst", "Analyzes data", client)
val options = AgentOptions.default.withTools(List(tool))
```

Proposed DSL equivalent:

```scala
// A2A agent looks like any other Agent
val remoteAnalyst: Agent[Supervisor, AnalysisRequest, AnalysisResult] =
  a2aInterpreter.connect("http://remote-agent:8080")

// Use it as a delegation target, same as a local subagent
val parent = claudeInterpreter
  .withCapabilities(CanSpawn(remoteAnalyst))
```

What changed:
- Remote agents have the same `Agent` type as local ones
- The A2A transport is an interpreter detail, not visible to the caller
- Delegation works identically for local and remote agents

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

This is `AgentOptions` with a different name. It mixes semantic policy with provider config.

### Anti-example: lowest-common-denominator events

```scala
// BAD: losing provider-specific information
enum AgentEvent:
  case Text(value: String)
  case Done
```

This throws away tool calls, delegation, native events. The normalized event set must be rich enough to be useful, with `Native` as the escape hatch.

### Anti-example: forced abstraction over providers

```scala
// BAD: pretending all providers work the same way
trait Agent:
  def run(prompt: String): Stream[Event]
  // No principal, no policy, no typed output, no capabilities
```

This collapses everything into a string-in, events-out pipe. It erases the semantic content that makes the DSL valuable.

## Design Validation Checklist

Each proposed DSL type should pass these checks:

- [ ] Can express the equivalent current-API pattern without loss of power
- [ ] Makes at least one currently-implicit concept explicit (principal, policy, capability, budget)
- [ ] Preserves access to provider-native features via escape hatch
- [ ] Does not require users to learn the DSL to use existing `Claude*` APIs
- [ ] Composes with other DSL types (e.g., capabilities + policy + middleware)
- [ ] The type signature tells you something useful that prose documentation currently has to explain
