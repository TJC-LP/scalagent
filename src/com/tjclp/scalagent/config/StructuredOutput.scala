package com.tjclp.scalagent.config

import zio.json.*
import zio.json.ast.Json
import zio.schema.*
import com.tjclp.scalagent.schema.SchemaToJson
import com.tjclp.scalagent.macros.StructuredOutputMacros

/**
 * Type class for structured output types.
 *
 * Provides compile-time derivation of JSON Schema and type-safe parsing. Use this to ensure your agent returns data in
 * exactly the format you need with full type safety.
 *
 * == Simplified Usage (Recommended) ==
 *
 * Use the macro-based derivation with optional @description annotations:
 *
 * {{{
 * import com.tjclp.scalagent.macros.description
 *
 * case class AnalysisResult(
 *   @description("A brief summary of findings") summary: String,
 *   @description("List of identified issues") issues: List[String],
 *   @description("Quality score from 1-10") score: Int
 * )
 *
 * given StructuredOutput[AnalysisResult] = StructuredOutput.derive[AnalysisResult]
 *
 * // Use in query
 * val options = AgentOptions.default.withStructuredOutput[AnalysisResult]
 * }}}
 *
 * == Legacy Usage ==
 *
 * If you need more control, you can still provide explicit Schema and JsonDecoder:
 *
 * {{{
 * object AnalysisResult:
 *   given Schema[AnalysisResult] = DeriveSchema.gen[AnalysisResult]
 *   given JsonDecoder[AnalysisResult] = DeriveJsonDecoder.gen[AnalysisResult]
 *   given StructuredOutput[AnalysisResult] = StructuredOutput.fromSchema[AnalysisResult]
 * }}}
 */
trait StructuredOutput[A]:
  /** JSON Schema for this type */
  def jsonSchema: Json

  /** Parse JSON result to typed value */
  def parse(json: Json): Either[String, A]

object StructuredOutput:

  /**
   * Derive StructuredOutput automatically from a case class.
   *
   * This is the recommended way to create StructuredOutput instances. It:
   *   - Generates JSON Schema at compile time
   *   - Extracts @description annotations for field documentation
   *   - Derives JsonDecoder automatically
   *
   * Example:
   * {{{
   * case class Result(
   *   @description("Summary of the analysis") summary: String,
   *   @description("Score from 1-10") score: Int,
   *   tags: List[String]  // Description is optional
   * )
   *
   * given StructuredOutput[Result] = StructuredOutput.derive[Result]
   * }}}
   */
  inline def derive[A]: StructuredOutput[A] =
    StructuredOutputMacros.derive[A]

  /**
   * Create StructuredOutput from explicit Schema and JsonDecoder.
   *
   * Use this if you need more control over schema generation or have
   * pre-existing Schema/JsonDecoder instances.
   *
   * Example:
   * {{{
   * case class Result(summary: String, score: Int)
   * object Result:
   *   given Schema[Result] = DeriveSchema.gen[Result]
   *   given JsonDecoder[Result] = DeriveJsonDecoder.gen[Result]
   *   given StructuredOutput[Result] = StructuredOutput.fromSchema[Result]
   * }}}
   */
  def fromSchema[A](using schema: Schema[A], decoder: JsonDecoder[A]): StructuredOutput[A] =
    new StructuredOutput[A]:
      val jsonSchema: Json = SchemaToJson.convert(schema)

      def parse(json: Json): Either[String, A] =
        json.as[A]

  /** Create OutputFormat from a StructuredOutput type class instance */
  def toOutputFormat[A](using so: StructuredOutput[A]): OutputFormat =
    OutputFormat(so.jsonSchema)

  /** Summon a StructuredOutput instance */
  def apply[A](using so: StructuredOutput[A]): StructuredOutput[A] = so
end StructuredOutput
