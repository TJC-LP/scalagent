package com.tjclp.scalagent.experimental

import zio.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.errors.AgentError

/**
 * Helpers for applying gated semantic reviews to evaluations.
 *
 * Kept outside the capture-checking file to avoid macro/tracer interactions.
 * The permit boundary is explicit at the call site, while the reviewer itself
 * remains a normal effectful `Reviewer`.
 */
object AgenticReview:
  def enrich[P, O](
    permit: ReviewPermit,
    evaluation: Evaluation[P, O],
    reviewer: Reviewer[P, O],
  ): IO[AgentError, Evaluation[P, O]] =
    ZIO.succeed(permit.consume()) *> Evaluation.withReview(evaluation, reviewer)

  /**
   * Enrich an evaluation with a reviewer that is only allowed to see classified outputs
   * when the reviewer's clearance dominates the output's visibility.
   */
  def enrichClassified[P, O, ReviewerLevel <: Visibility, DataLevel <: Visibility](
    permit: ReviewPermit,
    evaluation: Evaluation[P, Classified[O, DataLevel]],
    reviewer: Reviewer[P, Classified[O, DataLevel]],
  )(using CanSee[ReviewerLevel, DataLevel]
  ): IO[AgentError, Evaluation[P, Classified[O, DataLevel]]] =
    enrich(permit, evaluation, reviewer)
