package com.tjclp.scalagent.errors

import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.types.{SessionId, ToolUseId}

/**
 * Typed error ADT for Claude Agent operations.
 *
 * This provides type-safe error handling instead of using generic `Throwable`. Errors can be pattern matched for
 * specific recovery strategies.
 *
 * Example usage:
 * {{{
 * Claude.query("Hello")
 *   .catchSome {
 *     case AgentError.PermissionDenied(tool, reason) =>
 *       Console.printLine(s"Permission denied for $tool: $reason")
 *     case AgentError.RateLimited(retryAfterMs) =>
 *       ZIO.sleep(retryAfterMs.millis) *> retry
 *   }
 * }}}
 */
sealed trait AgentError extends Exception:
  def message: String
  override def getMessage: String = message

object AgentError:

  /** Configuration validation error */
  final case class ConfigurationError(message: String) extends AgentError

  /** Permission was denied for a tool operation */
  final case class PermissionDenied(
    toolName: String,
    reason: String,
    toolUseId: Option[ToolUseId] = None)
      extends AgentError:
    def message: String = s"Permission denied for tool '$toolName': $reason"

  /** Tool execution failed with an error */
  final case class ToolExecutionFailed(
    toolName: String,
    cause: Throwable)
      extends AgentError:
    def message: String              = s"Tool '$toolName' failed: ${cause.getMessage}"
    override def getCause: Throwable = cause

  /** Session was closed or is no longer valid */
  final case class SessionClosed(
    sessionId: SessionId,
    reason: Option[String] = None)
      extends AgentError:
    def message: String = reason match
      case Some(r) => s"Session '${sessionId.value}' closed: $r"
      case None    => s"Session '${sessionId.value}' is closed"

  /** API rate limit exceeded */
  final case class RateLimited(
    retryAfterMs: Long)
      extends AgentError:
    def message: String = s"Rate limited, retry after ${retryAfterMs}ms"

  /** API returned an error response */
  final case class ApiError(
    code: Int,
    message: String,
    details: Option[String] = None)
      extends AgentError

  /** Operation was interrupted by user or system */
  final case class Interrupted(
    reason: String)
      extends AgentError:
    def message: String = s"Operation interrupted: $reason"

  /** Maximum turns exceeded */
  final case class MaxTurnsExceeded(
    maxTurns: Int,
    actualTurns: Int)
      extends AgentError:
    def message: String = s"Maximum turns exceeded: $actualTurns/$maxTurns"

  /** Budget limit exceeded */
  final case class BudgetExceeded(
    maxBudgetUsd: Double,
    actualCostUsd: Double)
      extends AgentError:
    def message: String = f"Budget exceeded: $$$actualCostUsd%.4f/$$$maxBudgetUsd%.4f"

  /** Unknown or unexpected error from SDK */
  final case class Unknown(
    message: String,
    cause: Option[Throwable] = None)
      extends AgentError:
    override def getCause: Throwable = cause.orNull

  /** A streamed SDK payload could not be parsed into the Scala model. */
  final case class MessageParseError(
    message: String,
    raw: Option[Json] = None,
    cause: Option[Throwable] = None)
      extends AgentError:
    override def getCause: Throwable = cause.orNull

  // JSON codecs for serialization
  given JsonEncoder[AgentError] = JsonEncoder[String].contramap(_.message)
  given JsonDecoder[AgentError] = JsonDecoder[String].map(msg => Unknown(msg))

  /** Convert from generic Throwable to typed AgentError */
  def fromThrowable(t: Throwable): AgentError = t match
    case e: AgentError => e
    case other         => Unknown(other.getMessage, Some(other))

  /** Convert from SDK error reason string to typed error */
  def fromErrorReason(reason: String, details: Option[String] = None): AgentError =
    reason match
      case "max_turns"   => MaxTurnsExceeded(0, 0) // Actual values set by caller
      case "max_budget"  => BudgetExceeded(0, 0)   // Actual values set by caller
      case "interrupted" => Interrupted("User requested")
      case other         => ApiError(500, other, details)
end AgentError
