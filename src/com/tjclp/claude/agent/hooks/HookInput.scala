package com.tjclp.claude.agent.hooks

import zio.json._
import zio.json.ast.Json
import com.tjclp.claude.agent.tools.ToolName

/** Input payloads for different hook event types.
  *
  * Each hook receives specific context relevant to the event being handled.
  */
sealed trait HookInput:
  /** Session ID for this hook invocation */
  def sessionId: String

  /** Current working directory */
  def cwd: String

  /** Path to the transcript file */
  def transcriptPath: String

object HookInput:

  /** Input for PreToolUse hook - before tool execution */
  final case class PreToolUse(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: String,
      agentId: Option[String] = None
  ) extends HookInput

  /** Input for PostToolUse hook - after successful tool execution */
  final case class PostToolUse(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: String,
      toolResponse: String,
      agentId: Option[String] = None
  ) extends HookInput

  /** Input for PostToolUseFailure hook - after tool execution error */
  final case class PostToolUseFailure(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: String,
      error: String,
      agentId: Option[String] = None
  ) extends HookInput

  /** Input for PermissionRequest hook - when permission decision needed */
  final case class PermissionRequest(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: String,
      suggestions: List[PermissionSuggestion] = Nil,
      blockedPath: Option[String] = None,
      decisionReason: Option[String] = None,
      agentId: Option[String] = None
  ) extends HookInput

  /** Input for Notification hook */
  final case class Notification(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      message: String,
      level: NotificationLevel = NotificationLevel.Info
  ) extends HookInput

  /** Input for UserPromptSubmit hook */
  final case class UserPromptSubmit(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      prompt: String
  ) extends HookInput

  /** Input for SessionStart hook */
  final case class SessionStart(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      isResume: Boolean = false,
      previousSessionId: Option[String] = None
  ) extends HookInput

  /** Input for SessionEnd hook */
  final case class SessionEnd(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      reason: SessionEndReason,
      totalCostUsd: Option[Double] = None
  ) extends HookInput

  /** Input for Stop hook */
  final case class Stop(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      reason: String
  ) extends HookInput

  /** Input for SubagentStart hook */
  final case class SubagentStart(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      subagentId: String,
      subagentType: String,
      parentToolUseId: String
  ) extends HookInput

  /** Input for SubagentStop hook */
  final case class SubagentStop(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      subagentId: String,
      subagentType: String,
      parentToolUseId: String,
      success: Boolean
  ) extends HookInput

  /** Input for PreCompact hook - before context compaction */
  final case class PreCompact(
      sessionId: String,
      cwd: String,
      transcriptPath: String,
      currentTokens: Int,
      trigger: CompactTrigger
  ) extends HookInput

/** Permission suggestion from the SDK */
final case class PermissionSuggestion(
    toolName: ToolName,
    behavior: PermissionBehavior,
    prefix: Option[String] = None
)

object PermissionSuggestion:
  given JsonDecoder[PermissionSuggestion] = DeriveJsonDecoder.gen[PermissionSuggestion]
  given JsonEncoder[PermissionSuggestion] = DeriveJsonEncoder.gen[PermissionSuggestion]

/** Permission behavior options */
enum PermissionBehavior:
  case Allow, Deny, Ask

  def toRaw: String = this match
    case Allow => "allow"
    case Deny  => "deny"
    case Ask   => "ask"

object PermissionBehavior:
  given JsonEncoder[PermissionBehavior] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[PermissionBehavior] = JsonDecoder[String].mapOrFail {
    case "allow" => Right(Allow)
    case "deny"  => Right(Deny)
    case "ask"   => Right(Ask)
    case other   => Left(s"Unknown permission behavior: $other")
  }

/** Notification level */
enum NotificationLevel:
  case Info, Warning, Error

object NotificationLevel:
  given JsonEncoder[NotificationLevel] = JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[NotificationLevel] = JsonDecoder[String].mapOrFail {
    case "info"    => Right(Info)
    case "warning" => Right(Warning)
    case "error"   => Right(Error)
    case other     => Left(s"Unknown notification level: $other")
  }

/** Reason for session ending */
enum SessionEndReason:
  case Success, Error, Interrupted, MaxTurns, MaxBudget

object SessionEndReason:
  given JsonEncoder[SessionEndReason] = JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[SessionEndReason] = JsonDecoder[String].mapOrFail {
    case "success"     => Right(Success)
    case "error"       => Right(Error)
    case "interrupted" => Right(Interrupted)
    case "max_turns"   => Right(MaxTurns)
    case "max_budget"  => Right(MaxBudget)
    case other         => Left(s"Unknown session end reason: $other")
  }

/** Trigger for context compaction */
enum CompactTrigger:
  case Manual, Auto

object CompactTrigger:
  given JsonEncoder[CompactTrigger] = JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[CompactTrigger] = JsonDecoder[String].mapOrFail {
    case "manual" => Right(Manual)
    case "auto"   => Right(Auto)
    case other    => Left(s"Unknown compact trigger: $other")
  }
