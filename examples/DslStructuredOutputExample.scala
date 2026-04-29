package com.tjclp.scalagent.examples

import zio.*
import zio.json.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.codex.*
import com.tjclp.scalagent.interop.codex.CodexInterpreter

/**
 * DSL structured output: type-safe agent responses via native provider JSON schemas.
 *
 * Demonstrates:
 * - StructuredOutput.derive — macro-generated JSON schema from a case class
 * - ClaudeInterpreter.typed[A] — agent whose output type is A, not String
 * - ClaudeInterpreter.typedBuilder[A] — capability-typed structured output agent
 * - CodexInterpreter.typed[A] — same case class, different provider
 * - OutputCodec dispatch — transparent structured output wiring to each provider
 *
 * Run with: ./mill examples.run dsl-structured
 *
 * Requires ANTHROPIC_API_KEY environment variable.
 * Part 3 (Codex) also requires OPENAI_API_KEY and codex CLI installed.
 */
object DslStructuredOutputExample extends ZIOAppDefault:

  // --- Define structured output types ---

  case class RiskAssessment(
    @description("Risk severity: low, medium, high, or critical")
    severity: String,
    @description("Numeric risk score from 0.0 to 1.0")
    score: Double,
    @description("Specific risk findings")
    findings: List[String],
    @description("Recommended action to mitigate the risk")
    recommendation: String)
      derives JsonDecoder

  // Single line: macro generates JSON schema + decoder
  given StructuredOutput[RiskAssessment] = StructuredOutput.derive[RiskAssessment]

  val run: ZIO[Any, Any, Unit] =
    val baseOptions = AgentOptions.default
      .withModel(Model.sonnet)
      .withPermissionMode(PermissionMode.DontAsk)

    val policy = ExecutionPolicy(
      budget = Budget.usd(0.50),
      maxTurns = Some(3),
      stopStrategy = StopStrategy.FirstResponse,
    )

    val program = for
      claudeAgent <- ZIO.service[ClaudeAgent]
      _           <- Console.printLine("=== DSL Structured Output Example ===\n").orDie

      // --- Part 1: Simple typed agent ---
      _ <- Console.printLine("--- Part 1: ClaudeInterpreter.typed[RiskAssessment] ---").orDie

      // Agent[Any, String, RiskAssessment] — output is RiskAssessment, not String
      typedAgent = ClaudeInterpreter.typed[RiskAssessment](claudeAgent, baseOptions)

      assessment <- ZIO.scoped {
        typedAgent
          .run(
            "analyst",
            "Assess the cybersecurity risk of a legacy COBOL system handling PII data with no encryption at rest.",
            policy,
          )
          .result
      }

      _ <- Console.printLine(s"  Severity: ${assessment.severity}").orDie
      _ <- Console.printLine(s"  Score: ${assessment.score}").orDie
      _ <- Console.printLine(s"  Findings:").orDie
      _ <- ZIO.foreachDiscard(assessment.findings)(f => Console.printLine(s"    - $f").orDie)
      _ <- Console.printLine(s"  Recommendation: ${assessment.recommendation}\n").orDie

      // --- Part 2: Typed builder with capabilities ---
      _ <- Console.printLine("--- Part 2: ClaudeInterpreter.typedBuilder[RiskAssessment] ---").orDie

      // TypedAgent[Any, String, RiskAssessment, CanUseTools[ReadOnlyTools] & HasBudget]
      capableAgent = ClaudeInterpreter
        .typedBuilder[RiskAssessment](claudeAgent, baseOptions)
        .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
        .withBudget
        .build

      assessment2 <- ZIO.scoped {
        val agentRun = capableAgent.run(
          "auditor",
          "Read CLAUDE.md and assess the risk of the build configuration described there.",
          policy,
        )
        for
          events <- agentRun.events.runCollect.map(_.toList)
          output <- agentRun.result
          trace = TraceSummary.fromEvents(events)
          _ <- Console.printLine(s"  Turns: ${trace.numTurns}, Cost: $$${trace.costUsd}").orDie
        yield output
      }

      _ <- Console.printLine(s"  Severity: ${assessment2.severity}").orDie
      _ <- Console.printLine(s"  Score: ${assessment2.score}").orDie
      _ <- Console.printLine(s"  Findings:").orDie
      _ <- ZIO.foreachDiscard(assessment2.findings)(f => Console.printLine(s"    - $f").orDie)
      _ <- Console.printLine(s"  Recommendation: ${assessment2.recommendation}\n").orDie

      // --- Part 3: Codex typed agent (same RiskAssessment, different provider) ---
      _ <- Console.printLine("--- Part 3: CodexInterpreter.typed[RiskAssessment] ---").orDie

      codexClient        = CodexClient.create()
      codexThreadOptions = CodexThreadOptions(
        sandboxMode = Some(SandboxMode.ReadOnly),
        approvalPolicy = Some(ApprovalMode.Never),
        skipGitRepoCheck = true,
      )

      // Agent[Any, CodexInput, RiskAssessment] — same output type, Codex provider
      codexTypedAgent = CodexInterpreter.typed[RiskAssessment](codexClient, codexThreadOptions)

      assessment3 <- ZIO.scoped {
        codexTypedAgent
          .run(
            "analyst",
            "Assess the cybersecurity risk of a legacy COBOL system handling PII data with no encryption at rest.",
            policy,
          )
          .result
      }

      _ <- Console.printLine(s"  Severity: ${assessment3.severity}").orDie
      _ <- Console.printLine(s"  Score: ${assessment3.score}").orDie
      _ <- Console.printLine(s"  Findings:").orDie
      _ <- ZIO.foreachDiscard(assessment3.findings)(f => Console.printLine(s"    - $f").orDie)
      _ <- Console.printLine(s"  Recommendation: ${assessment3.recommendation}\n").orDie

      _ <- Console.printLine("=== Done ===").orDie
    yield ()

    program.provide(ClaudeAgent.live)
  end run
end DslStructuredOutputExample
