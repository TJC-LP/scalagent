package com.tjclp.claude.agent.messages

import munit.FunSuite
import zio.json._
import zio.json.ast.Json
import zio.schema._
import com.tjclp.claude.agent.TestFixtures
import com.tjclp.claude.agent.TestFixtures._
import com.tjclp.claude.agent.config.StructuredOutput

class ResultOutcomeSpec extends FunSuite:

  // ============================================
  // Success Variant
  // ============================================

  test("Success stores all metrics"):
    val success = successOutcome
    assertEquals(success.durationMs, 1500L)
    assertEquals(success.durationApiMs, 1200L)
    assertEquals(success.numTurns, 3)
    assertEquals(success.result, "Task completed successfully!")
    assertEquals(success.totalCostUsd, 0.005)

  test("Success can have structured output"):
    val success = successOutcomeWithStructuredOutput
    assert(success.structuredOutput.isDefined)
    success.structuredOutput.foreach { json =>
      // Verify JSON structure
      json match
        case obj: Json.Obj =>
          assert(obj.get("summary").isDefined)
          assert(obj.get("score").isDefined)
        case other => fail(s"Expected Json.Obj, got $other")
    }

  // ============================================
  // Error Variant
  // ============================================

  test("Error stores error details"):
    val error = errorOutcome
    assertEquals(error.reason, ErrorReason.DuringExecution)
    assertEquals(error.errors, List("Tool execution failed"))
    assertEquals(error.numTurns, 1)

  test("Error with MaxTurns reason"):
    val error = errorOutcomeMaxTurns
    assertEquals(error.reason, ErrorReason.MaxTurns)
    assertEquals(error.numTurns, 10)

  // ============================================
  // Extension Methods - Common Accessors
  // ============================================

  test("permissionDenials works for both Success and Error"):
    assertEquals(successOutcome.permissionDenials, Nil)
    assertEquals(errorOutcome.permissionDenials, Nil)

  test("totalCostUsd works for both Success and Error"):
    assertEquals(successOutcome.totalCostUsd, 0.005)
    assertEquals(errorOutcome.totalCostUsd, 0.001)

  test("numTurns works for both Success and Error"):
    assertEquals(successOutcome.numTurns, 3)
    assertEquals(errorOutcome.numTurns, 1)

  test("durationMs works for both Success and Error"):
    assertEquals(successOutcome.durationMs, 1500L)
    assertEquals(errorOutcome.durationMs, 500L)

  test("durationApiMs works for both Success and Error"):
    assertEquals(successOutcome.durationApiMs, 1200L)
    assertEquals(errorOutcome.durationApiMs, 400L)

  test("usage works for both Success and Error"):
    assertEquals(successOutcome.usage, sampleUsage)
    assertEquals(errorOutcome.usage, sampleUsage)

  // ============================================
  // Extension Methods - isSuccess / isError
  // ============================================

  test("isSuccess returns true for Success"):
    assert(successOutcome.isSuccess)
    assert(successOutcomeWithStructuredOutput.isSuccess)

  test("isSuccess returns false for Error"):
    assert(!errorOutcome.isSuccess)
    assert(!errorOutcomeMaxTurns.isSuccess)

  test("isError returns true for Error"):
    assert(errorOutcome.isError)

  test("isError returns false for Success"):
    assert(!successOutcome.isError)

  // ============================================
  // Extension Methods - resultText
  // ============================================

  test("resultText returns text for Success"):
    assertEquals(successOutcome.resultText, Some("Task completed successfully!"))

  test("resultText returns None for Error"):
    assertEquals(errorOutcome.resultText, None)

  // ============================================
  // Extension Methods - errors / errorReason
  // ============================================

  test("errors returns list for Error"):
    assertEquals(errorOutcome.errors, List("Tool execution failed"))

  test("errors returns empty for Success"):
    assertEquals(successOutcome.errors, Nil)

  test("errorReason returns reason for Error"):
    assertEquals(errorOutcome.errorReason, Some(ErrorReason.DuringExecution))
    assertEquals(errorOutcomeMaxTurns.errorReason, Some(ErrorReason.MaxTurns))

  test("errorReason returns None for Success"):
    assertEquals(successOutcome.errorReason, None)

  // ============================================
  // Extension Methods - structuredOutput
  // ============================================

  test("structuredOutput returns Some for Success with output"):
    assert(successOutcomeWithStructuredOutput.structuredOutput.isDefined)

  test("structuredOutput returns None for Success without output"):
    assertEquals(successOutcome.structuredOutput, None)

  test("structuredOutput returns None for Error"):
    assertEquals(errorOutcome.structuredOutput, None)

  // ============================================
  // Extension Methods - parseAs
  // ============================================

  // Define a test case class for parsing
  case class TestResult(summary: String, score: Int)
  object TestResult:
    given Schema[TestResult] = DeriveSchema.gen[TestResult]
    given JsonDecoder[TestResult] = DeriveJsonDecoder.gen[TestResult]
    given StructuredOutput[TestResult] = StructuredOutput.derive[TestResult]

  test("parseAs returns Right for valid structured output"):
    val result = successOutcomeWithStructuredOutput.parseAs[TestResult]
    result match
      case Right(tr) =>
        assertEquals(tr.summary, "Done")
        assertEquals(tr.score, 95)
      case Left(err) => fail(s"Parse failed: $err")

  test("parseAs returns Left for Success without structured output"):
    val result = successOutcome.parseAs[TestResult]
    assert(result.isLeft)
    result.left.foreach(err => assert(err.contains("No structured output")))

  test("parseAs returns Left for Error"):
    val result = errorOutcome.parseAs[TestResult]
    assert(result.isLeft)

  // ============================================
  // ErrorReason
  // ============================================

  test("ErrorReason has correct raw values"):
    assertEquals(ErrorReason.DuringExecution.toRaw, "error_during_execution")
    assertEquals(ErrorReason.MaxTurns.toRaw, "error_max_turns")
    assertEquals(ErrorReason.MaxBudgetUsd.toRaw, "error_max_budget_usd")
    assertEquals(ErrorReason.MaxStructuredOutputRetries.toRaw, "error_max_structured_output_retries")

  test("ErrorReason Custom preserves value"):
    val custom = ErrorReason.Custom("my_error")
    assertEquals(custom.toRaw, "my_error")

  test("ErrorReason.fromString parses known values"):
    assertEquals(ErrorReason.fromString("error_during_execution"), ErrorReason.DuringExecution)
    assertEquals(ErrorReason.fromString("error_max_turns"), ErrorReason.MaxTurns)
    assertEquals(ErrorReason.fromString("error_max_budget_usd"), ErrorReason.MaxBudgetUsd)

  test("ErrorReason.fromString returns Custom for unknown"):
    val result = ErrorReason.fromString("unknown_error")
    result match
      case ErrorReason.Custom(value) => assertEquals(value, "unknown_error")
      case other                     => fail(s"Expected Custom, got $other")

  // ============================================
  // ModelUsage
  // ============================================

  test("ModelUsage stores token counts"):
    assertEquals(sampleUsage.inputTokens, 100)
    assertEquals(sampleUsage.outputTokens, 50)
    assertEquals(sampleUsage.cacheReadInputTokens, 10)
    assertEquals(sampleUsage.cacheCreationInputTokens, 5)

  test("ModelUsage.empty has all zeros"):
    assertEquals(ModelUsage.empty.inputTokens, 0)
    assertEquals(ModelUsage.empty.outputTokens, 0)
    assertEquals(ModelUsage.empty.cacheReadInputTokens, 0)
    assertEquals(ModelUsage.empty.cacheCreationInputTokens, 0)

  // ============================================
  // JSON Serialization
  // ============================================

  test("ResultOutcome.Success JSON round-trip"):
    val json = (successOutcome: ResultOutcome).toJson
    val parsed = json.fromJson[ResultOutcome]
    parsed match
      case Right(s: ResultOutcome.Success) =>
        assertEquals(s.numTurns, 3)
        assertEquals(s.totalCostUsd, 0.005)
      case other => fail(s"Expected Right(Success), got $other")

  test("ResultOutcome.Error JSON round-trip"):
    val json = (errorOutcome: ResultOutcome).toJson
    val parsed = json.fromJson[ResultOutcome]
    parsed match
      case Right(e: ResultOutcome.Error) =>
        assertEquals(e.reason, ErrorReason.DuringExecution)
      case other => fail(s"Expected Right(Error), got $other")

  test("ErrorReason JSON round-trip"):
    val original = ErrorReason.MaxTurns
    val json = original.toJson
    val parsed = json.fromJson[ErrorReason]
    assertEquals(parsed, Right(original))

  test("ModelUsage JSON round-trip"):
    val json = sampleUsage.toJson
    val parsed = json.fromJson[ModelUsage]
    assertEquals(parsed, Right(sampleUsage))
