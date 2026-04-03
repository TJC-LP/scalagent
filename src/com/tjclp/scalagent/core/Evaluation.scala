package com.tjclp.scalagent.core

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
    score: Double
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
    Evaluation(principal, output, trace, complexity, score)

  /** Evaluate from a pre-computed TraceSummary. */
  def fromTrace[P, O](
      principal: P,
      output: O,
      trace: TraceSummary,
      utility: Utility[P, O]
  ): Evaluation[P, O] =
    val complexity = Complexity.fromTrace(trace)
    val score = utility.score(principal, output, trace)
    Evaluation(principal, output, trace, complexity, score)
