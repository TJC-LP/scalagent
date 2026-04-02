package com.tjclp.scalagent.hooks

import com.tjclp.scalagent.config.PermissionMode
import com.tjclp.scalagent.json.StringEnumJsonCodec
import com.tjclp.scalagent.messages.AssistantMessageError
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{SessionId, SubagentId, ToolUseId}
import zio.json.*
import zio.json.ast.Json

/** Input payloads for different hook event types.
  *
  * Each hook receives specific context relevant to the event being handled.
  */
sealed trait HookInput:
  /** Session ID for this hook invocation */
  def sessionId: SessionId

  /** Current working directory */
  def cwd: String

  /** Path to the transcript file */
  def transcriptPath: String

  /** Permission mode active for this session */
  def permissionMode: Option[PermissionMode]

  /** Subagent identifier, present when the hook fires from within a subagent.
    * Absent for the main thread, even in --agent sessions.
    */
  def hookAgentId: Option[SubagentId]

  /** Agent type name (e.g., "general-purpose", "code-reviewer").
    * Present when the hook fires from within a subagent (alongside agentId),
    * or on the main thread of a session started with --agent (without agentId).
    */
  def hookAgentType: Option[String]

object HookInput:

  /** Input for PreToolUse hook - before tool execution */
  final case class PreToolUse(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: ToolUseId,
      agentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput:
    def hookAgentId: Option[SubagentId] = agentId

  /** Input for PostToolUse hook - after successful tool execution */
  final case class PostToolUse(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: ToolUseId,
      toolResponse: String,
      agentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput:
    def hookAgentId: Option[SubagentId] = agentId

  /** Input for PostToolUseFailure hook - after tool execution error */
  final case class PostToolUseFailure(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: ToolUseId,
      error: String,
      agentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      isInterrupt: Boolean = false,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput:
    def hookAgentId: Option[SubagentId] = agentId

  /** Input for PermissionRequest hook - when permission decision needed */
  final case class PermissionRequest(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      permissionSuggestions: List[Json] = Nil,
      permissionMode: Option[PermissionMode] = None,
      toolUseId: Option[ToolUseId] = None,
      agentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      title: Option[String] = None,
      displayName: Option[String] = None,
      description: Option[String] = None
  ) extends HookInput:
    def hookAgentId: Option[SubagentId] = agentId

  /** Input for Notification hook */
  final case class Notification(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      message: String,
      title: Option[String] = None,
      notificationType: String = "info",
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for UserPromptSubmit hook */
  final case class UserPromptSubmit(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      prompt: String,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for SessionStart hook */
  final case class SessionStart(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      source: SessionStartSource,
      agentType: Option[String] = None,
      model: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput:
    def hookAgentType: Option[String] = agentType

  /** Input for SessionEnd hook */
  final case class SessionEnd(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      reason: ExitReason,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None,
      totalCostUsd: Option[Double] = None
  ) extends HookInput

  /** Input for Stop hook */
  final case class Stop(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      stopHookActive: Boolean = false,
      lastAssistantMessage: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for StopFailure hook - triggered when agent fails to stop cleanly */
  final case class StopFailure(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      error: AssistantMessageError,
      errorDetails: Option[String] = None,
      lastAssistantMessage: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for SubagentStart hook */
  final case class SubagentStart(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      agentId: SubagentId,
      agentType: String,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput:
    def hookAgentId: Option[SubagentId] = Some(agentId)
    def hookAgentType: Option[String] = Some(agentType)

    @deprecated("Use agentId", "0.2.63")
    def subagentId: SubagentId = agentId

    @deprecated("Use agentType", "0.2.63")
    def subagentType: String = agentType

  /** Input for SubagentStop hook */
  final case class SubagentStop(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      stopHookActive: Boolean = false,
      agentId: SubagentId,
      agentTranscriptPath: String,
      agentType: String,
      lastAssistantMessage: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput:
    def hookAgentId: Option[SubagentId] = Some(agentId)
    def hookAgentType: Option[String] = Some(agentType)

    @deprecated("Use agentId", "0.2.63")
    def subagentId: SubagentId = agentId

    @deprecated("Use agentType", "0.2.63")
    def subagentType: String = agentType

  /** Input for PostCompact hook - after context compaction completes */
  final case class PostCompact(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      trigger: CompactTrigger,
      compactSummary: String,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for PreCompact hook - before context compaction */
  final case class PreCompact(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      trigger: CompactTrigger,
      customInstructions: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for Setup hook */
  final case class Setup(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      trigger: SetupTrigger,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for TeammateIdle hook - multi-agent coordination */
  final case class TeammateIdle(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      teammateName: String,
      teamName: String,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for TaskCompleted hook */
  final case class TaskCompleted(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      taskId: String,
      taskSubject: String,
      taskDescription: Option[String] = None,
      teammateName: Option[String] = None,
      teamName: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for Elicitation hook - MCP elicitation request */
  final case class Elicitation(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      mcpServerName: String,
      message: String,
      mode: Option[ElicitationMode] = None,
      url: Option[String] = None,
      elicitationId: Option[String] = None,
      requestedSchema: Option[Json] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for ElicitationResult hook */
  final case class ElicitationResult(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      mcpServerName: String,
      action: ElicitationAction,
      elicitationId: Option[String] = None,
      mode: Option[ElicitationMode] = None,
      content: Option[Json] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for ConfigChange hook */
  final case class ConfigChange(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      source: ConfigChangeSource,
      filePath: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for WorktreeCreate hook */
  final case class WorktreeCreate(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      name: String,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for WorktreeRemove hook */
  final case class WorktreeRemove(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      worktreePath: String,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for InstructionsLoaded hook - when CLAUDE.md or memory files are loaded */
  final case class InstructionsLoaded(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      filePath: String,
      memoryType: MemoryType,
      loadReason: InstructionsLoadReason,
      globs: Option[List[String]] = None,
      triggerFilePath: Option[String] = None,
      parentFilePath: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for PermissionDenied hook - when a tool permission is denied */
  final case class PermissionDenied(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      toolName: ToolName,
      toolInput: Json,
      toolUseId: ToolUseId,
      reason: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for TaskCreated hook - when a background task is created */
  final case class TaskCreated(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      taskId: String,
      taskSubject: String,
      taskDescription: Option[String] = None,
      teammateName: Option[String] = None,
      teamName: Option[String] = None,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for CwdChanged hook - when the working directory changes */
  final case class CwdChanged(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      oldCwd: String,
      newCwd: String,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
  ) extends HookInput

  /** Input for FileChanged hook - when a watched file changes */
  final case class FileChanged(
      sessionId: SessionId,
      cwd: String,
      transcriptPath: String,
      filePath: String,
      event: FileChangeEvent,
      hookAgentId: Option[SubagentId] = None,
      hookAgentType: Option[String] = None,
      permissionMode: Option[PermissionMode] = None
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
  given JsonEncoder[PermissionBehavior] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[PermissionBehavior] = StringEnumJsonCodec.decoderOrFail {
    case "allow" => Right(Allow)
    case "deny"  => Right(Deny)
    case "ask"   => Right(Ask)
    case other   => Left(s"Unknown permission behavior: $other")
  }

/** Reason for session exit */
enum ExitReason:
  case Clear, Resume, Logout, PromptInputExit, Other, BypassPermissionsDisabled
  case Custom(value: String)

  def toRaw: String = this match
    case Clear                      => "clear"
    case Resume                     => "resume"
    case Logout                     => "logout"
    case PromptInputExit            => "prompt_input_exit"
    case Other                      => "other"
    case BypassPermissionsDisabled  => "bypass_permissions_disabled"
    case Custom(v)                  => v

object ExitReason:
  given JsonEncoder[ExitReason] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[ExitReason] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): ExitReason = s match
    case "clear"                       => Clear
    case "resume"                      => Resume
    case "logout"                      => Logout
    case "prompt_input_exit"           => PromptInputExit
    case "other"                       => Other
    case "bypass_permissions_disabled" => BypassPermissionsDisabled
    case other                         => Custom(other)

/** Source of session start */
enum SessionStartSource:
  case Startup, Resume, Clear, Compact
  case Custom(value: String)

  def toRaw: String = this match
    case Startup  => "startup"
    case Resume   => "resume"
    case Clear    => "clear"
    case Compact  => "compact"
    case Custom(v) => v

object SessionStartSource:
  given JsonEncoder[SessionStartSource] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[SessionStartSource] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): SessionStartSource = s match
    case "startup" => Startup
    case "resume"  => Resume
    case "clear"   => Clear
    case "compact" => Compact
    case other     => Custom(other)

/** Trigger for setup hook */
enum SetupTrigger:
  case Init, Maintenance
  case Custom(value: String)

  def toRaw: String = this match
    case Init        => "init"
    case Maintenance => "maintenance"
    case Custom(v)   => v

object SetupTrigger:
  given JsonEncoder[SetupTrigger] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[SetupTrigger] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): SetupTrigger = s match
    case "init"        => Init
    case "maintenance" => Maintenance
    case other         => Custom(other)

/** Trigger for context compaction */
enum CompactTrigger:
  case Manual, Auto
  case Custom(value: String)

  def toRaw: String = this match
    case Manual    => "manual"
    case Auto      => "auto"
    case Custom(v) => v

object CompactTrigger:
  given JsonEncoder[CompactTrigger] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[CompactTrigger] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): CompactTrigger = s match
    case "manual" => Manual
    case "auto"   => Auto
    case other    => Custom(other)

/** Elicitation mode */
enum ElicitationMode:
  case Form, Url
  case Custom(value: String)

  def toRaw: String = this match
    case Form      => "form"
    case Url       => "url"
    case Custom(v) => v

object ElicitationMode:
  given JsonEncoder[ElicitationMode] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[ElicitationMode] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): ElicitationMode = s match
    case "form" => Form
    case "url"  => Url
    case other  => Custom(other)

/** Elicitation action result */
enum ElicitationAction:
  case Accept, Decline, Cancel
  case Custom(value: String)

  def toRaw: String = this match
    case Accept    => "accept"
    case Decline   => "decline"
    case Cancel    => "cancel"
    case Custom(v) => v

object ElicitationAction:
  given JsonEncoder[ElicitationAction] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[ElicitationAction] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): ElicitationAction = s match
    case "accept"  => Accept
    case "decline" => Decline
    case "cancel"  => Cancel
    case other     => Custom(other)

/** Memory type for instructions loaded hook */
enum MemoryType:
  case User, Project, Local, Managed
  case Custom(value: String)

  def toRaw: String = this match
    case User       => "User"
    case Project    => "Project"
    case Local      => "Local"
    case Managed    => "Managed"
    case Custom(v)  => v

object MemoryType:
  given JsonEncoder[MemoryType] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[MemoryType] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): MemoryType = s match
    case "User"    => User
    case "Project" => Project
    case "Local"   => Local
    case "Managed" => Managed
    case other     => Custom(other)

/** Load reason for instructions loaded hook */
enum InstructionsLoadReason:
  case SessionStart, NestedTraversal, PathGlobMatch, Include, Compact
  case Custom(value: String)

  def toRaw: String = this match
    case SessionStart    => "session_start"
    case NestedTraversal => "nested_traversal"
    case PathGlobMatch   => "path_glob_match"
    case Include         => "include"
    case Compact         => "compact"
    case Custom(v)       => v

object InstructionsLoadReason:
  given JsonEncoder[InstructionsLoadReason] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[InstructionsLoadReason] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): InstructionsLoadReason = s match
    case "session_start"    => SessionStart
    case "nested_traversal" => NestedTraversal
    case "path_glob_match"  => PathGlobMatch
    case "include"          => Include
    case "compact"          => Compact
    case other              => Custom(other)

/** Source of a configuration change */
enum ConfigChangeSource:
  case UserSettings, ProjectSettings, LocalSettings, PolicySettings, Skills
  case Custom(value: String)

  def toRaw: String = this match
    case UserSettings    => "user_settings"
    case ProjectSettings => "project_settings"
    case LocalSettings   => "local_settings"
    case PolicySettings  => "policy_settings"
    case Skills          => "skills"
    case Custom(v)       => v

object ConfigChangeSource:
  given JsonEncoder[ConfigChangeSource] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[ConfigChangeSource] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): ConfigChangeSource = s match
    case "user_settings"    => UserSettings
    case "project_settings" => ProjectSettings
    case "local_settings"   => LocalSettings
    case "policy_settings"  => PolicySettings
    case "skills"           => Skills
    case other              => Custom(other)

/** File change event type */
enum FileChangeEvent:
  case Change, Add, Unlink
  case Custom(value: String)

  def toRaw: String = this match
    case Change    => "change"
    case Add       => "add"
    case Unlink    => "unlink"
    case Custom(v) => v

object FileChangeEvent:
  given JsonEncoder[FileChangeEvent] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[FileChangeEvent] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): FileChangeEvent = s match
    case "change" => Change
    case "add"    => Add
    case "unlink" => Unlink
    case other    => Custom(other)
