package com.tjclp.scalagent.core

/** Rich trace data from an agent run, foldable from an `AgentEvent` stream.
  *
  * Captures what happened during a run without provider-specific details.
  * Used by `Utility` for scoring and `Complexity` for graph metrics.
  */
final case class TraceSummary(
    durationMs: Long,
    numTurns: Int,
    numToolCalls: Int,
    numToolResults: Int,
    numDelegations: Int,
    toolNames: Set[String],
    delegationIds: Set[String],
    costUsd: Double,
    isSuccess: Boolean,
    resultText: Option[String],
    stopReason: Option[String],
    totalEvents: Int,
    nativeEventCount: Int
)

object TraceSummary:

  /** Fold a list of AgentEvents into a TraceSummary. */
  def fromEvents(events: List[AgentEvent]): TraceSummary =
    var numToolCalls = 0
    var numToolResults = 0
    var numDelegations = 0
    var toolNames = Set.empty[String]
    var delegationIds = Set.empty[String]
    var nativeCount = 0
    var completed: Option[RunSummary] = None

    events.foreach {
      case AgentEvent.ToolCall(name, _) =>
        numToolCalls += 1
        toolNames += name
      case AgentEvent.ToolResult(name, _, _) =>
        numToolResults += 1
        if name.nonEmpty then toolNames += name
      case AgentEvent.DelegationStarted(_, childId) =>
        numDelegations += 1
        delegationIds += childId
      case AgentEvent.DelegationFinished(childId, _) =>
        delegationIds += childId
      case AgentEvent.Completed(summary) =>
        completed = Some(summary)
      case _: AgentEvent.Native =>
        nativeCount += 1
      case _ => ()
    }

    val summary = completed.getOrElse(RunSummary(0, 0, 0.0, false))

    TraceSummary(
      durationMs = summary.durationMs,
      numTurns = summary.numTurns,
      numToolCalls = numToolCalls,
      numToolResults = numToolResults,
      numDelegations = numDelegations,
      toolNames = toolNames,
      delegationIds = delegationIds,
      costUsd = summary.costUsd,
      isSuccess = summary.isSuccess,
      resultText = summary.resultText,
      stopReason = summary.stopReason,
      totalEvents = events.size,
      nativeEventCount = nativeCount
    )

  /** Create from RunSummary alone (less detail). */
  def fromRunSummary(summary: RunSummary): TraceSummary =
    TraceSummary(
      durationMs = summary.durationMs,
      numTurns = summary.numTurns,
      numToolCalls = 0,
      numToolResults = 0,
      numDelegations = 0,
      toolNames = Set.empty,
      delegationIds = Set.empty,
      costUsd = summary.costUsd,
      isSuccess = summary.isSuccess,
      resultText = summary.resultText,
      stopReason = summary.stopReason,
      totalEvents = 0,
      nativeEventCount = 0
    )
