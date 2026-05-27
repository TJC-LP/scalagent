package com.tjclp.scalagent.config

import munit.FunSuite
import zio.json.*
import zio.schema.*

class StructuredOutputParitySpec extends FunSuite:
  private inline def assertSchemaParity[A](using Schema[A], JsonDecoder[A]): Unit =
    val macroSchema = StructuredOutput.derive[A].jsonSchema
    val schemaToJsonSchema = StructuredOutput.fromSchema[A].jsonSchema
    assertEquals(macroSchema, schemaToJsonSchema)

  case class BasicOutput(
      name: String,
      count: Int,
      active: Boolean
  ) derives JsonDecoder

  object BasicOutput:
    given Schema[BasicOutput] = DeriveSchema.gen[BasicOutput]

  test("macro schema matches SchemaToJson for primitive fields"):
    assertSchemaParity[BasicOutput]

  case class FloatingPointOutput(
      ratio: Float,
      score: Double
  ) derives JsonDecoder

  object FloatingPointOutput:
    given Schema[FloatingPointOutput] = DeriveSchema.gen[FloatingPointOutput]

  test("macro schema matches SchemaToJson for float and double fields"):
    assertSchemaParity[FloatingPointOutput]

  enum Severity derives JsonDecoder:
    case Low, Medium, High

  object Severity:
    given Schema[Severity] = DeriveSchema.gen[Severity]

  case class DetailedIssue(
      severity: Severity,
      message: String,
      line: Option[Int]
  ) derives JsonDecoder

  object DetailedIssue:
    given Schema[DetailedIssue] = DeriveSchema.gen[DetailedIssue]

  case class ComplexOutput(
      summary: String,
      issues: List[DetailedIssue],
      metadata: Option[Map[String, String]],
      tags: Set[String]
  ) derives JsonDecoder

  object ComplexOutput:
    given Schema[ComplexOutput] = DeriveSchema.gen[ComplexOutput]

  test("macro schema matches SchemaToJson for nested, option, map, and set fields"):
    assertSchemaParity[ComplexOutput]

  case class AdvancedShapes(
      score: Either[String, Int],
      point: (Double, Double)
  ) derives JsonDecoder

  object AdvancedShapes:
    given Schema[AdvancedShapes] = DeriveSchema.gen[AdvancedShapes]

  test("macro schema matches SchemaToJson for either and tuple fields"):
    assertSchemaParity[AdvancedShapes]
