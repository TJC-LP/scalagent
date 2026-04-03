# Protocol Boundary

Status: exploratory.

This document separates the internal DSL from the protocols and SDKs it may target.

The short version:

- the DSL is internal semantics
- A2A is remote delegation and task transport
- MCP is tool, resource, and prompt transport
- Claude, Codex, and AI SDKs are provider interpreters

Or more bluntly:

- A2A is plumbing
- the DSL is physics

## Layering

| Layer | Role | What it should standardize | What it should not try to own |
|------|------|-----------------------------|-------------------------------|
| Internal DSL | Computational semantics | principals, capabilities, policies, traces, delegation, utility, context evolution | provider wire formats |
| Provider interpreter | Runtime binding | translating DSL operations into Claude/Codex/AI SDK calls | the core semantics themselves |
| MCP | Tool and resource protocol | typed tool I/O, resources, prompts, server capability surface | inter-agent orchestration semantics |
| A2A | Inter-agent protocol | remote task creation, discovery, async lifecycle, streaming, task updates | internal execution graphs, utility, budgets, effect typing |

## What A2A Does Well

A2A is a strong fit for:

- remote agent discovery
- task lifecycle tracking
- async and long-running work
- recursive delegation across process and trust boundaries
- interop between heterogeneous agent implementations

This makes A2A a good transport for a remote `Agent` interpreter.

## What A2A Does Not Model

A2A should not be mistaken for an internal DSL because it does not natively represent:

- stochasticity
- typed effect surfaces
- execution graph complexity
- typed budgets and deadlines
- principal-relative utility
- internal context-window evolution

That is not a flaw. It is a protocol design choice.

## What MCP Does Well

MCP is a strong fit for:

- typed tool schemas
- typed resource access
- prompt surfaces
- server-declared capabilities
- embedding local or remote capabilities behind a structured contract

This makes MCP a good transport for tool capability within an agent runtime.

## Why Neither Protocol Is the DSL

Both A2A and MCP deliberately leave large parts of agent computation opaque:

- A2A optimizes for interop and delegation
- MCP optimizes for tool/resource exposure

The DSL work in this repo is about the missing semantic middle:

- how an agent is represented internally
- what authority it has
- how it delegates
- what constraints govern it
- how its context evolves
- how we evaluate whether it was good

## Recommended Boundary

The internal DSL should define concepts like:

- `Agent`
- `AgentRun`
- `AgentEvent`
- `ExecutionPolicy`
- `Budget`
- `Capability`
- `ContextKernel`
- `Utility`

Then:

- Claude interpreter: executes DSL agents against the Claude SDK
- Codex interpreter: executes DSL agents against Codex-native flows
- AI SDK interpreter: executes DSL agents through model/tool abstractions exposed there
- A2A interpreter: treats a remote A2A agent as an `Agent`
- MCP interpreter: treats tools/resources/prompts as capabilities available to an `Agent`

## Interop Strategy

The most promising architecture is not "pick A2A or MCP." It is:

- use the DSL for internal semantics
- use MCP for vertical capability access inside an agent
- use A2A for horizontal coordination across agents

That lets each layer do the job it was designed for.

## Standardized Extension Candidates

If we eventually want stronger interop between the DSL and A2A, the most useful extension targets are:

- execution budget
- deadline
- max turns
- declared effect classes
- complexity hints
- utility or policy tags

These belong as protocol extensions or metadata, not as reasons to make the core DSL protocol-shaped.

## Current Repo Mapping

Today the repo already contains three distinct kinds of surface:

- provider-specific runtime APIs: `ClaudeAgent`, `ClaudeSession`, `QueryStream`
- protocol surfaces: `A2AClient`, `A2AServer`, `A2ATool`
- tool-facing abstractions: `ToolDef`, `ToolInput`, `StructuredOutput`

The DSL direction should sit above those, not replace them blindly.
