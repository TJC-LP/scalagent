package com.tjclp.scalagent.experimental

import scala.compiletime.testing.typeChecks
import zio.*
import com.tjclp.scalagent.core.*

class ClassifiedReviewSpec extends munit.FunSuite:

  test("classified review compiles when reviewer clearance dominates output visibility"):
    assert(typeChecks("""
      import com.tjclp.scalagent.core.*
      import com.tjclp.scalagent.experimental.*
      val reviewer = Reviewer.from[String, Classified[String, Internal]]((_, _, _) => ZIO.succeed(ReviewScore(1.0, "ok")))
      val eval = Evaluation.fromTrace(
        "auditor",
        Classified[String, Internal]("redacted"),
        TraceSummary.fromRunSummary(RunSummary(0, 0, 0.0, true)),
        Utility.reliability[String, Classified[String, Internal]]
      )
      SandboxedRun.withReviewPermit() { permit =>
        AgenticReview.enrichClassified[String, String, Secret, Internal](permit, eval, reviewer)
      }
    """))

  test("classified review does NOT compile when reviewer clearance is too low"):
    assert(!typeChecks("""
      import com.tjclp.scalagent.core.*
      import com.tjclp.scalagent.experimental.*
      val reviewer = Reviewer.from[String, Classified[String, Secret]]((_, _, _) => ZIO.succeed(ReviewScore(1.0, "ok")))
      val eval = Evaluation.fromTrace(
        "auditor",
        Classified[String, Secret]("classified"),
        TraceSummary.fromRunSummary(RunSummary(0, 0, 0.0, true)),
        Utility.reliability[String, Classified[String, Secret]]
      )
      SandboxedRun.withReviewPermit() { permit =>
        AgenticReview.enrichClassified[String, String, Internal, Secret](permit, eval, reviewer)
      }
    """))
