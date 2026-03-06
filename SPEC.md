# SPEC: scalagent Compatibility Overhaul and Runtime Hardening

Date: 2026-03-06
Status: Draft for implementation
Primary Repo: `~/git/scalagent`
Primary Consumer Context: large, bounded-parallel deck and artifact workflows in `~/git/anthropic-agent-sdk-workshop`
Underlying Runtime Baseline: `@anthropic-ai/claude-agent-sdk` `^0.2.69`

## 1. Executive Summary

`scalagent` is already useful as a Scala.js wrapper around the Claude Agent SDK, but it is still prototype-shaped in the places that matter most for long-running, high-volume, automation-heavy workloads.

The motivating workload from the original cross-repo spec remains the same: reliable orchestration of large, bounded-parallel slide and deck generation runs. In that environment, the wrapper must survive upstream SDK drift, clean up generator-backed streams deterministically, avoid unbounded transcript retention, and present APIs that stay aligned with the underlying TypeScript library instead of drifting into a parallel abstraction universe.

This spec adapts the original broader draft into a `scalagent`-specific overhaul plan. The repo goal is:

1. Maximize compatibility with the upstream TypeScript SDK and its runtime behavior.
2. Make message parsing forward-compatible and non-throwing under SDK evolution.
3. Guarantee cleanup and idempotent lifecycle behavior for query and session streams.
4. Replace transcript-heavy convenience paths with explicit, bounded collection policies.
5. Make skill preloading for the main thread ergonomic while remaining compatible with current and future SDK behavior.
6. Turn compatibility with the underlying TS library into something tested, documented, and CI-enforced.

## 2. Context and Scope

This repo is the primary implementation venue for the runtime-safety workstream from the original workshop spec. The workshop repo remains a consumer and stressor, not the source of truth for runtime semantics.

The most important context from the original draft is unchanged:

- workloads can be long-lived and parallel
- transcript growth matters
- interruption and cancellation must leave state clean
- unknown upstream SDK shapes must degrade gracefully
- CI must be able to trust the wrapper's behavior

What changes here is emphasis: this repo overhaul is explicitly compatibility-first. `scalagent` should mirror the TS SDK where possible, preserve raw upstream data when it cannot fully model it yet, and avoid inventing Scala-only semantics unless clearly necessary and explicitly documented.

## 3. Compatibility-First Design Principles

### 3.1 Source of truth

The upstream TypeScript SDK runtime and public typings are the source of truth for shape, naming, and lifecycle semantics.

### 3.2 Additive Scala adaptation

Scala APIs may add ergonomics, but they should not erase important upstream distinctions. If the SDK has a discriminated union, the Scala side should preserve it rather than flattening it into text placeholders.

### 3.3 Preserve unknowns

When the SDK evolves faster than `scalagent`, unknown variants must be preserved as structured unknown values with raw payloads and envelope metadata. Unknowns are not exceptional control flow.

### 3.4 Prefer pass-through over reinvention

If the underlying SDK already supports a capability, `scalagent` should expose or reuse that capability rather than implementing a parallel Scala-only mechanism.

### 3.5 Workarounds must be removable

If `scalagent` must shim a missing top-level TS capability, the shim must be:

- clearly isolated
- feature-detectable or version-gated where practical
- easy to remove once the TS SDK supports the capability natively

### 3.6 CI must detect drift

Compatibility with the TS SDK cannot remain a matter of memory or manual spot checks. The repo needs explicit parity checks, fixture coverage, and documentation sync.

## 4. Problem Statement

Today, `scalagent` has four structural weaknesses:

1. The message converter assumes known SDK shapes and throws when they drift.
2. Cleanup-aware async-generator adapters exist but are not wired into the main query/session paths.
3. Convenience APIs default to collecting full transcripts and, in some cases, derive text from the transcript rather than the final result payload.
4. Public API/docs/test coverage drift from both the local implementation and the underlying TS SDK.

For automation-heavy consumers, these are not edge cases. They become failure modes.

## 5. Goals

1. Parser never crashes solely because the SDK introduced an unknown message/system/content/delta variant.
2. Query and session streaming always run cleanup logic on completion, interruption, or early consumer termination.
3. `ask`/`queryComplete`-style APIs support bounded collection and return semantically correct outputs.
4. Main-thread skill preloading becomes ergonomic without locking the repo into an unremovable workaround.
5. The Scala wrapper's public surface is explicitly reconciled against the TS SDK.
6. Tests for runtime safety and compatibility are JS-runnable and CI-credible.
7. Docs, package metadata, and examples reflect the actual implementation and current SDK baseline.

## 6. Non-Goals

1. Replacing the underlying TS SDK with a native Scala implementation.
2. Eliminating all Scala-specific ergonomics.
3. Solving workshop-level deck contracts inside this repo.
4. Guaranteeing byte-for-byte output determinism from model-driven runs.
5. Reworking unrelated A2A or macro subsystems unless they are part of the compatibility pass.

## 7. SDK Baseline and Local Compatibility Facts

The installed SDK and local repo audit establish the following baseline facts:

1. `Query` is an `AsyncGenerator<SDKMessage, void>` and also exposes `interrupt()` and `close()`.
2. `SDKSession.stream()` returns an `AsyncGenerator<SDKMessage, void>` and `SDKSession` also exposes `close()` and async disposal.
3. SDK `AgentDefinition` supports `skills?: string[]`.
4. SDK top-level `Options` does **not** expose `skills?: string[]` in the installed typings.
5. SDK top-level `Options` **does** expose `agent?: string` and `agents?: Record<string, AgentDefinition>`, meaning a main-thread agent definition may be usable as a compatibility path for main-thread skill preloading.
6. SDK `allowedTools` means auto-allow without prompting, not the base set of available tools; tool availability is controlled by `tools`.

These facts materially affect the implementation plan below.

## 8. Current-State Findings

### 8.1 P0 findings

1. `MessageConverter.fromRaw()` throws on unknown top-level message types.
2. Unknown system event subtypes also throw.
3. Unknown content blocks and deltas are flattened into text placeholders instead of preserved structurally.
4. `ContentBlock.Image` exists in the ADT but is not actually parsed from raw SDK payloads.
5. Query/session streaming paths do not use the cleanup-aware async-generator helpers that already exist in the repo.

### 8.2 P1 findings

1. Many parser branches still assume required fields via unsafe `asInstanceOf[String]`/`asInstanceOf[Int]` extraction, so malformed or evolved payloads can still crash parsing.
2. `Claude.ask()` and `ClaudeSession.ask()` derive text by concatenating all `.text` extracted from all messages, rather than using the final result payload.
3. Because `AgentMessage.text` includes user text, stream deltas, and prompt suggestions, transcript-derived `ask()` results can be semantically wrong or duplicated when partials or post-result suggestions are present.
4. `ClaudeAgent.queryComplete()` and `collectResult` duplicate transcript-heavy collection logic.
5. `Claude.session()` installs a finalizer that may call `close()` again even if the user already closed the underlying open session value earlier in scope.
6. `QueryStream.close()` and `ClaudeSession.close()` are not explicitly modeled as idempotent.
7. Cleanup errors are currently ignored, not classified or surfaced.
8. `QueryStreamSpec` only covers `streamUserMessage` shape and does not validate lifecycle behavior.
9. `ClaudeAgentSpec` is ignored entirely in Scala.js and therefore does not protect the real runtime path.

### 8.3 P2 findings

1. README metadata is out of sync with the codebase:
   - README references `0.2.4`
   - `package.json` is `0.2.5-SNAPSHOT`
   - `build.mill` publishes `0.3.1-SNAPSHOT` by default
   - README says Scala `3.3.x` while the build is on Scala `3.7.4`
2. README message-shape documentation is stale and omits several current cases.
3. `AgentDefinition` in Scala currently exposes fields that are not present in the installed SDK typings, so the wrapper already contains compatibility drift that needs explicit classification.
4. `AgentOptions` does not currently include a top-level `skills` field even though that is the ergonomic shape consumers want.
5. The repo lacks a formal parity matrix describing which TS SDK fields are mirrored, adapted, missing, or Scala-only.

## 9. Target State

## 9.1 Message contract

`scalagent` should preserve a typed Scala view of SDK messages while remaining forward-compatible.

Required properties:

- no parser crashes for unknown discriminants
- raw payload JSON preserved for unknown variants
- envelope metadata preserved whenever available
- explicit support for image blocks and other currently known shapes
- malformed payloads classified separately from transport errors

## 9.2 Lifecycle contract

Streaming APIs should obey deterministic cleanup semantics.

Required properties:

- normal completion runs cleanup
- early consumer termination runs cleanup
- explicit `interrupt()` attempts SDK interruption and then cleanup
- explicit `close()` is idempotent and cleanup runs once
- cleanup errors never mask the primary result

## 9.3 Result collection contract

Collection should become policy-driven rather than always transcript-heavy.

Required properties:

- callers can request full transcript, bounded transcript, summary-only, or outcome-only collection
- `ask()` returns the semantic final answer, not concatenated transcript text
- callers can sink messages externally without retaining everything in memory
- post-result metadata such as prompt suggestions is handled intentionally rather than accidentally

## 9.4 Skills contract

Main-thread skill preloading should be ergonomic and compatible-first.

Required properties:

- top-level Scala API supports `skills`
- implementation prefers native SDK-compatible paths before prompt injection
- current subagent `AgentDefinition.skills` behavior remains intact
- user/project skill resolution is explicit and documented
- no extra turns spent on runtime `Skill` tool calls for preloaded skills

## 9.5 Compatibility contract

The repo should maintain an explicit compatibility inventory against the installed TS SDK.

Required properties:

- known parity gaps are enumerated
- Scala-only extensions are clearly marked
- upstream drift is detectable in CI
- docs explain where semantics intentionally differ and why

## 10. Detailed Workstreams

## 10.1 Workstream A: Forward-Compatible Message Modeling

### A1. Add unknown fallback variants

Required additions:

- `AgentMessage.Unknown(...)`
- `SystemEvent.Unknown(...)`
- `ContentBlock.Unknown(...)`
- `StreamDelta.Unknown(...)`

Each unknown variant should preserve:

- raw payload as `zio.json.ast.Json`
- `type`
- `subtype` when present
- `uuid` when present
- `sessionId` when present
- `parentToolUseId` when present

### A2. Stop flattening unknowns into text

Current text placeholders like `"[Unknown content block: ...]"` and `"[Unknown delta: ...]"` should be removed from the parser path. Unknowns should remain unknowns.

### A3. Parse currently-known omitted shapes

At minimum:

- image content blocks should map to the existing `ContentBlock.Image`
- image source variants should distinguish URL vs base64 source where the SDK payload makes that possible

### A4. Introduce safe extraction helpers

`MessageConverter` should stop assuming all fields are present and correctly typed.

Required design shift:

- central helpers for optional string/int/boolean/object extraction
- envelope parsing first, subtype-specific parsing second
- only truly unrecoverable primitive absence should fail hard
- malformed but survivable shapes should produce unknown variants plus warnings

### A5. Preserve raw envelope structure

For known variants too, consider preserving a lightweight raw envelope or raw payload for debugging/replay, at least behind a policy or debug mode.

### A6. Keep legacy shapes working

Existing legacy aliases already handled in the converter should remain supported while the converter is hardened.

## 10.2 Workstream B: Query and Session Lifecycle Hardening

### B1. Wire cleanup-aware generators into live paths

`AsyncIteratorOps.toZStreamWithReturn()` or an improved equivalent should be used in:

- `QueryStream.messages`
- `ClaudeSession.send`

### B2. Make `QueryStream` stateful and idempotent

`QueryStream` should manage its lifecycle explicitly rather than exposing raw calls directly.

Required behavior:

- cleanup action executes at most once
- `interrupt()` attempts SDK interrupt and then terminates the generator cleanly
- `close()` is idempotent
- `messages` stream termination triggers cleanup automatically
- cleanup may choose between generator `return()` and forceful `close()` based on cause

### B3. Track active session stream state

`ClaudeSessionLive` should keep track of the active turn stream generator so that:

- early consumer termination cleans up the turn stream
- `interrupt()` can clean up the currently active stream even if the SDK lacks a typed session interrupt API
- `close()` can terminate both the session and any active stream once

### B4. Fix scoped finalizer semantics

Because `Claude.session()` installs a finalizer over the open session object, the underlying session implementation must tolerate a later finalizer close call after manual close.

### B5. Cleanup errors must be counted and logged

Cleanup failures should not be silent `ignore`s. They should be:

- logged
- classified
- counted in summary surfaces where appropriate
- prevented from overwriting the primary query/session outcome

## 10.3 Workstream C: Result Collection and Transcript Policy Redesign

### C1. Introduce explicit collection policy

Add a collection policy type, e.g.:

- `Full`
- `NoStreamingDeltas`
- `UntilResult`
- `ResultOnly`
- `SummaryOnly`
- `Disabled`

The exact names may vary, but the contract must make retention intentional.

### C2. Centralize collection logic

Current collection logic is duplicated across:

- `ClaudeAgent.queryComplete`
- `Claude.queryComplete`
- `package.scala` `collectResult`
- `Claude.ask`
- `ClaudeSession.ask`

This should be replaced by a shared collector.

### C3. Fix semantic correctness of `ask`

`ask()` should return the final semantic answer, not transcript concatenation.

Required behavior:

- prefer `ResultOutcome.Success.result`
- fall back only when no formal result exists
- do not accidentally append prompt suggestions, user echoes, or streaming deltas

### C4. Add lightweight result surfaces

Suggested additions:

- `QuerySummary`
- `OutcomeOnly`
- `UsageSummary`
- `CollectedWarnings`

These should allow automation-heavy callers to avoid retaining whole transcripts.

### C5. Support external transcript sinks

Callers should be able to:

- disable in-memory retention
- stream to a callback or sink
- retain only bounded recent messages in memory

## 10.4 Workstream D: TS SDK Parity and Compatibility Matrix

### D1. Produce an explicit parity inventory

For the installed SDK baseline, classify `scalagent` fields and surfaces as:

- exact mirror
- adapted mirror
- missing upstream field exposure
- Scala-only extension
- provisional / requires runtime verification

At minimum this inventory should cover:

- `Options`
- `AgentDefinition`
- `Query`
- `SDKSession`
- message shapes emitted by `SDKMessage`

### D2. Reconcile extra Scala-only agent fields

`scalagent` currently exposes `AgentDefinition` fields that are not present in the installed local SDK typings.

Required decision for each extra field:

- confirm it is runtime-supported and keep it
- mark it provisional and document it clearly
- move it behind an advanced/unstable surface
- remove it if it is no longer compatible

### D3. Audit missing top-level TS options

The current Scala `AgentOptions` does not obviously mirror every TS `Options` field.

Required action:

- identify missing high-value fields
- decide whether to add strongly-typed wrappers or documented pass-through support
- avoid silent incompatibility drift

### D4. Add drift detection

Introduce a repo-level compatibility check such as:

- a checked-in compatibility matrix document
- fixture-based validation against selected `sdk.d.ts` shapes
- a CI task that fails when expected SDK fields/unions materially change

## 10.5 Workstream E: Main-Thread Skill Preloading

### E1. Add `skills` to `AgentOptions`

Add a first-class top-level Scala API such as:

- `skills: List[SkillName]` on `AgentOptions`
- `withSkills(skillNames: SkillName*)`
- possibly a distinct helper for preloading vs runtime `Skill` tool usage

### E2. Preferred compatibility path: synthesize or augment a main agent

Because the installed SDK supports:

- `AgentDefinition.skills`
- `Options.agent`
- `Options.agents`

The first implementation path to evaluate is:

- synthesize a hidden main-thread `AgentDefinition` with the requested skills
- or augment an existing main-thread agent when safe
- set `agent` to that definition for the main conversation

This path is more TS-native than prompt injection and should be preferred if it preserves required behavior.

### E3. Fallback path: filesystem skill resolution + prompt injection

If the synthetic-main-agent path is insufficient, add a fallback shim that:

- resolves skills from `settingSources`
- reads `SKILL.md` content from supported user/project directories
- appends or prepends resolved skill content to the effective system prompt
- skips runtime `Skill` tool calls for those preloaded skills

### E4. Preserve existing runtime skill-loading path

The current `withSkillsEnabled` helper should remain available for runtime skill discovery/loading, but its documentation should make clear that it is not the same thing as top-level preloaded skills.

### E5. Avoid double-loading

If and when the upstream SDK adds top-level `Options.skills`, `scalagent` should:

- pass the field through natively
- disable the fallback shim for that case
- preserve identical public Scala ergonomics

## 10.6 Workstream F: Test Overhaul

### F1. Message converter fixtures

Add JS-runnable coverage for:

- unknown top-level message type
- unknown system subtype
- unknown content block subtype
- unknown delta subtype
- image content block
- malformed but survivable payloads
- missing required primitives that should become terminal parser failures

### F2. Query lifecycle tests

Add tests for:

- normal completion cleanup
- early consumer termination cleanup
- interrupt during active stream
- close-after-completion
- double-close
- double-interrupt
- cleanup error handling

### F3. Session lifecycle tests

Add tests for:

- per-turn stream cleanup
- manual close followed by scoped finalizer close
- send interruption behavior
- resume/close consistency

### F4. Replace ignored tests as primary safety net

Ignored specs may remain as documentation if desired, but runtime safety should be validated by runnable Scala.js tests.

### F5. Compatibility tests

Add tests or checks that assert the wrapper still matches the important TS SDK facts captured in this spec.

## 10.7 Workstream G: Docs, Metadata, and Examples Sync

### G1. Version and runtime sync

Update docs so version/runtime claims match the build and publication story.

### G2. Public API docs sync

Refresh README and examples for:

- actual message cases
- lifecycle semantics
- prompt suggestion behavior
- skill loading vs skill preloading
- transcript collection policies

### G3. Compatibility policy docs

Document:

- how `scalagent` tracks upstream SDK drift
- which surfaces are stable vs provisional
- how unknown variants are represented

## 11. Milestones

## Milestone M1: Runtime Safety Baseline

### Deliverables

1. Unknown fallback variants across message/system/content/delta models.
2. Non-throwing `MessageConverter` for unknown discriminants.
3. Cleanup-aware query/session stream handling.
4. JS-runnable parser and lifecycle tests.
5. README/build metadata sync for current versions.

### Exit criteria

1. Unknown SDK events no longer crash ordinary runs.
2. Query/session cleanup runs on completion and interruption.
3. Cleanup omission rate is effectively zero in test coverage.
4. Parser drift coverage exists for top-level/system/content/delta unknowns.

## Milestone M2: Result Policy + Skills Compatibility

### Deliverables

1. Transcript capture policy and shared collector.
2. Correct `ask()` semantics based on final result, not transcript text.
3. Lightweight summary/result surfaces.
4. Top-level `AgentOptions.skills` API.
5. Preferred main-thread skills compatibility path, plus fallback if needed.

### Exit criteria

1. Large-workload callers can avoid full transcript retention.
2. `ask()` is no longer polluted by user echoes, deltas, or prompt suggestions.
3. Main-thread skill preloading does not require runtime `Skill` tool turns.
4. Existing subagent skill behavior remains intact.

## Milestone M3: Compatibility Matrix + CI Gates

### Deliverables

1. Checked-in TS parity matrix.
2. CI drift detection for important SDK shapes.
3. Docs describing stable/provisional compatibility boundaries.
4. Example updates that demonstrate the new lifecycle and skill-loading behavior.

### Exit criteria

1. CI catches material SDK compatibility drift.
2. Public docs match the actual implementation.
3. Consumers have a documented migration path for old transcript-heavy APIs.

## 12. Acceptance Criteria and Success Metrics

### Runtime safety

- unknown SDK event crash rate: `0`
- cleanup omission rate in covered interruption scenarios: `0`
- double-close causing user-visible failure: `0`

### Compatibility

- major TS SDK parity gaps are explicitly inventoried
- undocumented Scala-only fields in public API: `0`
- unknown variants preserve raw payload and envelope metadata

### Collection correctness

- `ask()` returns final semantic answer, not transcript concatenation
- bounded/no-transcript collection modes exist for large workloads
- prompt suggestions no longer accidentally contaminate answer text

### Test credibility

- ignored/documentation-only specs are not the primary safety net
- parser and lifecycle regressions are CI-detectable in Scala.js

## 13. Likely Code Touchpoints

### Core runtime

- `src/com/tjclp/scalagent/streaming/MessageConverter.scala`
- `src/com/tjclp/scalagent/messages/AgentMessage.scala`
- `src/com/tjclp/scalagent/messages/SystemEvent.scala`
- `src/com/tjclp/scalagent/messages/ContentBlock.scala`
- `src/com/tjclp/scalagent/streaming/AsyncIteratorOps.scala`
- `src/com/tjclp/scalagent/streaming/QueryStream.scala`
- `src/com/tjclp/scalagent/ClaudeAgent.scala`
- `src/com/tjclp/scalagent/Claude.scala`
- `src/com/tjclp/scalagent/session/ClaudeSession.scala`

### Configuration and compatibility

- `src/com/tjclp/scalagent/config/AgentOptions.scala`
- `src/com/tjclp/scalagent/config/AgentDefinition.scala`
- `src/com/tjclp/scalagent/config/SkillName.scala`
- new compatibility helper modules if needed
- new skill resolution / preload helper modules if needed

### Tests

- `test/src/com/tjclp/scalagent/streaming/MessageConverterSpec.scala`
- `test/src/com/tjclp/scalagent/streaming/QueryStreamSpec.scala`
- new session lifecycle specs
- new compatibility/parity specs
- `test/src/com/tjclp/scalagent/config/AgentOptionsSpec.scala`

### Docs

- `README.md`
- examples touching query/session/skills behavior
- new compatibility documentation if added

## 14. Risks and Mitigations

1. **Upstream SDK evolves faster than the wrapper.**
   - Mitigation: unknown fallbacks, raw payload preservation, parity fixtures, CI drift checks.

2. **Lifecycle cleanup changes introduce regressions.**
   - Mitigation: fake-generator tests covering completion, interruption, early termination, and double-close cases.

3. **Result-policy redesign breaks existing callers.**
   - Mitigation: additive APIs first, keep compatibility path, document migration.

4. **Main-thread skills workaround becomes obsolete or conflicts with native SDK support later.**
   - Mitigation: prefer TS-native `agent`/`agents` path first, isolate fallback shim, feature-detect or version-gate when possible.

5. **Wrapper diverges further from TS typings while adding compatibility features.**
   - Mitigation: explicit parity matrix and compatibility review as part of each release.

## 15. Open Questions

1. Is a synthesized main-thread `AgentDefinition` sufficient to satisfy the top-level skills requirement in all important cases, or only some?
2. Which current Scala-only `AgentDefinition` fields are truly runtime-supported by the installed SDK, versus historical or wrapper-only carryovers?
3. How much of the TS `Options` surface should be strongly typed versus exposed through documented advanced/pass-through hooks?
4. Should unknown variants be retained in all collection policies, or only in full/debug policies?
5. What is the preferred public migration story for existing `ask()` callers once transcript-derived behavior is removed?

## 16. Immediate Coding Batches

### Batch 1: Parser hardening

1. Add unknown variants to message/system/content/delta ADTs.
2. Refactor `MessageConverter` around safe envelope extraction.
3. Parse image blocks.
4. Add unknown/malformed fixture coverage.

### Batch 2: Lifecycle hardening

1. Wire cleanup-aware async-generator handling into query/session streams.
2. Make `close()`/`interrupt()` idempotent and stateful.
3. Add query/session cleanup tests.
4. Fix scoped finalizer double-close behavior.

### Batch 3: Result policy and skills

1. Add shared collector and transcript capture policy.
2. Fix `ask()` to use final result semantics.
3. Add top-level `AgentOptions.skills`.
4. Evaluate synthetic main-agent skill preloading path before implementing prompt injection fallback.

### Batch 4: Compatibility and docs

1. Create the TS parity inventory.
2. Reconcile Scala-only agent/option fields.
3. Update README/build/version/runtime docs.
4. Add CI checks for runtime and compatibility drift.

