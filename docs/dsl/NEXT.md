# Next Steps

Status: post-exploration. Core DSL, two provider interpreters (Claude + Codex), experimental control-plane safety, live cross-provider examples, and gated semantic review exist on `dsl/core-exploration`.

## Branch Summary

12 commits, ~7,200 lines added, 0 lines removed from existing code:

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
| `28cb8f5` | 6+ | Live examples, CustomTools fix, TraceLogger.callbackZIO |
| `42c9a6d` | 8 | Codex interpreter — proves DSL is provider-independent |
| `657de26` | 8 | Codex + cross-provider examples, ExampleRunner dispatcher |
| `edb236c` | fix | examples.run forwards CLI args via EXAMPLE env |
| `HEAD` | 6+ | Score breakdowns, effectful reviewers, agentic review permits |

43 test suites, 0 failures. All existing tests unchanged.

## Running Examples

```bash
./mill examples.run dsl-basic        # DSL one-shot + streaming + eval
./mill examples.run dsl-builder      # Builder + read-only tools + JSONL logging
./mill examples.run dsl-delegation   # Typed parent/child with Peano depth
./mill examples.run dsl-review       # Operational score + gated semantic review
./mill examples.run dsl-codex        # Same DSL, Codex provider
./mill examples.run dsl-cross        # Claude ↔ Codex cross-provider chain
./mill examples.run -- --help        # List all 19 examples
```

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
- **Reviewer budgeting**: verify `ReviewPermit` / semantic reviewers behave as intended in live runs

## Priority 3.5: Review Semantics

Current state:

- `Utility` gives pure heuristic scoring
- `ScoreBreakdown` makes that heuristic explainable
- `Reviewer` gives effectful semantic review
- `ReviewPermit` gates nondeterministic judge-model use explicitly

Near-term follow-ups:

- add a first-class combined view (`operationalScore` vs `semanticReview`)
- add success-gated defaults for production dashboards
- add live reviewer examples beyond Claude

## Priority 3.5: Score Semantics

Current `Evaluation.score` is useful, but mostly as an **operational heuristic**:

- built-in utilities score run success, cost, latency, and trace size
- they do not yet provide domain-specific correctness by default
- semantic scoring is possible now via typed custom `Utility.from(...)`

Near-term follow-up work:

- add named score breakdowns alongside the scalar
- add "success-gated" compositions for workflows where failed runs should score near zero
- add example semantic scorers that actually inspect typed output

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
| MCP resources/prompts implementation blocked | Medium | SDK doesn't expose them yet |
| A2AServerAdapter is thin | Medium | Real DSL-backed executor exists, but transport still reuses the existing A2A server stack |
| Capture checking + ZIO macro conflict | Low | Workaround: separate files. CC is experimental. |
| `ContextKernel` not implemented | Low | Deferred intentionally — needs conversation support first |

## Key Patterns to Preserve

- **Additive only** — never modify existing `Claude*` types
- **Core is provider-independent** — no JS imports in `core/`
- **`Native` escape hatch** — every event mapper preserves provider-specific events
- **`agentTransform` callback** — how builders wire phantom types to runtime enforcement
- **`SharedRun` pattern** — Queue + Promise + forkDaemon for single-stream execution sharing
- **Two capture checking approaches** — real CC in `experimental/Capabilities.scala`, zio-blocks in `experimental/ScopedCapabilities.scala`
