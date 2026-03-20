package com.tjclp.scalagent.hooks

import com.tjclp.scalagent.json.StringEnumJsonCodec
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

  /** Stop failure hook - triggered when agent fails to stop cleanly */
  case StopFailure

  /** Subagent initiated */
  case SubagentStart

  /** Subagent completed */
  case SubagentStop

  /** Before context compaction */
  case PreCompact

  /** After context compaction completes */
  case PostCompact

  /** Setup hook - triggered during initialization or maintenance */
  case Setup

  /** Teammate idle hook - triggered in multi-agent coordination */
  case TeammateIdle

  /** Task completed hook - fired when a background task completes */
  case TaskCompleted

  /** MCP elicitation hook - triggered on elicitation requests */
  case Elicitation

  /** Elicitation result hook - triggered after elicitation completes */
  case ElicitationResult

  /** Configuration change hook */
  case ConfigChange

  /** Git worktree creation hook */
  case WorktreeCreate

  /** Git worktree removal hook */
  case WorktreeRemove

  /** Instructions loaded hook - triggered when CLAUDE.md or memory files are loaded */
  case InstructionsLoaded

  /** Convert to SDK string representation */
  def toRaw: String = this match
    case PreToolUse         => "PreToolUse"
    case PostToolUse        => "PostToolUse"
    case PostToolUseFailure => "PostToolUseFailure"
    case PermissionRequest  => "PermissionRequest"
    case Notification       => "Notification"
    case UserPromptSubmit   => "UserPromptSubmit"
    case SessionStart       => "SessionStart"
    case SessionEnd         => "SessionEnd"
    case Stop               => "Stop"
    case StopFailure        => "StopFailure"
    case SubagentStart      => "SubagentStart"
    case SubagentStop       => "SubagentStop"
    case PreCompact         => "PreCompact"
    case PostCompact        => "PostCompact"
    case Setup              => "Setup"
    case TeammateIdle       => "TeammateIdle"
    case TaskCompleted      => "TaskCompleted"
    case Elicitation        => "Elicitation"
    case ElicitationResult  => "ElicitationResult"
    case ConfigChange       => "ConfigChange"
    case WorktreeCreate     => "WorktreeCreate"
    case WorktreeRemove       => "WorktreeRemove"
    case InstructionsLoaded   => "InstructionsLoaded"

object HookEvent:
  given JsonEncoder[HookEvent] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[HookEvent] = StringEnumJsonCodec.decoderOrFail {
    case "PreToolUse"        => Right(PreToolUse)
    case "PostToolUse"       => Right(PostToolUse)
    case "PostToolUseFailure" => Right(PostToolUseFailure)
    case "PermissionRequest" => Right(PermissionRequest)
    case "Notification"      => Right(Notification)
    case "UserPromptSubmit"  => Right(UserPromptSubmit)
    case "SessionStart"      => Right(SessionStart)
    case "SessionEnd"        => Right(SessionEnd)
    case "Stop"              => Right(Stop)
    case "StopFailure"       => Right(StopFailure)
    case "SubagentStart"     => Right(SubagentStart)
    case "SubagentStop"      => Right(SubagentStop)
    case "PreCompact"        => Right(PreCompact)
    case "PostCompact"       => Right(PostCompact)
    case "Setup"             => Right(Setup)
    case "TeammateIdle"      => Right(TeammateIdle)
    case "TaskCompleted"     => Right(TaskCompleted)
    case "Elicitation"       => Right(Elicitation)
    case "ElicitationResult" => Right(ElicitationResult)
    case "ConfigChange"      => Right(ConfigChange)
    case "WorktreeCreate"    => Right(WorktreeCreate)
    case "WorktreeRemove"      => Right(WorktreeRemove)
    case "InstructionsLoaded"  => Right(InstructionsLoaded)
    case other                 => Left(s"Unknown hook event: $other")
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
    case "StopFailure"       => StopFailure
    case "SubagentStart"     => SubagentStart
    case "SubagentStop"      => SubagentStop
    case "PreCompact"        => PreCompact
    case "PostCompact"       => PostCompact
    case "Setup"             => Setup
    case "TeammateIdle"      => TeammateIdle
    case "TaskCompleted"     => TaskCompleted
    case "Elicitation"       => Elicitation
    case "ElicitationResult" => ElicitationResult
    case "ConfigChange"      => ConfigChange
    case "WorktreeCreate"    => WorktreeCreate
    case "WorktreeRemove"      => WorktreeRemove
    case "InstructionsLoaded"  => InstructionsLoaded
    case other => throw new IllegalArgumentException(s"Unknown hook event: $other")
