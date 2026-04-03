package com.tjclp.scalagent.core

import zio.json.ast.Json
import com.tjclp.scalagent.config.StructuredOutput

/** Type class for decoding agent output into type O.
  *
  * For `O = String`, this is a trivial passthrough from the result text.
  * For structured types, it delegates to `StructuredOutput[A]`.
  */
trait OutputCodec[O]:
  /** Extract the typed output from a successful result. */
  def decode(resultText: String, structuredOutput: Option[Json]): Either[String, O]

  /** If this codec requires structured output mode, return the schema. */
  def structuredOutputFormat: Option[StructuredOutput[?]] = None

object OutputCodec:
  /** String passthrough — no structured output needed. */
  given stringCodec: OutputCodec[String] with
    def decode(resultText: String, structuredOutput: Option[Json]): Either[String, String] =
      Right(resultText)

  /** Derive from StructuredOutput. Requires structured output mode from the provider. */
  given fromStructuredOutput[A](using so: StructuredOutput[A]): OutputCodec[A] with
    def decode(resultText: String, structuredOutput: Option[Json]): Either[String, A] =
      structuredOutput match
        case Some(json) => so.parse(json)
        case None       => Left("Expected structured output but none was returned")

    override def structuredOutputFormat: Option[StructuredOutput[?]] = Some(so)
