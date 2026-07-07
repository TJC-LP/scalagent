package com.tjclp.scalagent.messages

import com.tjclp.scalagent.json.StringEnumJsonCodec
import com.tjclp.scalagent.config.{CommandName, FastModeState, Model, OutputStyle, PermissionMode, SkillName}
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{MessageUuid, ToolUseId}
import zio.json.*

/** System-level events emitted during agent execution */
enum SystemEvent:
  /** Initial system event with session info */
  case Init(
    apiKeySource: ApiKeySource,
    claudeCodeVersion: String,
    cwd: String,
    tools: List[ToolName],
    mcpServers: List[McpServerStatus],
    model: Model,
    permissionMode: PermissionMode,
    slashCommands: List[CommandName],
    outputStyle: OutputStyle,
    skills: List[SkillName],
    plugins: List[PluginInfo],
    agents: Option[List[String]],
    betas: Option[List[String]],
    fastModeState: Option[FastModeState])

  /** Compact boundary event (context compaction) */
  case CompactBoundary(
    trigger: CompactTrigger,
    preTokens: Int)

  /** Status update */
  case Status(status: Option[SdkStatus], permissionMode: Option[PermissionMode] = None)

  /** Hook response event */
  case HookResponse(
    hookId: String,
    hookName: String,
    hookEvent: String,
    stdout: String,
    stderr: String,
    output: String,
    exitCode: Option[Int],
    outcome: HookOutcome)

  /** Hook execution started */
  case HookStarted(
    hookId: String,
    hookName: String,
    hookEvent: String)

  /** Hook execution progress */
  case HookProgress(
    hookId: String,
    hookName: String,
    hookEvent: String,
    stdout: String,
    stderr: String,
    output: String)

  /**
   * File persistence tracking event (SDK 0.2.31).
   * Emitted when files are persisted or fail to persist.
   */
  case FilesPersisted(
    files: List[PersistedFile],
    failed: List[FailedFile],
    processedAt: String)

  /** Session state changed notification */
  case SessionStateChanged(
    state: SdkSessionState)

  /**
   * Memory recall event (SDK 0.2.105).
   * Emitted when the memory recall supervisor surfaces relevant memories into the turn.
   */
  case MemoryRecall(
    mode: MemoryRecallMode,
    memories: List[RecalledMemory])

  /**
   * Generic text banner emitted by the loop (SDK 0.3.201) — non-error status
   * lines, hook feedback (e.g. a UserPromptSubmit hook's block reason), and
   * slash-command output. Render `content` as plaintext at the given level.
   */
  case Informational(
    content: String,
    level: InformationalLevel,
    toolUseId: Option[ToolUseId] = None,
    preventContinuation: Boolean = false)

  /**
   * Emitted when a model refusal triggered a retry on the configured fallback
   * model (SDK 0.3.201). `retractedMessageUuids` is a resolution-time eviction
   * signal: remove those messages from transcript state on receipt.
   */
  case ModelRefusalFallback(
    originalModel: String,
    fallbackModel: String,
    content: String,
    requestId: Option[String] = None,
    apiRefusalCategory: Option[String] = None,
    apiRefusalExplanation: Option[String] = None,
    retractedMessageUuids: List[MessageUuid] = Nil,
    refusedUserMessageUuid: Option[MessageUuid] = None)

  /**
   * Emitted when the model ends the stream with stop_reason "refusal" and no
   * fallback model is configured, so the turn ends as an error (SDK 0.3.201).
   */
  case ModelRefusalNoFallback(
    originalModel: String,
    content: String,
    requestId: Option[String] = None,
    apiRefusalCategory: Option[String] = None,
    apiRefusalExplanation: Option[String] = None,
    refusedUserMessageUuid: Option[MessageUuid] = None)

  /**
   * Emitted on opt-in graceful worker teardown, before the heartbeat stops
   * (SDK 0.3.201). Treat as a live-tail signal only — a resumed session may
   * replay historical instances mid-stream.
   */
  case WorkerShuttingDown(
    reason: String)

  /** Forward-compatible fallback for unknown system events */
  case Unknown(
    envelope: UnknownEnvelope)
end SystemEvent

object SystemEvent:
  given JsonDecoder[SystemEvent] = DeriveJsonDecoder.gen[SystemEvent]
  given JsonEncoder[SystemEvent] = DeriveJsonEncoder.gen[SystemEvent]

/** SDK session state values (distinct from the session.SessionState phantom type) */
enum SdkSessionState:
  case Idle
  case Running
  case RequiresAction
  case Custom(value: String)

  def toRaw: String = this match
    case Idle           => "idle"
    case Running        => "running"
    case RequiresAction => "requires_action"
    case Custom(v)      => v

object SdkSessionState:
  given JsonEncoder[SdkSessionState] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[SdkSessionState] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): SdkSessionState = s match
    case "idle"            => Idle
    case "running"         => Running
    case "requires_action" => RequiresAction
    case other             => Custom(other)

/** Trigger for context compaction */
enum CompactTrigger:
  case Manual
  case Auto
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

/**
 * Render level for [[SystemEvent.Informational]] banners (SDK 0.3.201).
 * `Info` shows only in transcript mode; `Notice` renders in inactive gray;
 * `Suggestion` and `Warning` are more prominent.
 */
enum InformationalLevel:
  case Info
  case Notice
  case Suggestion
  case Warning
  case Custom(value: String)

  def toRaw: String = this match
    case Info       => "info"
    case Notice     => "notice"
    case Suggestion => "suggestion"
    case Warning    => "warning"
    case Custom(v)  => v

object InformationalLevel:
  given JsonEncoder[InformationalLevel] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[InformationalLevel] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): InformationalLevel = s match
    case "info"       => Info
    case "notice"     => Notice
    case "suggestion" => Suggestion
    case "warning"    => Warning
    case other        => Custom(other)

/** SDK status values */
enum SdkStatus:
  case Compacting
  case Requesting
  case Custom(value: String)

  def toRaw: String = this match
    case Compacting => "compacting"
    case Requesting => "requesting"
    case Custom(v)  => v

object SdkStatus:
  given JsonEncoder[SdkStatus] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[SdkStatus] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): SdkStatus = s match
    case "compacting" => Compacting
    case "requesting" => Requesting
    case other        => Custom(other)

/** Source of API key */
enum ApiKeySource:
  case User
  case Project
  case Org
  case Temporary
  case OAuth
  case Custom(value: String)

  def toRaw: String = this match
    case User      => "user"
    case Project   => "project"
    case Org       => "org"
    case Temporary => "temporary"
    case OAuth     => "oauth"
    case Custom(v) => v

object ApiKeySource:
  given JsonEncoder[ApiKeySource] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[ApiKeySource] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): ApiKeySource = s match
    case "user"      => User
    case "project"   => Project
    case "org"       => Org
    case "temporary" => Temporary
    case "oauth"     => OAuth
    case other       => Custom(other)

/** MCP server connection status */
final case class McpServerStatus(
  name: String,
  status: McpConnectionStatus,
  serverInfo: Option[McpServerInfo],
  error: Option[String] = None,
  scope: Option[String] = None,
  tools: Option[List[McpToolInfo]] = None)

object McpServerStatus:
  given JsonDecoder[McpServerStatus] = DeriveJsonDecoder.gen[McpServerStatus]
  given JsonEncoder[McpServerStatus] = DeriveJsonEncoder.gen[McpServerStatus]

/** MCP tool information from server status */
final case class McpToolInfo(
  name: String,
  description: Option[String] = None,
  annotations: Option[McpToolAnnotations] = None)

object McpToolInfo:
  given JsonDecoder[McpToolInfo] = DeriveJsonDecoder.gen[McpToolInfo]
  given JsonEncoder[McpToolInfo] = DeriveJsonEncoder.gen[McpToolInfo]

/** MCP tool annotations */
final case class McpToolAnnotations(
  readOnly: Option[Boolean] = None,
  destructive: Option[Boolean] = None,
  openWorld: Option[Boolean] = None)

object McpToolAnnotations:
  given JsonDecoder[McpToolAnnotations] = DeriveJsonDecoder.gen[McpToolAnnotations]
  given JsonEncoder[McpToolAnnotations] = DeriveJsonEncoder.gen[McpToolAnnotations]

/** MCP connection status */
enum McpConnectionStatus:
  case Connected
  case Failed
  case NeedsAuth
  case Pending
  case Disabled
  case Custom(value: String)

  def toRaw: String = this match
    case Connected => "connected"
    case Failed    => "failed"
    case NeedsAuth => "needs-auth"
    case Pending   => "pending"
    case Disabled  => "disabled"
    case Custom(v) => v

object McpConnectionStatus:
  given JsonEncoder[McpConnectionStatus] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[McpConnectionStatus] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): McpConnectionStatus = s match
    case "connected"  => Connected
    case "failed"     => Failed
    case "needs-auth" => NeedsAuth
    case "needs_auth" => NeedsAuth // Legacy format support
    case "pending"    => Pending
    case "disabled"   => Disabled
    case other        => Custom(other)

/** MCP server information */
final case class McpServerInfo(
  name: String,
  version: String)

object McpServerInfo:
  given JsonDecoder[McpServerInfo] = DeriveJsonDecoder.gen[McpServerInfo]
  given JsonEncoder[McpServerInfo] = DeriveJsonEncoder.gen[McpServerInfo]

/** Plugin information */
final case class PluginInfo(
  name: String,
  path: String)

object PluginInfo:
  given JsonDecoder[PluginInfo] = DeriveJsonDecoder.gen[PluginInfo]
  given JsonEncoder[PluginInfo] = DeriveJsonEncoder.gen[PluginInfo]

/** Hook outcome values */
enum HookOutcome:
  case Success
  case Error
  case Cancelled
  case Custom(value: String)

  def toRaw: String = this match
    case Success   => "success"
    case Error     => "error"
    case Cancelled => "cancelled"
    case Custom(v) => v

object HookOutcome:
  given JsonEncoder[HookOutcome] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[HookOutcome] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): HookOutcome = s match
    case "success"   => Success
    case "error"     => Error
    case "cancelled" => Cancelled
    case other       => Custom(other)

/**
 * Memory recall mode (SDK 0.2.105).
 * 'select' returns full file bodies; 'synthesize' returns a Sonnet-authored paragraph.
 */
enum MemoryRecallMode:
  case Select
  case Synthesize
  case Custom(value: String)

  def toRaw: String = this match
    case Select     => "select"
    case Synthesize => "synthesize"
    case Custom(v)  => v

object MemoryRecallMode:
  given JsonEncoder[MemoryRecallMode] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[MemoryRecallMode] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): MemoryRecallMode = s match
    case "select"     => Select
    case "synthesize" => Synthesize
    case other        => Custom(other)

/** Memory scope (SDK 0.2.105). */
enum MemoryScope:
  case Personal
  case Team
  case Custom(value: String)

  def toRaw: String = this match
    case Personal  => "personal"
    case Team      => "team"
    case Custom(v) => v

object MemoryScope:
  given JsonEncoder[MemoryScope] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[MemoryScope] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): MemoryScope = s match
    case "personal" => Personal
    case "team"     => Team
    case other      => Custom(other)

/** A memory recalled by the supervisor (SDK 0.2.105). */
final case class RecalledMemory(
  path: String,
  scope: MemoryScope,
  content: Option[String] = None)

object RecalledMemory:
  given JsonDecoder[RecalledMemory] = DeriveJsonDecoder.gen[RecalledMemory]
  given JsonEncoder[RecalledMemory] = DeriveJsonEncoder.gen[RecalledMemory]

/** Successfully persisted file info (SDK 0.2.31) */
final case class PersistedFile(
  filename: String,
  fileId: String)

object PersistedFile:
  given JsonDecoder[PersistedFile] = DeriveJsonDecoder.gen[PersistedFile]
  given JsonEncoder[PersistedFile] = DeriveJsonEncoder.gen[PersistedFile]

/** Failed file persistence info (SDK 0.2.31) */
final case class FailedFile(
  filename: String,
  error: String)

object FailedFile:
  given JsonDecoder[FailedFile] = DeriveJsonDecoder.gen[FailedFile]
  given JsonEncoder[FailedFile] = DeriveJsonEncoder.gen[FailedFile]
