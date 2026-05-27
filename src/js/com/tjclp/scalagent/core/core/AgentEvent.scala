package com.tjclp.scalagent.core

import zio.json.ast.Json

/** Metadata identifying text or status events emitted by a Claude subagent. */
final case class SubagentContext(
  subagentType: String,
  taskDescription: Option[String] = None)

/**
 * Normalized event ADT emitted during an agent run.
 *
 * Provider-independent. Provider-specific events pass through
 * as `Native` with a tag and JSON payload, preserving lossless access.
 */
enum AgentEvent:
  /** Incremental or complete text output from the agent. */
  case TextDelta(
    value: String,
    subagentContext: Option[SubagentContext] = None)

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
  case Status(
    value: String,
    subagentContext: Option[SubagentContext] = None)

  /** Terminal event: the run completed. */
  case Completed(summary: RunSummary)

  /** Provider-specific event that does not normalize. Lossless escape hatch. */
  case Native(tag: String, payload: Json)
end AgentEvent
