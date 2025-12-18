package com.tjclp.scalagent.messages

import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.config.StructuredOutput
import com.tjclp.scalagent.types.ToolUseId

/** Result outcome from a completed query */
enum ResultOutcome:
  /** Successful query completion */
  case Success(
      durationMs: Long,
      durationApiMs: Long,
      numTurns: Int,
      result: String,
      totalCostUsd: Double,
      usage: ModelUsage,
      modelUsage: Map[String, PerModelUsage],
      permissionDenials: List[PermissionDenial],
      structuredOutput: Option[zio.json.ast.Json]
  )

  /** Query terminated with error */
  case Error(
      reason: ErrorReason,
      durationMs: Long,
      durationApiMs: Long,
      numTurns: Int,
      totalCostUsd: Double,
      usage: ModelUsage,
      modelUsage: Map[String, PerModelUsage],
      permissionDenials: List[PermissionDenial],
      errors: List[String]
  )

object ResultOutcome:
  given JsonDecoder[ResultOutcome] = DeriveJsonDecoder.gen[ResultOutcome]
  given JsonEncoder[ResultOutcome] = DeriveJsonEncoder.gen[ResultOutcome]

  // Extension methods for common field access across Success/Error cases
  extension (outcome: ResultOutcome)
    /** Get permission denials from either Success or Error */
    def permissionDenials: List[PermissionDenial] = outcome match
      case Success(_, _, _, _, _, _, _, denials, _) => denials
      case Error(_, _, _, _, _, _, _, denials, _)   => denials

    /** Get total cost in USD from either Success or Error */
    def totalCostUsd: Double = outcome match
      case Success(_, _, _, _, cost, _, _, _, _) => cost
      case Error(_, _, _, _, cost, _, _, _, _)   => cost

    /** Get number of turns from either Success or Error */
    def numTurns: Int = outcome match
      case Success(_, _, turns, _, _, _, _, _, _) => turns
      case Error(_, _, _, turns, _, _, _, _, _)   => turns

    /** Get duration in milliseconds from either Success or Error */
    def durationMs: Long = outcome match
      case Success(duration, _, _, _, _, _, _, _, _) => duration
      case Error(_, duration, _, _, _, _, _, _, _)   => duration

    /** Get API duration in milliseconds from either Success or Error */
    def durationApiMs: Long = outcome match
      case Success(_, apiDuration, _, _, _, _, _, _, _) => apiDuration
      case Error(_, _, apiDuration, _, _, _, _, _, _)   => apiDuration

    /** Get token usage from either Success or Error */
    def usage: ModelUsage = outcome match
      case Success(_, _, _, _, _, u, _, _, _) => u
      case Error(_, _, _, _, _, u, _, _, _)   => u

    /** Get per-model usage from either Success or Error */
    def modelUsage: Map[String, PerModelUsage] = outcome match
      case Success(_, _, _, _, _, _, mu, _, _) => mu
      case Error(_, _, _, _, _, _, mu, _, _)   => mu

    /** Check if this is a successful outcome */
    def isSuccess: Boolean = outcome match
      case _: Success => true
      case _: Error   => false

    /** Check if this is an error outcome */
    def isError: Boolean = !isSuccess

    /** Get the result text if successful */
    def resultText: Option[String] = outcome match
      case Success(_, _, _, result, _, _, _, _, _) => Some(result)
      case _: Error                                => None

    /** Get error details if this is an error outcome */
    def errors: List[String] = outcome match
      case Error(_, _, _, _, _, _, _, _, errs) => errs
      case _: Success                          => Nil

    /** Get the error reason if this is an error outcome */
    def errorReason: Option[ErrorReason] = outcome match
      case Error(reason, _, _, _, _, _, _, _, _) => Some(reason)
      case _: Success                            => None

    /** Get structured output JSON if available (Success only) */
    def structuredOutput: Option[Json] = outcome match
      case Success(_, _, _, _, _, _, _, _, so) => so
      case _: Error                            => None

    /** Parse structured output to a typed value.
      *
      * Requires a StructuredOutput type class instance for the target type.
      *
      * Example:
      * {{{
      * case class Result(summary: String, score: Int)
      * object Result:
      *   given Schema[Result] = DeriveSchema.gen[Result]
      *   given JsonDecoder[Result] = DeriveJsonDecoder.gen[Result]
      *   given StructuredOutput[Result] = StructuredOutput.derive[Result]
      *
      * outcome.parseAs[Result] match
      *   case Right(result) => println(result.summary)
      *   case Left(error) => println(s"Parse error: $error")
      * }}}
      */
    def parseAs[A](using so: StructuredOutput[A]): Either[String, A] =
      structuredOutput match
        case Some(json) => so.parse(json)
        case None       => Left("No structured output in result")

/** Reason for query error termination */
enum ErrorReason:
  case DuringExecution
  case MaxTurns
  case MaxBudgetUsd
  case MaxStructuredOutputRetries
  case Custom(value: String)

  def toRaw: String = this match
    case DuringExecution          => "error_during_execution"
    case MaxTurns                 => "error_max_turns"
    case MaxBudgetUsd             => "error_max_budget_usd"
    case MaxStructuredOutputRetries => "error_max_structured_output_retries"
    case Custom(v)                => v

object ErrorReason:
  given JsonEncoder[ErrorReason] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[ErrorReason] = JsonDecoder[String].map(fromString)

  def fromString(s: String): ErrorReason = s match
    case "error_during_execution"              => DuringExecution
    case "error_max_turns"                     => MaxTurns
    case "error_max_budget_usd"                => MaxBudgetUsd
    case "error_max_structured_output_retries" => MaxStructuredOutputRetries
    case other                                 => Custom(other)

/** Token usage statistics */
final case class ModelUsage(
    inputTokens: Int,
    outputTokens: Int,
    cacheReadInputTokens: Int,
    cacheCreationInputTokens: Int
)

object ModelUsage:
  given JsonDecoder[ModelUsage] = DeriveJsonDecoder.gen[ModelUsage]
  given JsonEncoder[ModelUsage] = DeriveJsonEncoder.gen[ModelUsage]

  val empty: ModelUsage = ModelUsage(0, 0, 0, 0)

/** Per-model usage statistics */
final case class PerModelUsage(
    inputTokens: Int,
    outputTokens: Int,
    cacheReadInputTokens: Int,
    cacheCreationInputTokens: Int,
    webSearchRequests: Int,
    costUSD: Double,
    contextWindow: Int
)

object PerModelUsage:
  given JsonDecoder[PerModelUsage] = DeriveJsonDecoder.gen[PerModelUsage]
  given JsonEncoder[PerModelUsage] = DeriveJsonEncoder.gen[PerModelUsage]

/** Record of a permission denial during query execution */
final case class PermissionDenial(
    toolName: String,
    toolUseId: ToolUseId,
    toolInput: zio.json.ast.Json
)

object PermissionDenial:
  given JsonDecoder[PermissionDenial] = DeriveJsonDecoder.gen[PermissionDenial]
  given JsonEncoder[PermissionDenial] = DeriveJsonEncoder.gen[PermissionDenial]
