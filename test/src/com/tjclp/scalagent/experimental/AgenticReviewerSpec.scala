package com.tjclp.scalagent.experimental

import zio.*
import com.tjclp.scalagent.core.*

class AgenticReviewerSpec extends munit.FunSuite:

  test("agentic reviewer requires explicit review permit and consumes it"):
    val reviewer = Reviewer.from[String, String] { (principal, output, trace) =>
      ZIO.succeed(
        ReviewScore(
          score = 0.9,
          rationale = s"Reviewed for $principal"
        )
      )
    }

    val baseEval = Evaluation.fromTrace(
      principal = "auditor",
      output = "result",
      trace = TraceSummary.fromRunSummary(RunSummary(1000, 1, 0.01, true, Some("result"))),
      utility = Utility.reliability[String, String]
    )

    val reviewed = SandboxedRun.withReviewPermit(maxReviews = 1) { permit =>
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe
          .run(AgenticReview.enrich(permit, baseEval, reviewer))
          .getOrThrowFiberFailure()
      }
    }

    assertEquals(reviewed.review.map(_.score), Some(0.9))

  test("review permit cannot be reused beyond its remaining count"):
    val reviewer = Reviewer.from[String, String]((_, _, _) => ZIO.succeed(ReviewScore(1.0, "ok")))

    val baseEval = Evaluation.fromTrace(
      principal = "auditor",
      output = "result",
      trace = TraceSummary.fromRunSummary(RunSummary(1000, 1, 0.01, true, Some("result"))),
      utility = Utility.reliability[String, String]
    )

    intercept[IllegalArgumentException] {
      SandboxedRun.withReviewPermit(maxReviews = 1) { permit =>
        Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(AgenticReview.enrich(permit, baseEval, reviewer)).getOrThrowFiberFailure()
          Runtime.default.unsafe.run(AgenticReview.enrich(permit, baseEval, reviewer)).getOrThrowFiberFailure()
        }
      }
    }
