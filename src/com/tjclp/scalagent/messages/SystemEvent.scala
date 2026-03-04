package com.tjclp.scalagent.messages

import zio.json.*
import com.tjclp.scalagent.config.{CommandName, FastModeState, Model, OutputStyle, PermissionMode, SkillName}
import com.tjclp.scalagent.tools.ToolName

/** System-level events emitted during agent execution */
enum SystemEvent:
  /** Initial system event with session info */
  case Init(
      apiKeySource: String, // Descriptive string like "/login managed key" or "ANTHROPIC_API_KEY"
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
      fastModeState: Option[FastModeState]
  )

  /** Compact boundary event (context compaction) */
  case CompactBoundary(
      trigger: CompactTrigger,
      preTokens: Int
  )

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
      outcome: HookOutcome
  )

  /** Hook execution started */
  case HookStarted(
      hookId: String,
      hookName: String,
      hookEvent: String
  )

  /** Hook execution progress */
  case HookProgress(
      hookId: String,
      hookName: String,
      hookEvent: String,
      stdout: String,
      stderr: String,
      output: String
  )

  /** File persistence tracking event (SDK 0.2.31).
    * Emitted when files are persisted or fail to persist.
    */
  case FilesPersisted(
      files: List[PersistedFile],
      failed: List[FailedFile],
      processedAt: String
  )

object SystemEvent:
  given JsonDecoder[SystemEvent] = DeriveJsonDecoder.gen[SystemEvent]
  given JsonEncoder[SystemEvent] = DeriveJsonEncoder.gen[SystemEvent]

/** Trigger for context compaction */
enum CompactTrigger:
  case Manual
  case Auto
  case Custom(value: String)

  def toRaw: String = this match
    case Manual     => "manual"
    case Auto       => "auto"
    case Custom(v)  => v

object CompactTrigger:
  given JsonEncoder[CompactTrigger] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[CompactTrigger] = JsonDecoder[String].map(fromString)

  def fromString(s: String): CompactTrigger = s match
    case "manual" => Manual
    case "auto"   => Auto
    case other    => Custom(other)

/** SDK status values */
enum SdkStatus:
  case Compacting
  case Custom(value: String)

  def toRaw: String = this match
    case Compacting => "compacting"
    case Custom(v)  => v

object SdkStatus:
  given JsonEncoder[SdkStatus] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[SdkStatus] = JsonDecoder[String].map(fromString)

  def fromString(s: String): SdkStatus = s match
    case "compacting" => Compacting
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
    case User       => "user"
    case Project    => "project"
    case Org        => "org"
    case Temporary  => "temporary"
    case OAuth      => "oauth"
    case Custom(v)  => v

object ApiKeySource:
  given JsonEncoder[ApiKeySource] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[ApiKeySource] = JsonDecoder[String].map(fromString)

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
    tools: Option[List[McpToolInfo]] = None
)

object McpServerStatus:
  given JsonDecoder[McpServerStatus] = DeriveJsonDecoder.gen[McpServerStatus]
  given JsonEncoder[McpServerStatus] = DeriveJsonEncoder.gen[McpServerStatus]

/** MCP tool information from server status */
final case class McpToolInfo(
    name: String,
    description: Option[String] = None,
    annotations: Option[McpToolAnnotations] = None
)

object McpToolInfo:
  given JsonDecoder[McpToolInfo] = DeriveJsonDecoder.gen[McpToolInfo]
  given JsonEncoder[McpToolInfo] = DeriveJsonEncoder.gen[McpToolInfo]

/** MCP tool annotations */
final case class McpToolAnnotations(
    readOnly: Option[Boolean] = None,
    destructive: Option[Boolean] = None,
    openWorld: Option[Boolean] = None
)

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
    case Connected  => "connected"
    case Failed     => "failed"
    case NeedsAuth  => "needs-auth"
    case Pending    => "pending"
    case Disabled   => "disabled"
    case Custom(v)  => v

object McpConnectionStatus:
  given JsonEncoder[McpConnectionStatus] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[McpConnectionStatus] = JsonDecoder[String].map(fromString)

  def fromString(s: String): McpConnectionStatus = s match
    case "connected"   => Connected
    case "failed"      => Failed
    case "needs-auth"  => NeedsAuth
    case "needs_auth"  => NeedsAuth  // Legacy format support
    case "pending"     => Pending
    case "disabled"    => Disabled
    case other         => Custom(other)

/** MCP server information */
final case class McpServerInfo(
    name: String,
    version: String
)

object McpServerInfo:
  given JsonDecoder[McpServerInfo] = DeriveJsonDecoder.gen[McpServerInfo]
  given JsonEncoder[McpServerInfo] = DeriveJsonEncoder.gen[McpServerInfo]

/** Plugin information */
final case class PluginInfo(
    name: String,
    path: String
)

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
    case Success    => "success"
    case Error      => "error"
    case Cancelled  => "cancelled"
    case Custom(v)  => v

object HookOutcome:
  given JsonEncoder[HookOutcome] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[HookOutcome] = JsonDecoder[String].map(fromString)

  def fromString(s: String): HookOutcome = s match
    case "success"   => Success
    case "error"     => Error
    case "cancelled" => Cancelled
    case other       => Custom(other)

/** Successfully persisted file info (SDK 0.2.31) */
final case class PersistedFile(
    filename: String,
    fileId: String
)

object PersistedFile:
  given JsonDecoder[PersistedFile] = DeriveJsonDecoder.gen[PersistedFile]
  given JsonEncoder[PersistedFile] = DeriveJsonEncoder.gen[PersistedFile]

/** Failed file persistence info (SDK 0.2.31) */
final case class FailedFile(
    filename: String,
    error: String
)

object FailedFile:
  given JsonDecoder[FailedFile] = DeriveJsonDecoder.gen[FailedFile]
  given JsonEncoder[FailedFile] = DeriveJsonEncoder.gen[FailedFile]
