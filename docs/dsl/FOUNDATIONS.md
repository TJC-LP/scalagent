# Foundations

Status: exploratory.

This document describes the semantic direction for a future internal DSL. It is intentionally denotational first and implementation second.

## Problem Statement

The current library models a specific provider very well. That remains valuable.

The missing layer is a semantic core that lets us talk about:

- typed principals
- typed agent input and output
- execution traces
- delegation
- budgets and deadlines
- utility and evaluation
- context evolution across turns

without making those concepts synonymous with a single provider's SDK.

## Denotational Sketch

The working mental model is:

```text
Agent : I -> D(Eff[O])
```

Where:

- `I` is the input domain: prompt, history, tool surface, memory view, principal, and execution policy
- `D` is a probability distribution over executions
- `Eff` is effectful computation: tools, memory, delegation, human escalation, state writes, protocol I/O
- `O` is the typed observable output

This should be read as a semantic model, not as a literal Scala encoding we must reproduce directly.

## What We Actually Want to Type

The strongest first step is not "make `D` a monad." The strongest first step is to type the realized run:

- what capabilities were available
- what policy bounded the run
- what events were emitted
- what typed result was produced

That leads to a shape like:

```scala
package com.tjclp.scalagent.core

import zio.*
import zio.stream.*
import zio.json.ast.Json

trait Agent[-P, -I, +O]:
  type Requirements
  def run(
      principal: P,
      input: I,
      policy: ExecutionPolicy
  ): AgentRun[Requirements, O]

final case class AgentRun[-R, +O](
    events: ZStream[R & Scope, AgentError, AgentEvent],
    result: ZIO[R & Scope, AgentError, O]
)

enum AgentEvent:
  case TextDelta(value: String)
  case ToolCall(name: String, args: Json)
  case ToolResult(name: String, value: Json, isError: Boolean)
  case DelegationStarted(label: String, childId: String)
  case DelegationFinished(childId: String, status: String)
  case Status(value: String)
  case Completed(summary: StopSummary)
  case Native(tag: String, payload: Json)
```

This keeps the semantics explicit without pretending we can statically know the entire sampled execution graph ahead of time.

## Principals

A principal should be a typed input to execution, not an ambient comment. Human users, supervisor agents, subagents, auditors, and autonomous schedulers have different utility models and policy envelopes.

Desired properties:

- principal identity is explicit
- utility is principal-relative
- delegation changes principal context
- parent agents can act as users of child agents

A future core should make "who is this run for?" as important as "what prompt was sent?"

## Capabilities

Capabilities should be modeled as composable requirements, not flattened into one giant configuration bag.

```scala
trait CanUseTools[T]
trait CanSpawn[D]
trait CanReadMemory[S]
trait CanWriteMemory[S]
trait CanEscalateHuman
trait HasBudget
trait HasClock
```

This supports intersection-based environments such as:

```scala
type AnalystCaps = CanUseTools[ReadOnlyTools] & HasBudget
type SupervisorCaps[D] = AnalystCaps & CanSpawn[D] & CanEscalateHuman
```

This is a better fit for Scala 3 than trying to encode every runtime distinction in one `AgentOptions`-style product type.

## Execution Policy

Policies should be typed, explicit, and separate from provider-native settings.

```scala
final case class ExecutionPolicy(
    budget: Budget,
    deadline: Option[Deadline],
    maxTurns: Option[Int],
    stopStrategy: StopStrategy,
    fallback: FallbackPolicy
)
```

The core policy layer should answer:

- how much may this run spend?
- how long may it run?
- how many turns may it take?
- what happens when it hits a limit?

Provider-native knobs can still exist, but they should be interpreted from this higher-level model rather than define it.

## Delegation and Sprawl Control

Delegation is not just another tool call. It changes authority, budget, and graph shape.

The first-class concepts we want are:

- maximum delegation depth
- child budget allocation
- child capability restriction
- parent/child principal relation
- observable delegation events

Type-level depth can be modeled with phantom types:

```scala
sealed trait Depth
sealed trait Z extends Depth
sealed trait S[N <: Depth] extends Depth

trait CanSpawn[D <: Depth]:
  type ChildDepth <: Depth
```

The exact encoding is open, but the goal is clear: if an agent is out of depth, that should be unrepresentable or at least mechanically rejected before runtime.

## Context as a Stochastic Process

One of the more interesting open questions is the "distribution of context windows." The most productive framing so far is a typed transition kernel over context state:

```scala
trait ContextKernel[Ctx, Ev]:
  def seed(input: AgentInput): Ctx
  def step(ctx: Ctx, event: Ev): Ctx
  def compact(ctx: Ctx): Ctx
```

This gives us a place to formalize:

- prompt assembly
- transcript growth
- summarization and compaction
- memory injection
- subagent summaries
- context loss and approximation

The important shift is that context should become a modeled subsystem, not an opaque byproduct of sending messages to a provider.

## Utility

Utility is observer-defined. That means the DSL should not hard-code a universal reward function.

```scala
trait Utility[-P, -O]:
  def score(principal: P, output: O, trace: TraceSummary): Double
```

This leaves room for multiple utility models:

- low-cost coding assistant
- high-reliability auditor
- latency-sensitive supervisor
- research agent optimizing for recall over speed

Subagents can then maximize the utility of their parent principal rather than some global built-in objective.

### Operational vs Semantic Scoring

There is an important distinction between:

- **operational scoring**: cheap, generic heuristics based on observable run properties
- **semantic scoring**: task-aware judgments about whether the output is actually good

Today the built-in utilities are primarily **operational**:

- `reliability` checks whether the run completed successfully
- `costMinimizing` favors cheaper runs
- `latencyMinimizing` favors faster runs
- `simplicityBiased` favors smaller traces

These are useful for regression tracking and runtime optimization, but they are not the same thing as correctness.

In particular, the built-in utilities do **not** currently inspect the output value in a domain-aware way, and most do not use the principal either. The trait supports semantic scoring, but the default utilities are intentionally simple and cheap.

That means a current DSL `score` should be read as:

> "How operationally good was this run under the chosen heuristic?"

not:

> "How semantically correct or useful was the answer?"

To get semantic value, users should provide typed custom scorers:

```scala
val semanticScorer: Utility[Reviewer, CodeReview] =
  Utility.from { (principal, output, trace) =>
    val hasFindings = output.findings.nonEmpty
    val respectsBudget = trace.costUsd <= principal.maxReviewBudgetUsd
    if hasFindings && respectsBudget then 1.0 else 0.0
  }
```

This is why the DSL keeps `Utility[-P, -O]` typed over both principal and output.

## Capture Checking

Capture checking is promising, but only for places where non-escape really matters.

Best early candidates:

- `BudgetSlice`
- `SpawnPermit`
- `HumanApproval`
- `ContextLease`
- `MemoryWriteLease`

These are exactly the resources we do not want copied into long-lived closures, hidden in mutable state, or leaked to siblings. Capture checking is likely too sharp a tool to make the center of the initial DSL, but it is a strong fit for rights and leases.

## Error Algebra

The existing `AgentError` ADT is already well-structured. The DSL should preserve it and clarify the taxonomy:

**Policy errors** — the run violated its contract:
- `MaxTurnsExceeded`
- `BudgetExceeded`
- (future) `DeadlineExceeded`

**Effect errors** — a side effect failed:
- `ToolExecutionFailed`
- `PermissionDenied`

**Lifecycle errors** — the run's environment changed:
- `SessionClosed`
- `Interrupted`

**Provider errors** — the backend reported a problem:
- `ApiError`
- `RateLimited`

**Framework errors** — internal parsing or config:
- `ConfigurationError`
- `MessageParseError`
- `Unknown`

This taxonomy matters for recovery. Policy errors are deterministic and budgetable. Effect errors are retryable or reroutable. Provider errors may need backoff. Lifecycle errors require session management.

The `FallbackPolicy` in `ExecutionPolicy` should be able to dispatch on these categories:

```scala
enum FallbackPolicy:
  case Fail                              // surface the error
  case Retry(maxAttempts: Int)           // retry effect/provider errors
  case Escalate                          // ask human (requires CanEscalateHuman)
  case Reroute(to: String)              // delegate to another agent
  case Custom(handler: AgentError => FallbackPolicy)
```

## Conversations and Multi-Turn State

One-shot `Agent.run` is the simplest case. Multi-turn conversation introduces:

- **Turn state** — messages accumulate across turns
- **Session identity** — conversations are resumable
- **Context growth** — the context window fills and may compact
- **Per-turn vs per-conversation policy** — budget applies across turns, not per turn

The existing `ClaudeSession[S <: SessionState]` models this with phantom typestate. The DSL should support an equivalent:

```scala
trait Conversation[-P, +O]:
  def turn(principal: P, message: String, policy: ExecutionPolicy): AgentRun[?, O]
  def history: IO[AgentError, List[AgentEvent]]
  def close: IO[AgentError, Unit]
```

Key design question: is `Conversation` a separate trait, or is it `Agent` applied to a `ContextKernel` that carries state across turns?

The strongest argument for separation: conversations have identity and lifecycle (create, resume, close). Agents are stateless functions. Making `Conversation` a wrapper around `Agent` + `ContextKernel` preserves both views.

## Tool Surface

The existing `ToolDef[A]` is already well-typed and should be reused directly. The DSL adds one concept: tools as a composable capability rather than a config field.

```scala
final case class ToolSurface(tools: List[ToolDef[?]]):
  def ++(other: ToolSurface): ToolSurface = ToolSurface(tools ++ other.tools)
  def filter(pred: ToolDef[?] => Boolean): ToolSurface = ToolSurface(tools.filter(pred))
```

`ToolSurface` satisfies `CanUseTools`:

```scala
given [T <: ToolSurface]: CanUseTools[T] with {}
```

This lets the type system track "this agent has tools" without enumerating every tool in the type signature. Finer-grained tool typing (read-only vs read-write) can use marker traits on `ToolSurface` subtypes.

## Lifecycle Interception

The codebase has 28 hook events covering tool use, session lifecycle, compaction, config changes, and more. The DSL should model lifecycle interception as composable middleware rather than a fixed config struct.

```scala
trait RunMiddleware:
  def wrap[R, O](run: AgentRun[R, O]): AgentRun[R, O]

object RunMiddleware:
  def compose(a: RunMiddleware, b: RunMiddleware): RunMiddleware =
    new RunMiddleware:
      def wrap[R, O](run: AgentRun[R, O]): AgentRun[R, O] =
        b.wrap(a.wrap(run))
```

Provider-specific hooks (`PreCompact`, `PostCompact`, `CwdChanged`, etc.) pass through as `AgentEvent.Native` and can be intercepted by middleware that knows about them. The DSL does not need to normalize every provider lifecycle event — only the semantic ones (tool use, delegation, completion).

## What Should Stay Runtime-Level

Not every semantic concept belongs in the type system.

These should remain primarily runtime observables:

- realized stochasticity
- actual cost
- actual latency
- actual stopping time
- sampled execution graph
- model-specific confidence signals

The type system should constrain authority and shape. The runtime should observe what actually happened.

## First Design Rule

Do not build a fake-universal `Agent` interface that every backend has to lie to implement.

The core should model semantics. Backend-specific interpreters should advertise the capabilities they actually support.
