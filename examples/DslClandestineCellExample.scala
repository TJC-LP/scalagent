package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.experimental.*

/**
 * Zero-trust clandestine cell system example.
 *
 * Goal:
 * - a leader can issue orders to cells
 * - cells never see the leader's identity or card
 * - cells only know the broker/dead-drop card
 * - cells cannot read each other's directives because routing is typed per cell
 * - secret reports can only be reviewed by sufficiently cleared reviewers
 *
 * Run with: ./mill examples.run dsl-cells
 */
object DslClandestineCellExample extends ZIOAppDefault:

  // ---------------------------------------------------------------------------
  // Cell topology
  // ---------------------------------------------------------------------------

  sealed trait Cell
  sealed trait AlphaCell   extends Cell
  sealed trait BravoCell   extends Cell
  sealed trait CharlieCell extends Cell

  private final case class LeaderIdentity(name: String)
  final case class BrokerCard(alias: String, capabilities: Set[String])
  final case class CommandAuthority(channel: String)

  final case class DeadDropId[C <: Cell](value: String)
  final case class ReturnAlias[C <: Cell](value: String)

  final case class MissionDirective(
    codename: String,
    objective: String,
    constraints: List[String])

  final case class FieldReport(
    codename: String,
    summary: String,
    nextNeed: String)

  final case class DirectiveEnvelope[C <: Cell](
    authority: CommandAuthority,
    directive: Classified[MissionDirective, Secret],
    replyTo: ReturnAlias[C])

  final case class ReportEnvelope[C <: Cell](
    report: Classified[FieldReport, Secret])

  // ---------------------------------------------------------------------------
  // Blind broker / dead drop
  // ---------------------------------------------------------------------------

  final class DeadDropBroker private (
    directivesRef: Ref[Map[String, List[Any]]],
    reportsRef: Ref[Map[String, List[Any]]],
    val card: BrokerCard):
    def publishDirective[C <: Cell](drop: DeadDropId[C], envelope: DirectiveEnvelope[C]): UIO[Unit] =
      directivesRef.update { state =>
        val updated = state.getOrElse(drop.value, Nil) :+ envelope
        state.updated(drop.value, updated)
      }

    def collectDirectives[C <: Cell](drop: DeadDropId[C]): UIO[List[DirectiveEnvelope[C]]] =
      directivesRef.modify { state =>
        val envelopes = state.getOrElse(drop.value, Nil).asInstanceOf[List[DirectiveEnvelope[C]]]
        (envelopes, state.updated(drop.value, Nil))
      }

    def publishReport[C <: Cell](alias: ReturnAlias[C], report: ReportEnvelope[C]): UIO[Unit] =
      reportsRef.update { state =>
        val updated = state.getOrElse(alias.value, Nil) :+ report
        state.updated(alias.value, updated)
      }

    def collectReports[C <: Cell](alias: ReturnAlias[C]): UIO[List[ReportEnvelope[C]]] =
      reportsRef.modify { state =>
        val reports = state.getOrElse(alias.value, Nil).asInstanceOf[List[ReportEnvelope[C]]]
        (reports, state.updated(alias.value, Nil))
      }
  end DeadDropBroker

  object DeadDropBroker:
    def make(cardAlias: String): UIO[DeadDropBroker] =
      for
        directives <- Ref.make(Map.empty[String, List[Any]])
        reports    <- Ref.make(Map.empty[String, List[Any]])
      yield DeadDropBroker(
        directives,
        reports,
        BrokerCard(cardAlias, Set("store", "blind-forward")),
      )

  // ---------------------------------------------------------------------------
  // Cell agents
  // ---------------------------------------------------------------------------

  private def cellAgent[C <: Cell](cellName: String): Agent[Any, DirectiveEnvelope[C], ReportEnvelope[C]] =
    new Agent[Any, DirectiveEnvelope[C], ReportEnvelope[C]]:
      def run(
        principal: Any,
        input: DirectiveEnvelope[C],
        policy: ExecutionPolicy,
      ): AgentRun[Any, ReportEnvelope[C]] =
        val directive = input.directive.value
        val report    = ReportEnvelope[C](
          Classified[FieldReport, Secret](
            FieldReport(
              codename = directive.codename,
              summary = s"$cellName executed objective: ${directive.objective}",
              nextNeed = s"$cellName requests fresh dead-drop instructions",
            )
          )
        )

        AgentRun(
          events = ZStream.fromIterable(
            List(
              AgentEvent.Status(s"$cellName received sealed directive ${directive.codename}"),
              AgentEvent.Completed(
                RunSummary(
                  durationMs = 150,
                  numTurns = 1,
                  costUsd = 0.0,
                  isSuccess = true,
                  resultText = Some(report.report.value.summary),
                  stopReason = Some("completed"),
                )
              ),
            )
          ),
          result = ZIO.succeed(report),
        )
      end run

  private def issueDirective[C <: Cell](
    leader: LeaderIdentity,
    authority: CommandAuthority,
    codename: String,
    objective: String,
    constraints: List[String],
    replyTo: ReturnAlias[C],
  ): DirectiveEnvelope[C] =
    // Note: leader identity is intentionally discarded here.
    DirectiveEnvelope(
      authority = authority,
      directive = Classified[MissionDirective, Secret](
        MissionDirective(codename, objective, constraints)
      ),
      replyTo = replyTo,
    )

  val run: ZIO[Any, Any, Unit] =
    val policy    = ExecutionPolicy(maxTurns = Some(1))
    val authority = CommandAuthority("northern-directorate")
    val leader    = LeaderIdentity("orchestrator-13")

    val alphaDrop   = DeadDropId[AlphaCell]("drop-alpha")
    val bravoDrop   = DeadDropId[BravoCell]("drop-bravo")
    val alphaReturn = ReturnAlias[AlphaCell]("return-alpha")
    val bravoReturn = ReturnAlias[BravoCell]("return-bravo")

    val alphaAgent = cellAgent[AlphaCell]("alpha-cell")
    val bravoAgent = cellAgent[BravoCell]("bravo-cell")

    val topSecretReviewer =
      Reviewer.from[String, Classified[FieldReport, Secret]] {
        (principal,
          report,
          trace,
        ) =>
          val passed = trace.isSuccess && report.value.summary.nonEmpty
          ZIO.succeed(
            ReviewScore(
              score = if passed then 0.95 else 0.10,
              rationale = s"$principal verified a secret field report without learning leader identity",
              strengths = List("compartmentalized routing", "single-cell scope"),
              issues = if passed then Nil else List("empty field summary"),
              confidence = Some(0.8),
              passed = Some(passed),
            )
          )
      }

    for
      broker <- DeadDropBroker.make("relay-7")

      _ <- Console.printLine("=== Zero-Trust Clandestine Cell System ===").orDie
      _ <- Console.printLine(s"Visible card to all parties: ${broker.card.alias} ${broker.card.capabilities}").orDie
      _ <- Console.printLine("Cells never talk directly to the leader or to each other.").orDie
      _ <- Console.printLine("Leader identity is discarded before directives enter the dead drop.").orDie

      _ <- broker.publishDirective(
        alphaDrop,
        issueDirective(
          leader,
          authority,
          codename = "EMBER",
          objective = "Observe the train station and count supply convoys",
          constraints = List("no direct contact", "reply via blind return route"),
          replyTo = alphaReturn,
        ),
      )
      _ <- broker.publishDirective(
        bravoDrop,
        issueDirective(
          leader,
          authority,
          codename = "FROST",
          objective = "Inspect the river crossing and note patrol timing",
          constraints = List("no direct contact", "reply via blind return route"),
          replyTo = bravoReturn,
        ),
      )

      alphaOrders <- broker.collectDirectives(alphaDrop)
      bravoOrders <- broker.collectDirectives(bravoDrop)

      _ <- Console
        .printLine(
          s"\nAlpha sees ${alphaOrders.size} directive(s) from authority ${alphaOrders.head.authority.channel}."
        )
        .orDie
      _ <- Console
        .printLine(s"Bravo sees ${bravoOrders.size} directive(s) from authority ${bravoOrders.head.authority.channel}.")
        .orDie
      _ <- Console
        .printLine("Neither sees LeaderIdentity; both only know the broker card and command channel alias.")
        .orDie

      alphaRunData <- ZIO.scoped {
        val run = alphaAgent.run("relay-7", alphaOrders.head, policy)
        for
          events <- run.events.runCollect.map(_.toList)
          report <- run.result
        yield (events, report)
      }
      bravoRunData <- ZIO.scoped {
        val run = bravoAgent.run("relay-7", bravoOrders.head, policy)
        for
          events <- run.events.runCollect.map(_.toList)
          report <- run.result
        yield (events, report)
      }
      (alphaEvents, alphaReport) = alphaRunData
      (bravoEvents, bravoReport) = bravoRunData

      _ <- broker.publishReport(alphaReturn, alphaReport)
      _ <- broker.publishReport(bravoReturn, bravoReport)

      collectedAlpha <- broker.collectReports(alphaReturn)
      collectedBravo <- broker.collectReports(bravoReturn)

      _ <- Console
        .printLine(
          s"\nLeader receives ${collectedAlpha.size + collectedBravo.size} report(s) through blind return aliases."
        )
        .orDie
      _ <- Console.printLine(s"Alpha report summary: ${collectedAlpha.head.report.value.summary}").orDie
      _ <- Console.printLine(s"Bravo report summary: ${collectedBravo.head.report.value.summary}").orDie

      alphaEval     = Evaluation.evaluate("directorate", alphaReport.report, alphaEvents, Utility.reliability)
      reviewedAlpha = SandboxedRun.withReviewPermit("secret-cell-review", maxReviews = 1) { permit =>
        Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe
            .run(
              AgenticReview.enrichClassified[String, FieldReport, TopSecret, Secret](
                permit,
                alphaEval,
                topSecretReviewer,
              )
            )
            .getOrThrowFiberFailure()
        }
      }

      _ <- Console.printLine("\n=== Classified Review ===").orDie
      _ <- Console.printLine(s"Operational score: ${reviewedAlpha.score}").orDie
      _ <- Console.printLine(s"Semantic review: ${reviewedAlpha.review.map(_.score).getOrElse(0.0)}").orDie
      _ <- Console.printLine(s"Review rationale: ${reviewedAlpha.review.map(_.rationale).getOrElse("<missing>")}").orDie

      _ <- Console.printLine("\n=== Compile-Time Guarantees (see commented examples) ===").orDie
      _ <- Console
        .printLine("  // broker.collectDirectives(bravoDrop) cannot be used where DeadDropId[AlphaCell] is expected")
        .orDie
      _ <- Console
        .printLine(
          "  // AgenticReview.enrichClassified[..., Internal, Secret](...) does not compile: insufficient clearance"
        )
        .orDie
      _ <- Console
        .printLine("  // Cells only ever see BrokerCard(alias=relay-7); they never see LeaderIdentity(orchestrator-13)")
        .orDie
    yield ()
    end for
  end run
end DslClandestineCellExample
