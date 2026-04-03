# Roadmap

Status: exploratory and additive.

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

Initial target package layout:

```text
src/com/tjclp/scalagent/core/
  Agent.scala
  AgentRun.scala
  AgentEvent.scala
  Capability.scala
  ExecutionPolicy.scala
  Budget.scala
  Utility.scala
  ContextKernel.scala
  Delegation.scala

src/com/tjclp/scalagent/interop/
  claude/
  a2a/
  mcp/
  openai/
  codex/
```

The exact names may change. The important part is the separation between core semantics and backend interpreters.

## Phase 0: Documentation and Vocabulary

Deliverables:

- establish the semantic vocabulary in `docs/dsl`
- define the boundary between DSL, A2A, MCP, and provider runtimes
- settle on the minimum core concepts before adding code

Non-goals:

- no public API breakage
- no mass rename of current `Claude*` surfaces

## Phase 1: Introduce a Tiny Core

Deliverables:

- add `Agent`, `AgentRun`, `AgentEvent`, and `ExecutionPolicy`
- keep the initial core intentionally small
- make the first version map cleanly onto existing Claude behavior

Rules:

- do not try to encode every provider feature yet
- keep native escape hatches available
- prefer additive wrappers over invasive rewrites

Success criteria:

- we can express a one-shot typed run without mentioning Claude in the core package
- we can stream normalized events while still retaining native events

## Phase 2: Capability Lifting

Deliverables:

- move authority concerns into composable capabilities
- introduce typed budget and deadline objects
- separate semantic policy from provider-native options

Likely first capabilities:

- tool use
- delegation
- memory read/write
- human escalation
- clock and deadline access

Success criteria:

- "can this agent do X?" is answered by capabilities or policy, not by prose documentation alone

## Phase 3: Claude Interpreter

Deliverables:

- implement a Claude interpreter from core DSL to current Claude runtime
- map normalized `AgentEvent` values from `AgentMessage`
- preserve access to native `QueryStream` and session controls where available

Important constraint:

- `ClaudeAgent`, `AgentOptions`, and `QueryStream` should remain first-class for users who want SDK-shaped control

Success criteria:

- the new DSL can sit on top of current Claude functionality without losing major power

## Phase 4: Protocol Interpreters

Deliverables:

- A2A adapter that treats a remote A2A agent as an `Agent`
- MCP adapter that treats tools/resources/prompts as typed capabilities
- protocol-specific metadata mappers for budgets, deadlines, and policy hints

Success criteria:

- horizontal orchestration via A2A
- vertical tool access via MCP
- neither protocol becomes the semantic center of the library

## Phase 5: Delegation and Sprawl Control

Deliverables:

- explicit parent-to-child budget slicing
- typed or policy-enforced delegation depth
- observable delegation events in traces
- fallback chains for limit hits: fail, ask human, escalate, or reroute

This is where the design becomes materially useful beyond API cleanup.

Success criteria:

- a parent agent can only delegate within its policy envelope
- child runs cannot exceed their allocated budget
- trace summaries can report delegation depth and fan-out

## Phase 6: Utility and Evaluation

Deliverables:

- principal-relative `Utility`
- trace summaries and evaluation hooks
- the beginnings of an empirical complexity model

This phase is about making "good agent" measurable without pretending utility is universal.

## Phase 7: Capture Checking Experiments

Deliverables:

- experimental module for non-escaping rights
- prototype capture-checked values such as `BudgetSlice` and `SpawnPermit`

Important constraint:

- keep this experimental until ergonomics and compiler support are acceptable

This should be the sharpest tool in the box, not the first one we reach for.

## What We Should Not Do First

- do not genericize `AgentOptions` into an all-purpose universal config type
- do not force all runtimes to expose identical control surfaces
- do not replace provider-native ADTs with a flattened generic event model only
- do not hide native handles from advanced users

## Open Questions

- How much of stochasticity belongs in the public API versus evaluators and trace summaries?
- Should depth be modeled with Peano-style phantom types, singleton integers, or purely runtime policy first?
- How should `ExecutionPolicy` map to provider-native controls that do not line up one-to-one?
- Which normalized events are stable enough to commit to early?
- What is the smallest useful context model for `ContextKernel`?

## Immediate Next Step After Docs

The next code step should be a tiny, non-breaking `core` experiment:

- `Agent`
- `AgentRun`
- `AgentEvent`
- `ExecutionPolicy`

Then prove it against one real interpreter: Claude.
