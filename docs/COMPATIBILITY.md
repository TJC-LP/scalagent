# Compatibility Matrix

Baseline: `@anthropic-ai/claude-agent-sdk` `^0.2.90`

This document tracks the current compatibility posture of `scalagent` against the installed TypeScript SDK baseline.

## Surface Summary

| Surface | Status | Notes |
|--------|--------|-------|
| `Options` core fields | Exact / adapted mirror | Strongly-typed Scala wrappers over SDK options such as `model`, `cwd`, `systemPrompt`, `tools`, `allowedTools`, `disallowedTools`, `maxTurns`, `mcpServers`, `thinking`, `effort`, `promptSuggestions`, `sessionId`, debug controls, `executable`, `executableArgs`, `pathToClaudeCodeExecutable`, `permissionPromptToolName`, and `stderr`. |
| `AgentDefinition.maxTurns` | Exact mirror | Per-agent turn limit exposed via builder and JSON codecs. |
| `agentProgressSummaries` | Exact mirror | Enables periodic AI-generated progress summaries for subagents. |
| `TaskProgress.summary` | Exact mirror | AI-generated progress summary text on task_progress events. |
| `ModelInfo.supportsAutoMode` | Exact mirror | Whether a model supports auto mode. |
| `gcpAuthRefresh` | Exact mirror | GCP authentication refresh command. |
| Top-level `Options.skills` | Scala-only compatibility shim | The installed SDK typings do not expose top-level `skills`; `AgentOptions.withSkills(...)` prefers a synthesized or augmented main agent, then falls back to prompt injection when needed. |
| `AgentDefinition.skills` | Exact mirror | Passed through natively and used as the preferred compatibility path for preloaded skills. |
| `Query` lifecycle | Adapted mirror | Wrapped by `QueryStream`, which preserves SDK control methods and adds cleanup-aware `ZStream` semantics plus idempotent `close()`. |
| `SDKSession` lifecycle | Adapted mirror | Wrapped by `ClaudeSession`, which tracks active turn streams, supports idempotent `close()`, and exposes collection helpers. |
| SDK message unions | Adapted mirror | Known variants are modeled explicitly; unknown message, system, content, and delta variants are preserved structurally with raw payloads. |
| Unknown payload handling | Scala extension | Unknowns are not flattened to placeholder text; they are preserved as `UnknownEnvelope` values. |
| Malformed payload handling | Scala extension | Unrecoverable payload issues surface as `AgentError.MessageParseError`. |
| Collection helpers | Scala extension | `CollectionPolicy`, `QueryCollector`, `QuerySummary`, `UsageSummary`, and `OutcomeOnly` provide bounded retention and semantic result helpers. |

## Message Compatibility

Known SDK message families are preserved as typed Scala ADTs where possible.

### Known behavior

- top-level unknown messages become `AgentMessage.Unknown`
- unknown `system` subtypes become `SystemEvent.Unknown`
- unknown content blocks become `ContentBlock.Unknown`
- unknown stream deltas become `StreamDelta.Unknown`
- image blocks are parsed into `ContentBlock.Image`
- raw JSON plus envelope metadata is preserved in `UnknownEnvelope`

### Envelope metadata preserved for unknowns

- raw JSON payload
- `type`
- `subtype` when present
- `uuid` when present
- `sessionId` when present
- `parentToolUseId` when present

## Lifecycle Compatibility

`scalagent` intentionally adds deterministic lifecycle semantics on top of the SDK’s generator/session model.

### Query lifecycle

- `QueryStream.messages` uses cleanup-aware generator bridging
- normal completion and early consumer termination run cleanup
- `interrupt` attempts SDK interruption and then cleanup
- `close()` is idempotent
- cleanup failures are logged and recorded, but do not mask the primary outcome

### Session lifecycle

- `ClaudeSession.send` uses cleanup-aware generator bridging per turn
- active turn state is tracked so interrupt/close can clean up once
- `ClaudeSession.close` is idempotent and safe with scoped finalizers
- `ask()` uses semantic result collection instead of transcript concatenation

## Skills Compatibility

Top-level preloaded skills are exposed as a Scala-first API while remaining removable once the upstream SDK adds native support.

### Preferred path

1. augment an existing main agent when `agent` already points at an in-memory `AgentDefinition`
2. otherwise synthesize an internal main-thread agent that carries the requested skills

### Fallback path

When a native agent-based path is not available, `scalagent` resolves `SKILL.md` files from configured `settingSources` and injects the resolved content into the effective system prompt.

## Drift Detection

The following checks currently guard compatibility-sensitive behavior:

- `./mill --no-server agent.compile`
- `./mill --no-server agent.test`
- `test/src/com/tjclp/scalagent/streaming/MessageConverterSpec.scala`
- `test/src/com/tjclp/scalagent/streaming/QueryStreamSpec.scala`
- `test/src/com/tjclp/scalagent/QueryCollectionSpec.scala`
- `test/src/com/tjclp/scalagent/config/AgentOptionsSpec.scala`

## Deferred Fields

The following SDK fields are not yet exposed in the Scala facades due to complex type requirements:

| Field | Reason |
|-------|--------|
| `onElicitation` | Requires `ElicitationRequest`/`ElicitationResult` facade types and async Promise bridging with AbortSignal |
| `spawnClaudeCodeProcess` | Requires `SpawnOptions`/`SpawnedProcess` facades with Node.js stream types (Readable, Writable) |

When bumping the SDK baseline, update this document alongside the relevant tests.
