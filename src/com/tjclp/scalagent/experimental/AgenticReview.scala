package com.tjclp.scalagent.experimental

import zio.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.errors.AgentError

/** Helpers for applying gated semantic reviews to evaluations.
  *
  * Kept outside the capture-checking file to avoid macro/tracer interactions.
  * The permit boundary is explicit at the call site, while the reviewer itself
  * remains a normal effectful `Reviewer`.
  */
object AgenticReview:
  def enrich[P, O](
      permit: ReviewPermit,
      evaluation: Evaluation[P, O],
      reviewer: Reviewer[P, O]
  ): IO[AgentError, Evaluation[P, O]] =
    permit.consume()
    Evaluation.withReview(evaluation, reviewer)
