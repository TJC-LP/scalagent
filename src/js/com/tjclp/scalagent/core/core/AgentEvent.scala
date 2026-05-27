package com.tjclp.scalagent.core

import zio.json.ast.Json

/**
 * Normalized event ADT emitted during an agent run.
 *
 * Provider-independent. Provider-specific events pass through
 * as `Native` with a tag and JSON payload, preserving lossless access.
 */
enum AgentEvent:
  /** Incremental or complete text output from the agent. */
  case TextDelta(value: String)

  /** Agent requested a tool invocation. */
  case ToolCall(name: String, args: Json)

  /** Result of a tool invocation. */
  case ToolResult(
    name: String,
    value: Json,
    isError: Boolean)

  /** A subagent or delegation has started. */
  case DelegationStarted(label: String, childId: String)

  /** A subagent or delegation has finished. */
  case DelegationFinished(childId: String, status: String)

  /** Status update (tool progress, task progress, session state). */
  case Status(value: String)

  /** Terminal event: the run completed. */
  case Completed(summary: RunSummary)

  /** Provider-specific event that does not normalize. Lossless escape hatch. */
  case Native(tag: String, payload: Json)
end AgentEvent
