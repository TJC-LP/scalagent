# DSL Design Notes

Status: exploratory.

`scalagent` currently provides a strong Claude-first Scala.js facade. The next step is not to erase that surface, but to introduce a more semantic internal DSL that can sit above Claude, Codex, AI SDK, A2A, MCP, and future runtimes without collapsing everything into a lowest-common-denominator API.

This folder scopes that direction. These documents are design notes, not a promise of immediate breaking changes.

This DSL work serves the framework's mission-critical positioning (see `docs/VISION.md`): typed authority boundaries, auditable execution, and deterministic lifecycle behavior for defense and regulated environments.

## Goals

- Make agent semantics explicit: typed input/output, event traces, execution policy, budgets, delegation, and utility.
- Preserve provider-native power through interpreters and native escape hatches.
- Use Scala 3 features where they buy real invariants: typestate, phantom types, opaque types, typeclasses, and eventually capture checking.
- Separate internal semantics from external protocols such as A2A and MCP.
- Make sprawl control a first-class part of the model instead of an afterthought.

## Non-goals

- Replace A2A or MCP.
- Force every backend into one fake-universal interface.
- Encode the full probability distribution of an agent run directly in the public type signature on day one.
- Rewrite the current Claude-shaped API before the core vocabulary stabilizes.

## Current Tension

Today the public API is intentionally close to the Claude SDK:

- `ClaudeAgent`
- `ClaudeSession`
- `QueryStream`
- `AgentOptions`
- `AgentDefinition`
- `AgentMessage`

That is a strength for SDK parity, but it makes the public surface backend-shaped rather than semantics-shaped. The DSL work in this folder proposes a thin internal calculus that existing provider-specific modules can interpret.

## Document Map

- [FOUNDATIONS.md](./FOUNDATIONS.md): core semantic model, proposed Scala shapes, and type-level design principles
- [MAPPING.md](./MAPPING.md): concrete bridge from existing codebase types to proposed DSL types
- [EXAMPLES.md](./EXAMPLES.md): what the DSL should look like in practice, grounded in current usage patterns
- [PROTOCOLS.md](./PROTOCOLS.md): how the internal DSL relates to A2A, MCP, and provider SDKs
- [ROADMAP.md](./ROADMAP.md): additive migration plan from the current codebase to a richer typed core

## Working Principles

- Additive first. The initial DSL should live alongside the current API.
- Semantics first. Transport and wire formats are adapters, not the center of the model.
- Typed constraints over prose constraints. If a budget or delegation rule matters, prefer making it representable in types or executable policy.
- Preserve observability. Event traces should remain available even when the output is strongly typed.
- Keep the escape hatch. Native SDK handles still matter for advanced control surfaces.
