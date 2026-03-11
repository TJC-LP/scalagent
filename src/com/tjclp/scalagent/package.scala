package com.tjclp

import zio.*
import zio.stream.*
import com.tjclp.scalagent.errors.*
import com.tjclp.scalagent.messages.*

/** Scala.JS facade for the Claude Agent SDK.
  *
  * This package provides a ZIO-based interface for interacting with the Claude Agent SDK. It offers:
  *
  *   - Type-safe message ADT (`AgentMessage`) matching the SDK's discriminated unions
  *   - Configuration builders (`AgentOptions`) with a fluent API
  *   - Streaming support via ZStream for consuming agent responses
  *   - Tool definition DSL for custom tools
  *   - Ergonomic extension methods for messages and streams
  *
  * == Quick Start ==
  *
  * {{{
  * import com.tjclp.scalagent.*
  * import zio.*
  *
  * object MyApp extends ZIOAppDefault:
  *   val run =
  *     ClaudeAgent.query("Hello, Claude!")
  *       .textOnly
  *       .tap(text => Console.printLine(text))
  *       .runDrain
  *       .provide(ClaudeAgent.live)
  * }}}
  *
  * == Configuration ==
  *
  * Use `AgentOptions` to configure queries:
  *
  * {{{
  * val options = AgentOptions.default
  *   .withModel("claude-sonnet-4-20250514")
  *   .withMaxTurns(10)
  *   .withPermissionMode(PermissionMode.AcceptEdits)
  * }}}
  *
  * == Message Types ==
  *
  * The `AgentMessage` enum includes:
  *   - `Assistant` - Claude's responses
  *   - `User` - User messages (including synthetic tool results)
  *   - `Result` - Final outcome (success or error)
  *   - `System` - System events (init, status, etc.)
  *   - `StreamEvent` - Partial streaming events
  *   - `ToolProgress` - Tool execution progress
  *
  * == Stream Combinators ==
  *
  * Extension methods on ZStream for ergonomic message processing:
  * {{{
  * stream.textOnly          // Extract only text content
  * stream.toolCallsOnly     // Extract only tool use requests
  * stream.untilResult       // Take messages until Result
  * stream.collectResult     // Collect all and return QueryResult
  * }}}
  *
  * @see
  *   [[ClaudeAgent]] for the main API
  * @see
  *   [[config.AgentOptions]] for configuration
  * @see
  *   [[messages.AgentMessage]] for message types
  */
package object scalagent {

  // The Agent SDK spawns a Claude Code subprocess. If _we_ are already running
  // inside Claude Code (e.g. an example invoked from a Claude session), the
  // subprocess will detect the CLAUDECODE env var and refuse to start with
  // "Claude Code cannot be launched inside another Claude Code session."
  // Clearing it here lets the SDK spawn freely.
  locally {
    val env = scala.scalajs.js.Dynamic.global.process.env
    if !scala.scalajs.js.isUndefined(env.CLAUDECODE) then
      scala.scalajs.js.special.delete(env, "CLAUDECODE")
  }

  /** Convenience type alias for the main service */
  type ClaudeAgentService = ClaudeAgent

  // ============================================================================
  // Re-exports for single-import convenience
  // Using type aliases and val references since Scala 3 export has limitations
  // ============================================================================

  // --- config package ---
  type AgentDefinition = config.AgentDefinition
  val AgentDefinition = config.AgentDefinition

  type AgentModel = config.AgentModel
  val AgentModel = config.AgentModel

  type AgentOptions = config.AgentOptions
  val AgentOptions = config.AgentOptions

  type CommandName = config.CommandName
  val CommandName = config.CommandName

  type McpServerConfig = config.McpServerConfig
  val McpServerConfig = config.McpServerConfig

  type Model = config.Model
  val Model = config.Model

  type OutputFormat = config.OutputFormat
  val OutputFormat = config.OutputFormat

  type OutputStyle = config.OutputStyle
  val OutputStyle = config.OutputStyle

  type PermissionMode = config.PermissionMode
  val PermissionMode = config.PermissionMode

  type PluginConfig = config.PluginConfig
  val PluginConfig = config.PluginConfig

  type PluginError = config.PluginError
  val PluginError = config.PluginError

  type PositiveDouble = config.PositiveDouble
  val PositiveDouble = config.PositiveDouble

  type PositiveInt = config.PositiveInt
  val PositiveInt = config.PositiveInt

  type SandboxSettings = config.SandboxSettings
  val SandboxSettings = config.SandboxSettings

  type SessionMode = config.SessionMode
  val SessionMode = config.SessionMode

  type SettingSource = config.SettingSource
  val SettingSource = config.SettingSource

  type SkillName = config.SkillName
  val SkillName = config.SkillName

  type StructuredOutput[A] = config.StructuredOutput[A]
  val StructuredOutput = config.StructuredOutput

  type SystemPromptConfig = config.SystemPromptConfig
  val SystemPromptConfig = config.SystemPromptConfig

  type ToolsConfig = config.ToolsConfig
  val ToolsConfig = config.ToolsConfig

  // --- messages package ---
  type AgentMessage = messages.AgentMessage
  val AgentMessage = messages.AgentMessage

  type ApiAssistantMessage = messages.ApiAssistantMessage
  val ApiAssistantMessage = messages.ApiAssistantMessage

  type ApiUserMessage = messages.ApiUserMessage
  val ApiUserMessage = messages.ApiUserMessage

  type ContentBlock = messages.ContentBlock
  val ContentBlock = messages.ContentBlock

  type ModelUsage = messages.ModelUsage
  val ModelUsage = messages.ModelUsage

  type ResultOutcome = messages.ResultOutcome
  val ResultOutcome = messages.ResultOutcome

  type Role = messages.Role
  val Role = messages.Role

  type StopReason = messages.StopReason
  val StopReason = messages.StopReason

  type StreamDelta = messages.StreamDelta
  val StreamDelta = messages.StreamDelta

  type RawStreamEvent = messages.RawStreamEvent
  val RawStreamEvent = messages.RawStreamEvent

  type SystemEvent = messages.SystemEvent
  val SystemEvent = messages.SystemEvent

  // ErrorReason is in messages package (in ResultOutcome.scala)
  type ErrorReason = messages.ErrorReason
  val ErrorReason = messages.ErrorReason

  // --- errors package ---
  type AgentError = errors.AgentError
  val AgentError = errors.AgentError

  // --- session package ---
  // ClaudeSession takes type parameters, export the companion object only
  val ClaudeSession = session.ClaudeSession

  // SessionState is a sealed trait without a companion val
  type SessionState = session.SessionState
  type Open = session.Open
  type Closed = session.Closed

  // --- tools package ---
  type ToolContent = tools.ToolContent
  val ToolContent = tools.ToolContent

  // ToolDef takes type parameters, export the companion object only
  val ToolDef = tools.ToolDef

  val ToolFiles = tools.ToolFiles

  // ToolInput is a type class trait with companion
  type ToolInput[A] = tools.ToolInput[A]
  val ToolInput = tools.ToolInput

  type ToolName = tools.ToolName
  val ToolName = tools.ToolName

  type ToolResult = tools.ToolResult
  val ToolResult = tools.ToolResult

  // --- types package ---
  type SessionId = types.SessionId
  val SessionId = types.SessionId

  type SessionUuid = types.SessionUuid
  val SessionUuid = types.SessionUuid

  type ToolUseId = types.ToolUseId
  val ToolUseId = types.ToolUseId

  type MessageUuid = types.MessageUuid
  val MessageUuid = types.MessageUuid

  type SubagentId = types.SubagentId
  val SubagentId = types.SubagentId

  type ApiMessageId = types.ApiMessageId
  val ApiMessageId = types.ApiMessageId

  // --- hooks package ---
  type CompactTrigger = hooks.CompactTrigger
  val CompactTrigger = hooks.CompactTrigger

  type ExitReason = hooks.ExitReason
  val ExitReason = hooks.ExitReason

  type SessionStartSource = hooks.SessionStartSource
  val SessionStartSource = hooks.SessionStartSource

  type SetupTrigger = hooks.SetupTrigger
  val SetupTrigger = hooks.SetupTrigger

  type ElicitationMode = hooks.ElicitationMode
  val ElicitationMode = hooks.ElicitationMode

  type ElicitationAction = hooks.ElicitationAction
  val ElicitationAction = hooks.ElicitationAction

  type ConfigChangeSource = hooks.ConfigChangeSource
  val ConfigChangeSource = hooks.ConfigChangeSource

  type HookCallback = hooks.HookCallback
  val HookCallback = hooks.HookCallback

  type HookEvent = hooks.HookEvent
  val HookEvent = hooks.HookEvent

  type HookConfig = hooks.HookConfig
  val HookConfig = hooks.HookConfig

  type HookInput = hooks.HookInput
  val HookInput = hooks.HookInput

  type HookOutput = hooks.HookOutput
  val HookOutput = hooks.HookOutput

  val HookPredicates = hooks.HookPredicates

  // --- permissions package ---
  type CanUseTool = permissions.CanUseTool
  val CanUseTool = permissions.CanUseTool

  type PermissionResult = permissions.PermissionResult
  val PermissionResult = permissions.PermissionResult

  type PermissionUpdate = permissions.PermissionUpdate
  val PermissionUpdate = permissions.PermissionUpdate

  type PermissionContext = permissions.PermissionContext
  val PermissionContext = permissions.PermissionContext

  // --- mcp package ---
  // McpServer and McpTool are objects only (no types)
  val McpServer = mcp.McpServer
  val McpTool = mcp.McpTool

  type McpToolName = mcp.McpToolName
  val McpToolName = mcp.McpToolName

  // --- a2a package ---
  type A2AClient = a2a.A2AClient
  val A2AClient = a2a.A2AClient

  type A2AServer = a2a.A2AServer
  val A2AServer = a2a.A2AServer

  // These are objects only (no types)
  val A2ATool = a2a.A2ATool
  val A2ARequest = a2a.A2ARequest
  val A2AResponse = a2a.A2AResponse

  // Re-export A2AClient extension methods for convenience
  export a2a.{sendText, streamText}

  type A2AError = a2a.A2AError
  val A2AError = a2a.A2AError

  type A2AMessage = a2a.A2AMessage
  val A2AMessage = a2a.A2AMessage

  type A2ATask = a2a.A2ATask
  val A2ATask = a2a.A2ATask

  type AgentCard = a2a.AgentCard
  val AgentCard = a2a.AgentCard

  // A2A types from various files
  type TaskId = a2a.TaskId
  val TaskId = a2a.TaskId

  type TaskState = a2a.TaskState
  val TaskState = a2a.TaskState

  type Part = a2a.Part
  val Part = a2a.Part

  type Artifact = a2a.Artifact
  val Artifact = a2a.Artifact

  type TaskStatus = a2a.TaskStatus
  val TaskStatus = a2a.TaskStatus

  type PushNotificationConfig = a2a.PushNotificationConfig
  val PushNotificationConfig = a2a.PushNotificationConfig

  type AgentCapabilities = a2a.AgentCapabilities
  val AgentCapabilities = a2a.AgentCapabilities

  type AgentProvider = a2a.AgentProvider
  val AgentProvider = a2a.AgentProvider

  type AgentSkill = a2a.AgentSkill
  val AgentSkill = a2a.AgentSkill

  type AgentExtension = a2a.AgentExtension
  val AgentExtension = a2a.AgentExtension

  type AgentCardSignature = a2a.AgentCardSignature
  val AgentCardSignature = a2a.AgentCardSignature

  type MessageSendConfiguration = a2a.MessageSendConfiguration
  val MessageSendConfiguration = a2a.MessageSendConfiguration

  // --- macros package ---
  type Tool = macros.Tool
  type Param = macros.Param
  type description = macros.description

  val ToolMacros = macros.ToolMacros

  // ============================================================================
  // Helper Functions
  // ============================================================================

  /** Extract text content from an assistant message's content blocks */
  def extractText(msg: messages.ApiAssistantMessage): String =
    msg.content.collect { case messages.ContentBlock.Text(text) => text }.mkString

  /** Extract all tool use requests from an assistant message */
  def extractToolUses(msg: messages.ApiAssistantMessage): List[messages.ContentBlock.ToolUse] =
    msg.content.collect { case tu: messages.ContentBlock.ToolUse => tu }

  // ============================================================================
  // ZStream Extension Methods for AgentMessage Streams
  // ============================================================================

  /** Extension methods for AgentMessage streams providing ergonomic message processing.
    *
    * These extensions work with any environment type R (not just Any), making them
    * compatible with service-based streams like ClaudeAgent.query().
    */
  extension [R](stream: ZStream[R, AgentError, AgentMessage])

    /** Extract only text content from messages.
      *
      * Filters to messages containing text and extracts the text content.
      *
      * Example:
      * {{{
      * ClaudeAgent.query("Hello")
      *   .textOnly
      *   .tap(text => Console.printLine(text))
      *   .runDrain
      * }}}
      */
    def textOnly: ZStream[R, AgentError, String] =
      stream.collect { case msg if msg.text.isDefined => msg.text.get }

    /** Extract only tool use requests from messages.
      *
      * Filters to assistant messages and extracts tool call requests.
      *
      * Example:
      * {{{
      * ClaudeAgent.query("Read the file")
      *   .toolCallsOnly
      *   .tap(tool => Console.printLine(s"Tool: ${tool.name}"))
      *   .runDrain
      * }}}
      */
    def toolCallsOnly: ZStream[R, AgentError, ContentBlock.ToolUse] =
      stream.flatMap(msg => ZStream.fromIterable(msg.toolCalls))

    /** Take messages until a Result message is received (inclusive).
      *
      * This is useful for multi-turn conversations where you want to process
      * one complete exchange before continuing.
      *
      * Example:
      * {{{
      * session.send("What is 2+2?")
      *   .untilResult
      *   .runCollect
      * }}}
      */
    def untilResult: ZStream[R, AgentError, AgentMessage] =
      stream.takeUntil(_.isResult)

    /** Collect all messages and return a QueryResult.
      *
      * Runs the stream to completion and constructs a QueryResult with
      * all messages and the final outcome.
      *
      * Example:
      * {{{
      * val result = ClaudeAgent.query("Hello")
      *   .collectResult
      *   .flatMap(r => Console.printLine(s"Cost: ${r.cost}"))
      * }}}
      */
    def collectResult(
        policy: CollectionPolicy = CollectionPolicy.Full,
        sink: QueryCollector.MessageSink = QueryCollector.noSink
    ): ZIO[R, AgentError, QueryResult] =
      QueryCollector.collect(stream, policy, sink)

    /** Extract only assistant messages from the stream.
      *
      * Filters to only AssistantMessage types, useful when you only
      * care about Claude's responses.
      */
    def assistantOnly: ZStream[R, AgentError, AgentMessage.Assistant] =
      stream.collect { case a: AgentMessage.Assistant => a }

    /** Extract only streaming events for real-time text display.
      *
      * Filters to StreamEvent messages and extracts text deltas,
      * useful for displaying text as it arrives.
      */
    def streamingText: ZStream[R, AgentError, String] =
      stream.collect {
        case AgentMessage.StreamEvent(event, _, _, _) =>
          event.delta.collect { case StreamDelta.TextDelta(t) => t }
      }.collect { case Some(text) => text }

    /** Log all messages as they pass through (for debugging).
      *
      * Example:
      * {{{
      * ClaudeAgent.query("Hello")
      *   .logMessages("msg")
      *   .runDrain
      * }}}
      */
    def logMessages(label: String): ZStream[R, AgentError, AgentMessage] =
      stream.tap(msg => ZIO.logInfo(s"[$label] $msg"))
}
