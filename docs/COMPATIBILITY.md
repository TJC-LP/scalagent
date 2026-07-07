# Compatibility Matrix

Baseline: `@anthropic-ai/claude-agent-sdk` `^0.3.201`, `@openai/codex-sdk` `^0.142.5`

This document tracks the current compatibility posture of `scalagent` against the installed TypeScript SDK baseline.

## Surface Summary

| Surface | Status | Notes |
|--------|--------|-------|
| `Options` core fields | Exact / adapted mirror | Strongly-typed Scala wrappers over SDK options such as `model`, `cwd`, `systemPrompt`, `tools`, `allowedTools`, `disallowedTools`, `maxTurns`, `mcpServers`, `thinking`, `effort`, `promptSuggestions`, `sessionId`, debug controls, `executable`, `executableArgs`, `pathToClaudeCodeExecutable`, `permissionPromptToolName`, and `stderr`. |
| `AgentDefinition.observer` / `observerMessage` (SDK 0.3.201) | Exact mirror | Background observer agent auto-spawned per run; `AgentDefinition.withObserver(...)`. |
| `CanUseTool` `options.requestId` (SDK 0.3.201) | Adapted mirror | Exposed as `PermissionContext.requestId` for audit correlation. The nullable return (out-of-band control_response) is intentionally not wrapped — the facade always answers in-band. |
| `Query.reinitialize` (SDK 0.3.201) | Exact mirror | `QueryStream.reinitialize` re-sends the initialize control request after a transport gap. |
| `Query.setMcpPermissionModeOverride` (SDK 0.3.201) | Exact mirror | `QueryStream.setMcpPermissionModeOverride(serverName, Option[McpPermissionModeOverride])`; returns the optional warning. |
| `Query.setMaxThinkingTokens` `thinkingDisplay` (SDK 0.3.201) | Adapted mirror | Omitted/null/value tri-state modeled as `ThinkingDisplayUpdate.{Keep, Clear, Set}`. |
| `BaseHookInput.prompt_id` (SDK 0.3.201) | Exact mirror | `HookInput.promptId` on every hook input; joins hook output to OTel events at prompt grain. |
| `sandbox.credentials` + `allowAppleEvents` (SDK 0.3.201) | Exact mirror | `SandboxCredentialsConfig` (file deny rules, env var deny/mask rules with `injectHosts`, `allowPlaintextInject`) and `SandboxSettings.withAllowAppleEvents`. |
| `system/informational` (SDK 0.3.201) | Exact mirror | `SystemEvent.Informational(content, level, toolUseId, preventContinuation)` with `InformationalLevel` enum. |
| `system/model_refusal_fallback` + `model_refusal_no_fallback` (SDK 0.3.201) | Exact mirror | `SystemEvent.ModelRefusalFallback` / `ModelRefusalNoFallback` with refusal category/explanation and retraction uuids. |
| `system/worker_shutting_down` (SDK 0.3.201) | Exact mirror | `SystemEvent.WorkerShuttingDown(reason)`; live-tail signal only. |
| `ModelInfo.resolvedModel` (SDK 0.3.201) | Exact mirror | Canonical wire model id an alias row resolves to. |
| `listSessions.includeProgrammatic` (SDK 0.3.201) | Exact mirror | `Claude.listSessions(..., includeProgrammatic = false)` filters SDK/daemon sessions. |
| `McpServerToolPolicy.org_max_permission` (SDK 0.3.201) | Exact mirror | `McpOrgMaxPermission` enum (`allow` / `ask` / `blocked`); SDK-side `permission_policy` optionality not mirrored (facade is write-side). |
| `SDKConversationResetMessage` / `SDKControlRequestProgressMessage` (SDK 0.3.201) | Structural fallback | Referenced in the SDKMessage union but not declared in the published d.ts; preserved as `AgentMessage.Unknown`. |
| `AgentDefinition.maxTurns` | Exact mirror | Per-agent turn limit exposed via builder and JSON codecs. |
| `agentProgressSummaries` | Exact mirror | Enables periodic AI-generated progress summaries for subagents. |
| `TaskProgress.summary` | Exact mirror | AI-generated progress summary text on task_progress events. |
| `ModelInfo.supportsAutoMode` | Exact mirror | Whether a model supports auto mode. |
| `gcpAuthRefresh` | Exact mirror | GCP authentication refresh command. |
| `Options.title` (SDK 0.2.113) | Exact mirror | Top-level session title option; `AgentOptions.withTitle(...)`. |
| `SDKUserMessage.shouldQuery` (SDK 0.2.110) | Exact mirror | `QueryStream.streamUserMessage(..., shouldQuery = Some(false))` appends without triggering a turn. |
| `SDKStatus.requesting` (SDK 0.2.108) | Exact mirror | New `SdkStatus.Requesting` variant on status system events. |
| `system/memory_recall` (SDK 0.2.105) | Exact mirror | `SystemEvent.MemoryRecall(mode, memories)` with `MemoryRecallMode` and `MemoryScope` enums. |
| `system/mirror_error` (SDK 0.2.113) | Exact mirror | `AgentMessage.MirrorError(error, projectKey, mirroredSessionId, subpath, ...)`. |
| `McpServerToolPolicy` on remote configs (SDK 0.2.111) | Exact mirror | `McpServerConfig.{HTTP,SSE}.tools` carries per-tool `McpToolPolicy`. |
| `EffortLevel.xhigh` / `EffortLevel.max` | Exact mirror | `Effort.XHigh` is documented for Opus 4.8 and Opus 4.7. `Effort.Max` is documented for Opus 4.8, Opus 4.7, Opus 4.6, and Sonnet 4.6. `AgentOptions.withModelAndEffort(...)` compile-time validates built-in model/effort pairs; the dynamic `withEffort(...)` path remains available for runtime and custom values. |
| `Model.Opus4_8` / `Model.Opus4_7` | Exact mirror | `Model.Opus4_8` and `Model.Opus4_7` are available. `Model.opus` intentionally changed from `Model.Opus4_7` to `Model.Opus4_8`; callers that need the previous target should use `Model.Opus4_7` explicitly. |
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

## Breaking Changes in the SDK Baseline

The `options.env` semantic changed between SDK 0.2.111 and 0.2.113. As of the current baseline,
**passing a non-empty `env` map replaces `process.env` for the Claude Code subprocess** (it does
not overlay). Callers that need to retain inherited variables must spread them explicitly, e.g.
`sys.env ++ Map("MY_VAR" -> "x")`. The same semantic applies to `CodexClientOptions.env` in the
Codex SDK.

## Deferred Fields

The following SDK fields are not yet exposed in the Scala facades:

| Field | Reason |
|-------|--------|
| `onElicitation` | Requires `ElicitationRequest`/`ElicitationResult` facade types and async Promise bridging with AbortSignal |
| `spawnClaudeCodeProcess` | Requires `SpawnOptions`/`SpawnedProcess` facades with Node.js stream types (Readable, Writable) |
| `sessionStore` / `SessionStore` / `InMemorySessionStore` / `importSessionToStore()` / `deleteSession()` (SDK 0.2.113) | Requires a ZIO-friendly `SessionStore[F]` abstraction and reference in-memory port — tracked as a follow-up PR, not a version-bump item. |
| OpenTelemetry trace context propagation (SDK 0.2.113) | Requires a zio-telemetry vs raw `@opentelemetry/api` integration decision — follow-up PR. |
| `startup()` + `WarmQuery` (SDK 0.2.111) | Additive pre-warm; existing lazy `ClaudeAgent` construction already covers the need. |

When bumping the SDK baseline, update this document alongside the relevant tests.
