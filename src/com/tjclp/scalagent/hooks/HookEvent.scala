package com.tjclp.scalagent.hooks

import zio.json.*

/** Hook event types supported by the Claude Agent SDK.
  *
  * These events allow intercepting and customizing agent behavior at various points during execution.
  */
enum HookEvent:
  /** Before a tool is executed - can modify input or block execution */
  case PreToolUse

  /** After successful tool execution - can inspect results */
  case PostToolUse

  /** After tool execution fails - can handle errors */
  case PostToolUseFailure

  /** When permission decision is needed - can approve/deny programmatically */
  case PermissionRequest

  /** System notification received */
  case Notification

  /** User prompt submitted */
  case UserPromptSubmit

  /** Session started or resumed */
  case SessionStart

  /** Session ended */
  case SessionEnd

  /** Stop hook triggered */
  case Stop

  /** Subagent initiated */
  case SubagentStart

  /** Subagent completed */
  case SubagentStop

  /** Before context compaction */
  case PreCompact

  /** Setup hook - triggered during initialization or maintenance (SDK 0.2.31) */
  case Setup

  /** Convert to SDK string representation */
  def toRaw: String = this match
    case PreToolUse        => "PreToolUse"
    case PostToolUse       => "PostToolUse"
    case PostToolUseFailure => "PostToolUseFailure"
    case PermissionRequest => "PermissionRequest"
    case Notification      => "Notification"
    case UserPromptSubmit  => "UserPromptSubmit"
    case SessionStart      => "SessionStart"
    case SessionEnd        => "SessionEnd"
    case Stop              => "Stop"
    case SubagentStart     => "SubagentStart"
    case SubagentStop      => "SubagentStop"
    case PreCompact        => "PreCompact"
    case Setup             => "Setup"

object HookEvent:
  given JsonEncoder[HookEvent] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[HookEvent] = JsonDecoder[String].mapOrFail {
    case "PreToolUse"        => Right(PreToolUse)
    case "PostToolUse"       => Right(PostToolUse)
    case "PostToolUseFailure" => Right(PostToolUseFailure)
    case "PermissionRequest" => Right(PermissionRequest)
    case "Notification"      => Right(Notification)
    case "UserPromptSubmit"  => Right(UserPromptSubmit)
    case "SessionStart"      => Right(SessionStart)
    case "SessionEnd"        => Right(SessionEnd)
    case "Stop"              => Right(Stop)
    case "SubagentStart"     => Right(SubagentStart)
    case "SubagentStop"      => Right(SubagentStop)
    case "PreCompact"        => Right(PreCompact)
    case "Setup"             => Right(Setup)
    case other               => Left(s"Unknown hook event: $other")
  }

  def fromString(s: String): HookEvent = s match
    case "PreToolUse"        => PreToolUse
    case "PostToolUse"       => PostToolUse
    case "PostToolUseFailure" => PostToolUseFailure
    case "PermissionRequest" => PermissionRequest
    case "Notification"      => Notification
    case "UserPromptSubmit"  => UserPromptSubmit
    case "SessionStart"      => SessionStart
    case "SessionEnd"        => SessionEnd
    case "Stop"              => Stop
    case "SubagentStart"     => SubagentStart
    case "SubagentStop"      => SubagentStop
    case "PreCompact"        => PreCompact
    case "Setup"             => Setup
    case other => throw new IllegalArgumentException(s"Unknown hook event: $other")
