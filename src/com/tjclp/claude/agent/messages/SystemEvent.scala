package com.tjclp.claude.agent.messages

import zio.json._
import com.tjclp.claude.agent.config.{CommandName, Model, OutputStyle, PermissionMode, SkillName}
import com.tjclp.claude.agent.tools.ToolName

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
      betas: Option[List[String]]
  )

  /** Compact boundary event (context compaction) */
  case CompactBoundary(
      trigger: CompactTrigger,
      preTokens: Int
  )

  /** Status update */
  case Status(status: Option[SdkStatus])

  /** Hook response event */
  case HookResponse(
      hookName: String,
      hookEvent: String,
      stdout: String,
      stderr: String,
      exitCode: Option[Int]
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
  case Custom(value: String)

  def toRaw: String = this match
    case User       => "user"
    case Project    => "project"
    case Org        => "org"
    case Temporary  => "temporary"
    case Custom(v)  => v

object ApiKeySource:
  given JsonEncoder[ApiKeySource] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[ApiKeySource] = JsonDecoder[String].map(fromString)

  def fromString(s: String): ApiKeySource = s match
    case "user"      => User
    case "project"   => Project
    case "org"       => Org
    case "temporary" => Temporary
    case other       => Custom(other)

/** MCP server connection status */
final case class McpServerStatus(
    name: String,
    status: McpConnectionStatus,
    serverInfo: Option[McpServerInfo]
)

object McpServerStatus:
  given JsonDecoder[McpServerStatus] = DeriveJsonDecoder.gen[McpServerStatus]
  given JsonEncoder[McpServerStatus] = DeriveJsonEncoder.gen[McpServerStatus]

/** MCP connection status */
enum McpConnectionStatus:
  case Connected
  case Failed
  case NeedsAuth
  case Pending
  case Custom(value: String)

  def toRaw: String = this match
    case Connected  => "connected"
    case Failed     => "failed"
    case NeedsAuth  => "needs_auth"
    case Pending    => "pending"
    case Custom(v)  => v

object McpConnectionStatus:
  given JsonEncoder[McpConnectionStatus] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[McpConnectionStatus] = JsonDecoder[String].map(fromString)

  def fromString(s: String): McpConnectionStatus = s match
    case "connected"  => Connected
    case "failed"     => Failed
    case "needs_auth" => NeedsAuth
    case "pending"    => Pending
    case other        => Custom(other)

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
