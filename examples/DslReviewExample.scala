package com.tjclp.scalagent.examples

import zio.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.experimental.*
import com.tjclp.scalagent.interop.claude.ClaudeInterpreter

/** DSL review example: operational scoring plus gated agentic semantic review.
  *
  * Demonstrates:
  * - pure `Utility` scoring with named breakdowns
  * - effectful `Reviewer` / `AgenticReviewer`
  * - `ReviewPermit` as an explicit impurity boundary
  * - `Evaluation.withReview` vs `AgenticReview.enrich`
  *
  * Run with: ./mill examples.go --example dsl-review
  *
  * Requires ANTHROPIC_API_KEY environment variable.
  */
object DslReviewExample extends ZIOAppDefault:

  case class ReviewJudgment(
      score: Double,
      rationale: String,
      strengths: List[String],
      issues: List[String]
  ) derives zio.json.JsonDecoder
  given StructuredOutput[ReviewJudgment] = StructuredOutput.derive[ReviewJudgment]

  val run: ZIO[Any, Any, Unit] =
    val baseOptions = AgentOptions.default
      .withModel(Model.haiku)
      .withPermissionMode(PermissionMode.DontAsk)

    val policy = ExecutionPolicy(
      budget = Budget.usd(1.00),
      maxTurns = Some(6)
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]
      // No tools needed — both agents do pure Q&A with safe default (no tools)
      answerAgent = ClaudeInterpreter.string(claudeAgent, baseOptions)
      reviewAgent = ClaudeInterpreter.typed[ReviewJudgment](claudeAgent, baseOptions.withMaxTurns(2))

      // 1. Produce a normal run
      runData <- ZIO.scoped {
        val run = answerAgent.run(
          "user",
          "In one short paragraph, explain what Scala.js is good for.",
          policy
        )
        for
          events <- run.events.runCollect.map(_.toList)
          output <- run.result
        yield (events, output)
      }
      (events, output) = runData

      // 2. Pure operational scoring
      operationalUtility = Utility.weightedNamed[String, String](
        Utility.named("reliability", Utility.reliability, 0.5),
        Utility.named("cost", Utility.costMinimizing, 0.3),
        Utility.named("simplicity", Utility.simplicityBiased, 0.2)
      )
      baseEval = Evaluation.evaluate("user", output, events, operationalUtility)

      _ <- Console.printLine("=== Operational Evaluation ===").orDie
      _ <- Console.printLine(s"Score: ${baseEval.score}").orDie
      _ <- Console.printLine(
        s"Breakdown: ${baseEval.breakdown.components.map(c => s"${c.name}=${"%.3f".format(c.raw)}").mkString(", ")}"
      ).orDie

      // 3. Build an effectful semantic reviewer
      semanticReviewer = Reviewer.fromAgent[String, String](
        reviewAgent.mapOutput(j => ReviewScore(j.score, j.rationale, j.strengths, j.issues)),
        renderPrompt = (principal, candidateOutput, trace) =>
          s"""Review the following model answer for the principal "$principal".
             |
             |Return JSON with:
             |- score: 0.0 to 1.0
             |- rationale: short explanation
             |- strengths: list of strengths
             |- issues: list of issues
             |
             |Answer:
             |$candidateOutput
             |
             |Trace:
             |- success: ${trace.isSuccess}
             |- costUsd: ${trace.costUsd}
             |- totalEvents: ${trace.totalEvents}
             |""".stripMargin,
        policy = ExecutionPolicy(maxTurns = Some(2))
      )

      // 4. Explicitly opt into the impurity boundary
      reviewedEval = SandboxedRun.withReviewPermit(label = "semantic-review", maxReviews = 1) { permit =>
        Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(AgenticReview.enrich(permit, baseEval, semanticReviewer)).getOrThrowFiberFailure()
        }
      }

      _ <- Console.printLine("\n=== Agentic Semantic Review ===").orDie
      _ <- Console.printLine(s"Operational score: ${reviewedEval.score}").orDie
      _ <- Console.printLine(s"Semantic review: ${reviewedEval.review.map(_.score).getOrElse(0.0)}").orDie
      _ <- Console.printLine(s"Rationale: ${reviewedEval.review.map(_.rationale).getOrElse("<missing>")}").orDie
      _ <- Console.printLine(s"Strengths: ${reviewedEval.review.map(_.strengths).getOrElse(Nil)}").orDie
      _ <- Console.printLine(s"Issues: ${reviewedEval.review.map(_.issues).getOrElse(Nil)}").orDie
    yield ()

    program.provide(ClaudeAgent.live)
