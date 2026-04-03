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

  /** Explain the score with named component contributions. */
  def breakdown(principal: P, output: O, trace: TraceSummary): ScoreBreakdown =
    val totalScore = score(principal, output, trace)
    ScoreBreakdown(
      total = totalScore,
      components = List(
        ScoreComponent(
          name = "total",
          raw = totalScore,
          weight = 1.0,
          contribution = totalScore
        )
      )
    )

object Utility:

  final case class NamedComponent[P, O](
      name: String,
      utility: Utility[P, O],
      weight: Double
  )

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

  /** Penalize failed runs strongly while preserving relative ordering among successful runs. */
  def successGated[P, O](underlying: Utility[P, O], failureScale: Double = 0.05): Utility[P, O] =
    val clamped = math.max(0.0, math.min(1.0, failureScale))
    new Utility[P, O]:
      def score(principal: P, output: O, trace: TraceSummary): Double =
        val base = underlying.score(principal, output, trace)
        if trace.isSuccess then base else base * clamped

      override def breakdown(principal: P, output: O, trace: TraceSummary): ScoreBreakdown =
        val base = underlying.breakdown(principal, output, trace)
        val gatedTotal = if trace.isSuccess then base.total else base.total * clamped
        base.copy(
          total = gatedTotal,
          components = base.components :+ ScoreComponent(
            name = "success_gate",
            raw = if trace.isSuccess then 1.0 else clamped,
            weight = 1.0,
            contribution = if trace.isSuccess then 1.0 else clamped
          )
        )

  /** Name a utility component for use in weighted breakdowns. */
  def named[P, O](name: String, utility: Utility[P, O], weight: Double): NamedComponent[P, O] =
    NamedComponent(name, utility, weight)

  /** Weighted composite of multiple utilities. */
  def weighted[P, O](components: (Utility[P, O], Double)*): Utility[P, O] =
    val namedComponents =
      components.zipWithIndex.map { case ((utility, weight), idx) =>
        NamedComponent(s"component_${idx + 1}", utility, weight)
      }
    weightedNamed(namedComponents*)

  /** Weighted composite with explicit component names. */
  def weightedNamed[P, O](components: NamedComponent[P, O]*): Utility[P, O] =
    val totalWeight = components.map(_.weight).sum
    require(totalWeight > 0, "Total weight must be positive")
    new Utility[P, O]:
      def score(principal: P, output: O, trace: TraceSummary): Double =
        components.map { component =>
          component.utility.score(principal, output, trace) * component.weight
        }.sum / totalWeight

      override def breakdown(principal: P, output: O, trace: TraceSummary): ScoreBreakdown =
        val parts = components.toList.map { component =>
          val raw = component.utility.score(principal, output, trace)
          val contribution = raw * component.weight / totalWeight
          ScoreComponent(
            name = component.name,
            raw = raw,
            weight = component.weight,
            contribution = contribution
          )
        }
        ScoreBreakdown(parts.map(_.contribution).sum, parts)
