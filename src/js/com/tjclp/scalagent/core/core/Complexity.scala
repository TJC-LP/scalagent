package com.tjclp.scalagent.core

/**
 * Execution graph complexity metrics for an agent run.
 *
 * From the formalization: `C(α) = E[|G(x)| | x]` — expected execution
 * graph size conditional on input. For a realized run, this measures
 * the actual graph size as a foundation for the expected-value metric.
 */
final case class Complexity(
  totalNodes: Int,
  toolCallNodes: Int,
  delegationNodes: Int,
  maxDelegationDepth: Int,
  graphDensity: Double)

object Complexity:

  /** Derive complexity metrics from a TraceSummary. */
  def fromTrace(trace: TraceSummary): Complexity =
    Complexity(
      totalNodes = trace.totalEvents,
      toolCallNodes = trace.numToolCalls,
      delegationNodes = trace.numDelegations,
      maxDelegationDepth =
        if trace.numDelegations > 0 then 1 else 0, // single-level observed; multi-level requires recursive trace
      graphDensity =
        if trace.totalEvents > 0 then trace.numDelegations.toDouble / trace.totalEvents
        else 0.0,
    )

  val zero: Complexity = Complexity(0, 0, 0, 0, 0.0)
