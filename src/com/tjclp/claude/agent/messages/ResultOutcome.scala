package com.tjclp.claude.agent.messages

import zio.json._

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

/** Reason for query error termination */
enum ErrorReason:
  case DuringExecution
  case MaxTurns
  case MaxBudgetUsd
  case MaxStructuredOutputRetries

object ErrorReason:
  given JsonDecoder[ErrorReason] = DeriveJsonDecoder.gen[ErrorReason]
  given JsonEncoder[ErrorReason] = DeriveJsonEncoder.gen[ErrorReason]

  def fromString(s: String): ErrorReason = s match
    case "error_during_execution"              => DuringExecution
    case "error_max_turns"                     => MaxTurns
    case "error_max_budget_usd"                => MaxBudgetUsd
    case "error_max_structured_output_retries" => MaxStructuredOutputRetries
    case other => throw new IllegalArgumentException(s"Unknown error reason: $other")

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
    toolUseId: String,
    toolInput: zio.json.ast.Json
)

object PermissionDenial:
  given JsonDecoder[PermissionDenial] = DeriveJsonDecoder.gen[PermissionDenial]
  given JsonEncoder[PermissionDenial] = DeriveJsonEncoder.gen[PermissionDenial]
