package com.tjclp.scalagent.core

import zio.*
import com.tjclp.scalagent.errors.AgentError

/** Effectful semantic reviewer for agent runs.
  *
  * Unlike `Utility`, a `Reviewer` may be asynchronous, expensive, and agent-backed.
  */
trait Reviewer[-P, -O]:
  def review(principal: P, output: O, trace: TraceSummary): IO[AgentError, ReviewScore]

object Reviewer:
  def from[P, O](f: (P, O, TraceSummary) => IO[AgentError, ReviewScore]): Reviewer[P, O] =
    (principal: P, output: O, trace: TraceSummary) => f(principal, output, trace)

  /** Build a reviewer from a typed review agent. */
  def fromAgent[P, O](
      reviewerAgent: Agent[Any, String, ReviewScore],
      renderPrompt: (P, O, TraceSummary) => String,
      policy: ExecutionPolicy = ExecutionPolicy.unbounded
  ): Reviewer[P, O] =
    from { (principal, output, trace) =>
      ZIO.scoped {
        reviewerAgent.run((), renderPrompt(principal, output, trace), policy).result
      }
    }
