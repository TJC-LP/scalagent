# Vision

Status: living document.

`scalagent` should become a framework for mission-critical agent execution — defense, critical infrastructure, regulated environments — not just a wrapper around a single SDK.

It targets domains where operators need typed authority boundaries, auditable execution, and deterministic lifecycle behavior before they will deploy an agent in production.

The core idea is simple:

- keep the semantic kernel small and strict
- make major harnesses plug and play
- treat providers, protocols, and transports as adapters around that kernel

### Vocabulary

A **harness** is a strategic integration point — a provider, protocol, or runtime that the framework supports at high quality. In code, harnesses are realized as **interpreters** (e.g., `ClaudeInterpreter`, `CodexInterpreter`, `A2AInterpreter`) that translate DSL operations into provider-specific calls. The **kernel** is the provider-independent semantic core (`core/`). **Adapters** are thin protocol bridges (wire formats, transport) that sit below interpreters.

## North Star

We want `scalagent` to be the place where an agent run becomes dependable:

- typed inputs and outputs
- explicit execution policy
- explicit tool and capability boundaries
- observable event streams
- deterministic lifecycle semantics
- auditable side effects

If a system matters enough that operators need to ask "what happened, why did it happen, and can I trust it to behave the same way under pressure?", `scalagent` should have a coherent answer.

The semantic kernel is grounded in a formal model: `Agent = I → D(Eff[O])`, where `I` is typed input (prompt, principal, policy, tool surface), `D` is a probability distribution over executions, `Eff` captures observable side effects, and `O` is typed output. See `docs/dsl/FOUNDATIONS.md` for the full denotational sketch.

## What We Are Building

`scalagent` should evolve into a constrained control plane for agents.

That means:

- a stable provider-independent kernel for runs, events, policies, summaries, and capabilities
- harnesses for major runtimes and protocols that are easy to swap in and out
- runtime enforcement that matches the type story
- strong interruption, timeout, retry, and cancellation behavior
- traceability that makes postmortems and audits practical

The framework should make the safe path the normal path.

## Major Harnesses

The priority is not "integrate everything." The priority is to support a small number of major harnesses well enough that they are truly plug and play.

Today that likely means:

- Claude / Claude Code style harnesses
- Codex style harnesses
- A2A client/server harnesses
- MCP tool and resource harnesses

A harness should plug into the same semantic model rather than forcing the rest of the framework to become backend-shaped.

## Design Principles

### Semantics First

The framework center should be the meaning of a run, not the wire format of a provider.

Core types should describe:

- what was requested
- what events happened
- what policy applied
- what result was produced
- what failed and how

### Strict Kernel, Rich Adapters

The kernel should stay narrow and boring.

Adapters can be rich, provider-specific, and ergonomic, but they should not redefine run semantics.

### Types Matter When They Change Reality

Type-level capabilities, typed outputs, and delegation constraints are valuable only when they correspond to real runtime enforcement.

`scalagent` should prefer invariants that are both:

- visible in types
- enforced in execution

Today, the critical constraints are fully enforced: tool restriction via `agentTransform`, delegation depth via Peano types and runtime assertions, budget propagation to provider options, filesystem sandboxing with symlink traversal prevention. Other capabilities — `CanReadMemory`, `CanEscalateHuman`, the `Classified[A, L]` visibility lattice — are forward-looking type markers that compile-time check but lack runtime enforcement. The gap is intentional and tracked; closing it is a standing priority (see Strategic Direction item 3).

### Side Effects Need Names

Mission-critical agents fail when side effects are implicit.

Tools, delegation, memory access, filesystem access, external calls, and human escalation should all be explicit capabilities with observable traces.

### Cancellation Is a Feature, Not Cleanup

Runs must stop when callers stop caring.

Deadlines, interrupts, scope exit, remote cancellation, and partial-consumer shutdown should all terminate underlying work predictably.

### Auditability Is a Core Capability

The framework should make it easy to answer:

- what did the agent see?
- what did it do?
- what tools did it call?
- what policy gates applied?
- what result did it return?
- what error path did it follow?

This is not ancillary logging. It is part of the product.

## What We Are Not Building

We are not trying to build:

- a vague "universal AI abstraction"
- a framework that hides all provider-specific power
- autonomy for its own sake
- a giant catalog of shallow integrations
- a type-level fantasy that is not backed by runtime behavior

Breadth without control is not the goal.

## Strategic Direction

Over time, the framework should move in these directions:

1. Stabilize the kernel.
2. Keep harnesses additive and swappable.
3. Strengthen runtime enforcement for every declared capability.
4. Treat traces, summaries, and replayable evidence as first-class outputs.
5. Separate stable APIs from exploratory DSL work aggressively.
6. Test hostile conditions, not just happy paths. Mission-critical deployment means adversarial robustness: capability escalation prevention, path traversal defense, sandbox escape rejection, malformed input resilience. Every security boundary should have a test that attempts to break it.

## Implications for the Codebase

This vision suggests a few practical rules:

- `core/` should remain the semantic center.
- interpreter and protocol modules should behave like harnesses, not alternate frameworks.
- experimental ideas should stay clearly marked until semantics and enforcement are aligned.
- new features should usually improve determinism, observability, or control before they improve breadth.
- adding a harness is justified when it cleanly maps onto the kernel and strengthens the plug-and-play story.

## Open Questions

These are good areas to refine over time:

Resolved: major harnesses are Claude, Codex, A2A, and MCP (see above).

Open:

- What is the minimum stable kernel surface we commit to supporting long-term?
- How much capability structure belongs in the public API versus internal DSL layers?
- What should a replayable or auditable run artifact look like? (Traces exist; the artifact format does not.)
- Where should the boundary sit between typed policy and operational policy?
- When should `CanReadMemory`, `CanEscalateHuman`, and `Classified` gain runtime enforcement?

## Working Thesis

The best version of `scalagent` is a typed, auditable, policy-enforcing runtime for constrained agent execution in defense and regulated environments, with a small number of high-quality harnesses that plug into the same semantic model backed by formal grounding.

If we preserve that shape, the framework can grow without becoming soft.
