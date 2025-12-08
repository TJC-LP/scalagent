package com.tjclp.claude.agent.messages

import zio.json._

/** System-level events emitted during agent execution */
enum SystemEvent:
  /** Initial system event with session info */
  case Init(
      apiKeySource: String, // Descriptive string like "/login managed key" or "ANTHROPIC_API_KEY"
      claudeCodeVersion: String,
      cwd: String,
      tools: List[String],
      mcpServers: List[McpServerStatus],
      model: String,
      permissionMode: String,
      slashCommands: List[String],
      outputStyle: String,
      skills: List[String],
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

object CompactTrigger:
  given JsonDecoder[CompactTrigger] = DeriveJsonDecoder.gen[CompactTrigger]
  given JsonEncoder[CompactTrigger] = DeriveJsonEncoder.gen[CompactTrigger]

  def fromString(s: String): CompactTrigger = s match
    case "manual" => Manual
    case "auto"   => Auto
    case other    => throw new IllegalArgumentException(s"Unknown compact trigger: $other")

/** SDK status values */
enum SdkStatus:
  case Compacting

object SdkStatus:
  given JsonDecoder[SdkStatus] = DeriveJsonDecoder.gen[SdkStatus]
  given JsonEncoder[SdkStatus] = DeriveJsonEncoder.gen[SdkStatus]

  def fromString(s: String): SdkStatus = s match
    case "compacting" => Compacting
    case other        => throw new IllegalArgumentException(s"Unknown SDK status: $other")

/** Source of API key */
enum ApiKeySource:
  case User
  case Project
  case Org
  case Temporary

object ApiKeySource:
  given JsonDecoder[ApiKeySource] = DeriveJsonDecoder.gen[ApiKeySource]
  given JsonEncoder[ApiKeySource] = DeriveJsonEncoder.gen[ApiKeySource]

  def fromString(s: String): ApiKeySource = s match
    case "user"      => User
    case "project"   => Project
    case "org"       => Org
    case "temporary" => Temporary
    case other       => throw new IllegalArgumentException(s"Unknown API key source: $other")

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

object McpConnectionStatus:
  given JsonDecoder[McpConnectionStatus] = DeriveJsonDecoder.gen[McpConnectionStatus]
  given JsonEncoder[McpConnectionStatus] = DeriveJsonEncoder.gen[McpConnectionStatus]

  def fromString(s: String): McpConnectionStatus = s match
    case "connected"  => Connected
    case "failed"     => Failed
    case "needs_auth" => NeedsAuth
    case "pending"    => Pending
    case other => throw new IllegalArgumentException(s"Unknown MCP connection status: $other")

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
