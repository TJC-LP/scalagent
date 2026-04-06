package com.tjclp.scalagent.core

import zio.*

/** Result of evaluating an agent run against a utility function.
  *
  * Bundles the principal, output, trace, complexity, and score
  * into a single loggable, inspectable result.
  */
final case class Evaluation[P, O](
    principal: P,
    output: O,
    trace: TraceSummary,
    complexity: Complexity,
    score: Double,
    breakdown: ScoreBreakdown = ScoreBreakdown.empty,
    review: Option[ReviewScore] = None
)

object Evaluation:
  /** Evaluate an agent run from collected events. */
  def evaluate[P, O](
      principal: P,
      output: O,
      events: List[AgentEvent],
      utility: Utility[P, O]
  ): Evaluation[P, O] =
    val trace = TraceSummary.fromEvents(events)
    val complexity = Complexity.fromTrace(trace)
    val score = utility.score(principal, output, trace)
    val breakdown = utility.breakdown(principal, output, trace)
    Evaluation(principal, output, trace, complexity, score, breakdown)

  /** Evaluate from a pre-computed TraceSummary. */
  def fromTrace[P, O](
      principal: P,
      output: O,
      trace: TraceSummary,
      utility: Utility[P, O]
  ): Evaluation[P, O] =
    val complexity = Complexity.fromTrace(trace)
    val score = utility.score(principal, output, trace)
    val breakdown = utility.breakdown(principal, output, trace)
    Evaluation(principal, output, trace, complexity, score, breakdown)

  /** Enrich an evaluation with an effectful semantic review. */
  def withReview[P, O](
      evaluation: Evaluation[P, O],
      reviewer: Reviewer[P, O]
  ): IO[com.tjclp.scalagent.errors.AgentError, Evaluation[P, O]] =
    reviewer.review(evaluation.principal, evaluation.output, evaluation.trace).map { review =>
      evaluation.copy(review = Some(review))
    }

  /** Alias for semantic-review enrichment, to distinguish it from pure scoring. */
  def withAgentReview[P, O](
      evaluation: Evaluation[P, O],
      reviewer: Reviewer[P, O]
  ): IO[com.tjclp.scalagent.errors.AgentError, Evaluation[P, O]] =
    withReview(evaluation, reviewer)
