package com.tjclp.scalagent.config

import zio.json._
import zio.json.ast.Json
import zio.schema._
import com.tjclp.scalagent.schema.SchemaToJson

/** Type class for structured output types.
  *
  * Provides compile-time derivation of JSON Schema and type-safe parsing. Use this to ensure your agent returns data in
  * exactly the format you need with full type safety.
  *
  * Example:
  * {{{
  * case class AnalysisResult(summary: String, issues: List[Issue], score: Int)
  *
  * object AnalysisResult:
  *   given Schema[AnalysisResult] = DeriveSchema.gen[AnalysisResult]
  *   given JsonDecoder[AnalysisResult] = DeriveJsonDecoder.gen[AnalysisResult]
  *   given StructuredOutput[AnalysisResult] = StructuredOutput.derive[AnalysisResult]
  *
  * // Use in query
  * val options = AgentOptions.default.withStructuredOutput[AnalysisResult]
  *
  * // Parse result
  * success.parseAs[AnalysisResult] match
  *   case Right(result) => println(result.summary)
  *   case Left(error) => println(s"Parse error: $error")
  * }}}
  */
trait StructuredOutput[A]:
  /** JSON Schema for this type */
  def jsonSchema: Json

  /** Parse JSON result to typed value */
  def parse(json: Json): Either[String, A]

object StructuredOutput:
  /** Derive StructuredOutput from zio-schema and zio-json.
    *
    * Requires implicit Schema[A] for JSON Schema generation and JsonDecoder[A] for parsing.
    *
    * Example:
    * {{{
    * case class Result(summary: String, score: Int)
    * object Result:
    *   given Schema[Result] = DeriveSchema.gen[Result]
    *   given JsonDecoder[Result] = DeriveJsonDecoder.gen[Result]
    *   given StructuredOutput[Result] = StructuredOutput.derive[Result]
    * }}}
    */
  def derive[A](using schema: Schema[A], decoder: JsonDecoder[A]): StructuredOutput[A] =
    new StructuredOutput[A]:
      val jsonSchema: Json = SchemaToJson.convert(schema)

      def parse(json: Json): Either[String, A] =
        json.as[A]

  /** Create OutputFormat from a StructuredOutput type class instance */
  def toOutputFormat[A](using so: StructuredOutput[A]): OutputFormat =
    OutputFormat(so.jsonSchema)

  /** Summon a StructuredOutput instance */
  def apply[A](using so: StructuredOutput[A]): StructuredOutput[A] = so
