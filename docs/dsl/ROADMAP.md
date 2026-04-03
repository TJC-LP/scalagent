# Roadmap

Status: Phases 0-8 complete on `dsl/core-exploration`. No existing code was modified — all DSL work is additive.

This roadmap assumes we keep the existing public Claude-first API working while we introduce a richer internal core.

## Current Baseline

The current codebase already has several strong building blocks:

- typestate for sessions
- typed tool schemas
- typed structured output
- A2A client/server interop
- MCP-backed tool exposure
- forward-compatible event modeling

What it does not yet have is a semantic center that these features all compose around.

## Proposed Package Direction

```text
src/com/tjclp/scalagent/core/
  Agent.scala
  AgentRun.scala
  AgentEvent.scala
  Capability.scala
  ExecutionPolicy.scala
  Budget.scala
  Utility.scala
  ContextKernel.scala        # not yet implemented
  Delegation.scala

src/com/tjclp/scalagent/interop/
  claude/
  a2a/
  mcp/
  codex/
```

The exact names may change. The important part is the separation between core semantics and backend interpreters.

---

## Completed Phases

### Phase 0: Documentation and Vocabulary -- DONE

Deliverables: `docs/dsl/` with ROADMAP, MAPPING, FOUNDATIONS, EXAMPLES.

### Phase 1: Introduce a Tiny Core -- DONE (`ac78f82`)

Deliverables: `Agent`, `AgentRun`, `AgentEvent`, `ExecutionPolicy`, `Budget`, `RunSummary`, `OutputCodec`.

Success: one-shot typed run without mentioning Claude in `core/`. Normalized events with `Native` escape hatch.

### Phase 2: Capability Lifting -- DONE (`dab4683`)

Deliverables: `Capability` trait (unsealed), `CanUseTools[T]`, `CanSpawn[D]`, `HasBudget`, `CanReadMemory`, `CanWriteMemory`, `CanEscalateHuman`. Peano depth types (`Z`, `S[N]`, `DepthLTE`). `TypedAgent[P, I, O, C]` with phantom intersection accumulation. `AgentBuilder` with `agentTransform`.

Success: "can this agent do X?" is answered by the type signature.

### Phase 3: Claude Interpreter -- DONE (`ac78f82`)

Deliverables: `ClaudeInterpreter` with `string()`, `typed[A]()`, `builder()`, `typedBuilder[A]()`. `EventMapper` mapping all 20+ `AgentMessage` cases to `AgentEvent`. `SharedRun` pattern (Queue + Promise + forkDaemon).

Success: DSL sits on top of Claude without losing power. `AgentOptions` preserved for SDK-shaped control.

### Phase 4: Protocol Interpreters -- DONE (`2e64127`)

Deliverables: `A2AInterpreter` (fromClient, discover), `A2AEventMapper`, `A2AServerAdapter`. `McpToolLoader` (fromTools, toServerConfig). Core traits: `A2ARemoteAgent`, `CanDelegateA2A`, `McpToolSurface`, `McpResourceSurface`, `McpPromptSurface`.

Success: horizontal orchestration via A2A, vertical tool access via MCP.

### Phase 5: Delegation and Sprawl Control -- DONE (`dab4683`)

Deliverables: `TypedAgent.delegate` and `delegateTyped` with `HasSpawn` evidence. `DelegationPolicy` (budgetFraction, maxChildTurns). Compile-time depth enforcement via `DepthLTE`. Runtime defense-in-depth assertions.

Success: parent agents can only delegate within their policy envelope. Traces report delegation depth.

### Phase 6: Utility and Evaluation -- DONE (`de316ab`, `28cb8f5`)

Deliverables: `Utility[-P, -O]` with `costMinimizing`, `reliability`, `latencyMinimizing`, `simplicityBiased`, `weighted`. `TraceSummary.fromEvents`. `Complexity.fromTrace`. `Evaluation.evaluate`. `TraceLogger` with `noop`, `console`, `callback`, `callbackZIO`, `all`.

Success: "good agent" is measurable. Observer-dependent scoring from the formalization paper.

### Phase 7: Capture Checking Experiments -- DONE (`086a4fc`, `3327617`, `c98ab52`)

Deliverables: `FileSandbox`, `BudgetSlice`, `SpawnPermit` extending `SharedCapability` (capture-checked). `SandboxedRun` scoped callbacks. `ScopedCapabilities` via zio-blocks/scope (non-experimental). Private constructors, path traversal fix.

Success: both capture checking and zio-blocks approaches work on Scala.js.

### Phase 8: Multi-Provider Proof -- DONE (`42c9a6d`, `657de26`, `edb236c`)

Deliverables: `CodexInterpreter` backed by `@openai/codex-sdk`. `CodexClient`, `CodexThread`, `CodexEvent`/`CodexItem` ADTs. `CodexEventMapper` (8 item types → `AgentEvent`). Builder with `codexTransform` (capabilities → sandbox mode). Live examples: `DslCodexExample`, `DslCrossProviderExample` (Claude ↔ Codex chain). `ExampleRunner` dispatcher.

Success: zero changes to `core/`. Same `Agent`, `AgentRun`, `AgentEvent`, `ExecutionPolicy`, `AgentBuilder`, `TypedAgent`, `TraceSummary`, `Utility`, `Evaluation` types work identically across both providers.

---

## What We Should Not Do

- do not genericize `AgentOptions` into an all-purpose universal config type
- do not force all runtimes to expose identical control surfaces
- do not replace provider-native ADTs with a flattened generic event model only
- do not hide native handles from advanced users

## Remaining Work

See `docs/dsl/NEXT.md` for current priorities:

1. PR and review
2. Integration testing (beyond live examples)
3. `ContextKernel` (context evolution across turns)
4. SDK parity check
5. `Conversation` DSL (multi-turn)
