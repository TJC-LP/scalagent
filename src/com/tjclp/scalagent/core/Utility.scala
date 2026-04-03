package com.tjclp.scalagent.core

/** Observer-dependent utility scoring for agent runs.
  *
  * From the formalization: `U_ω(α) = E[P(accept | α(x))]` — utility
  * is relative to the observer (principal). Different principals evaluate
  * the same run differently.
  *
  * @tparam P principal type
  * @tparam O output type
  */
trait Utility[-P, -O]:
  /** Score a run from the perspective of the given principal. */
  def score(principal: P, output: O, trace: TraceSummary): Double

object Utility:

  /** Create a Utility from a function. */
  def from[P, O](f: (P, O, TraceSummary) => Double): Utility[P, O] =
    (principal: P, output: O, trace: TraceSummary) => f(principal, output, trace)

  /** Minimize cost. Score = 1 / (1 + costUsd). */
  def costMinimizing[P, O]: Utility[P, O] =
    (_, _, trace) => 1.0 / (1.0 + trace.costUsd)

  /** Maximize reliability. Score = 1.0 if success, 0.0 if failure. */
  def reliability[P, O]: Utility[P, O] =
    (_, _, trace) => if trace.isSuccess then 1.0 else 0.0

  /** Minimize latency. Score = 1 / (1 + durationMs / 1000). */
  def latencyMinimizing[P, O]: Utility[P, O] =
    (_, _, trace) => 1.0 / (1.0 + trace.durationMs.toDouble / 1000.0)

  /** Minimize complexity. Score = 1 / (1 + totalEvents). */
  def simplicityBiased[P, O]: Utility[P, O] =
    (_, _, trace) => 1.0 / (1.0 + trace.totalEvents)

  /** Weighted composite of multiple utilities. */
  def weighted[P, O](components: (Utility[P, O], Double)*): Utility[P, O] =
    val totalWeight = components.map(_._2).sum
    require(totalWeight > 0, "Total weight must be positive")
    (principal: P, output: O, trace: TraceSummary) =>
      components.map { (u, w) => u.score(principal, output, trace) * w }.sum / totalWeight
