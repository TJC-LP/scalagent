# Next Steps

Status: post-exploration. Core DSL, interpreters, experimental control-plane safety, and live examples exist on `dsl/core-exploration`.

## Branch Summary

8 commits, ~5,500 lines added, 0 lines removed from existing code:

| Commit | Phase | What |
|---|---|---|
| `ac78f82` | 1+3 | Core types (Agent, AgentRun, AgentEvent, ExecutionPolicy) + Claude interpreter |
| `dab4683` | 2+5 | Peano depth types, intersection capabilities, TypedAgent, AgentBuilder, delegation |
| `2e64127` | 4 | First-class A2A + MCP (core traits + interop implementations) |
| `086a4fc` | 7 | Capture-checked capabilities (SharedCapability) + runtime enforcement improvements |
| `3327617` | 7 | zio-blocks/scope integration (non-experimental capability safety) |
| `c98ab52` | 7 | Sandbox hardening (private constructors, path traversal fix) |
| `de316ab` | 6 | Utility scoring, TraceSummary, Complexity, TraceLogger |
| `29bebba` | docs | NEXT.md with post-exploration priorities and known gaps |

40 test suites, 0 failures. All existing tests unchanged.

## Priority 1: PR and Review

- Push `dsl/core-exploration` branch
- Create PR against main
- Run full review — prior codex review caught real issues (runtime no-ops, path traversal)
- Squash or keep individual commits (each is a clean phase boundary)

## Priority 2: Update DSL Docs

- `docs/dsl/ROADMAP.md` — mark all phases as done, note what was actually built vs. planned
- `docs/dsl/MAPPING.md` — update with real types now that code exists
- Add a `docs/dsl/STATUS.md` summarizing current state of each component

## Priority 3: Integration Testing

The big one. All current tests are unit/compile-time. Need live integration:

- **Full DSL flow example**: `TypedAgent` with capabilities → run against live Claude → stream `AgentEvent` → fold into `TraceSummary` → score with `Utility` → log with `TraceLogger`
- **A2A round-trip**: stand up `A2AServerAdapter` exposing a DSL agent, call it via `A2AInterpreter`
- **Tool restriction verification**: `ClaudeInterpreter.builder().withReadOnlyTools().build` actually restricts tools at the provider level
- **Budget enforcement**: verify `Budget.Usd` propagates to `maxBudgetUsd` in `AgentOptions`

## Priority 4: ContextKernel

The one piece of FOUNDATIONS.md we never touched:

```scala
trait ContextKernel[Ctx, Ev]:
  def seed(input: AgentInput): Ctx
  def step(ctx: Ctx, event: Ev): Ctx
  def compact(ctx: Ctx): Ctx
```

Models context window evolution — prompt assembly, transcript growth, compaction, memory injection. Foundation for multi-turn `Conversation` support at the DSL level.

## Priority 5: SDK Parity Check

- Check if Claude Agent SDK has added MCP resource/prompt support since 0.2.90
- If so, implement `McpResourceSurface` and `McpPromptSurface` interop
- Update SDK alignment in build.mill if newer version available

## Priority 6: Conversation DSL

Multi-turn support wrapping `ClaudeSession[S]`:

```scala
trait Conversation[-P, +O]:
  def turn(principal: P, message: String, policy: ExecutionPolicy): AgentRun[?, O]
  def history: IO[AgentError, List[AgentEvent]]
  def close: IO[AgentError, Unit]
```

Bridges one-shot `Agent.run` to stateful session management. Carries `ContextKernel` state across turns.

## Known Gaps

| Gap | Severity | Notes |
|---|---|---|
| No live integration tests | High | All tests are unit/compile-time |
| MCP resources/prompts implementation blocked | Medium | SDK doesn't expose them yet |
| A2AServerAdapter is thin | Medium | Real DSL-backed executor exists, but transport still reuses the existing A2A server stack |
| Capture checking + ZIO macro conflict | Low | Workaround: separate files. CC is experimental. |
| `ContextKernel` not implemented | Low | Deferred intentionally — needs conversation support first |
| DSL docs/examples need continual tightening | Low | Keep examples aligned with honest `CustomTools` / effectful A2A flows |

## Key Patterns to Preserve

- **Additive only** — never modify existing `Claude*` types
- **Core is provider-independent** — no JS imports in `core/`
- **`Native` escape hatch** — every event mapper preserves provider-specific events
- **`agentTransform` callback** — how builders wire phantom types to runtime enforcement
- **`SharedRun` pattern** — Queue + Promise + forkDaemon for single-stream execution sharing
- **Two capture checking approaches** — real CC in `experimental/Capabilities.scala`, zio-blocks in `experimental/ScopedCapabilities.scala`
