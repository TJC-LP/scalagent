package com.tjclp

import zio.*
import zio.stream.*
import com.tjclp.scalagent.errors.*
import com.tjclp.scalagent.messages.*

/**
 * Scala.JS facade for the Claude Agent SDK.
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
package object scalagent:

  // The Agent SDK spawns a Claude Code subprocess. If _we_ are already running
  // inside Claude Code (e.g. an example invoked from a Claude session), the
  // subprocess will detect the CLAUDECODE env var and refuse to start with
  // "Claude Code cannot be launched inside another Claude Code session."
  // Clearing it here lets the SDK spawn freely.
  locally {
    val env = scala.scalajs.js.Dynamic.global.process.env
    if !scala.scalajs.js.isUndefined(env.CLAUDECODE) then scala.scalajs.js.special.delete(env, "CLAUDECODE")
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
  type Open         = session.Open
  type Closed       = session.Closed

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
  val McpTool   = mcp.McpTool

  type McpToolName = mcp.McpToolName
  val McpToolName = mcp.McpToolName

  // --- a2a package ---
  type A2AClient = a2a.A2AClient
  val A2AClient = a2a.A2AClient

  type A2AClientV03 = a2a.A2AClientV03
  val A2AClientV03 = a2a.A2AClientV03

  type A2AServer = a2a.A2AServer
  // `a2a.A2AServer` is now a pure trait without a companion `object A2AServer`.
  // JS-side server factory + Config live on `a2a.A2AServerLive`. Users who used
  // to call `A2AServer.start(config, runtime)` should call `A2AServerLive.start(...)`.
  val A2AServerLive = a2a.A2AServerLive
  type A2AServerApp[Self <: Singleton] = a2a.A2AServerApp[Self]

  type A2AServerV03 = a2a.A2AServerV03
  val A2AServerV03 = a2a.A2AServerV03
  type A2AServerAppV03[Self <: Singleton] = a2a.A2AServerAppV03[Self]

  type ExecutionMode = a2a.ExecutionMode
  val ExecutionMode = a2a.ExecutionMode

  // These are objects only (no types)
  val A2ATool     = a2a.A2ATool
  val A2ARequest  = a2a.A2ARequest
  val A2AResponse = a2a.A2AResponse
  type StreamResponse = a2a.A2AResponse.StreamResponse

  // Re-export A2AClient extension methods for convenience
  export a2a.{askText, sendAndPollText, sendText, streamText, submitText}

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

  type ContextId = a2a.ContextId
  val ContextId = a2a.ContextId

  type MessageId = a2a.MessageId
  val MessageId = a2a.MessageId

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

  type TaskPushNotificationConfig = a2a.TaskPushNotificationConfig
  val TaskPushNotificationConfig = a2a.TaskPushNotificationConfig

  type A2APushNotificationStore = a2a.A2APushNotificationStore
  val A2APushNotificationStore = a2a.A2APushNotificationStore

  type PushNotificationUrlPolicy = a2a.PushNotificationUrlPolicy
  val PushNotificationUrlPolicy = a2a.PushNotificationUrlPolicy

  type AuthenticationInfo = a2a.AuthenticationInfo
  val AuthenticationInfo = a2a.AuthenticationInfo

  type AgentCapabilities = a2a.AgentCapabilities
  val AgentCapabilities = a2a.AgentCapabilities

  type AgentInterface = a2a.AgentInterface
  val AgentInterface = a2a.AgentInterface

  type A2ATransport = a2a.A2ATransport
  val A2ATransport = a2a.A2ATransport

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

  type SecurityRequirement = a2a.SecurityRequirement
  val SecurityRequirement = a2a.SecurityRequirement

  type SecurityScheme = a2a.SecurityScheme
  val SecurityScheme = a2a.SecurityScheme

  val A2AProtocol = a2a.A2AProtocol
  val A2AHeader = a2a.A2AHeader
  val A2AContentType = a2a.A2AContentType

  // --- macros package ---
  type Tool        = macros.Tool
  type Param       = macros.Param
  type description = macros.description

  val ToolMacros = macros.ToolMacros

  // --- core DSL package ---
  // Agent takes type parameters, export companion object only
  val Agent = core.Agent

  type AgentRun[-R, +O] = core.AgentRun[R, O]
  val AgentRun = core.AgentRun

  type AgentEvent = core.AgentEvent
  val AgentEvent = core.AgentEvent

  type ExecutionPolicy = core.ExecutionPolicy
  val ExecutionPolicy = core.ExecutionPolicy

  type Budget = core.Budget
  val Budget = core.Budget

  type StopStrategy = core.StopStrategy
  val StopStrategy = core.StopStrategy

  type FallbackPolicy = core.FallbackPolicy
  val FallbackPolicy = core.FallbackPolicy

  type RunSummary = core.RunSummary
  val RunSummary = core.RunSummary

  type OutputCodec[O] = core.OutputCodec[O]
  val OutputCodec = core.OutputCodec

  // --- core DSL: utility and evaluation ---
  type TraceSummary = core.TraceSummary
  val TraceSummary = core.TraceSummary

  type Complexity = core.Complexity
  val Complexity = core.Complexity

  // Utility takes type parameters — export companion only
  val Utility = core.Utility

  type ScoreComponent = core.ScoreComponent
  val ScoreComponent = core.ScoreComponent

  type ScoreBreakdown = core.ScoreBreakdown
  val ScoreBreakdown = core.ScoreBreakdown

  type ReviewScore = core.ReviewScore
  val ReviewScore = core.ReviewScore

  // Reviewer takes type parameters — export companion only
  val Reviewer = core.Reviewer

  // --- core DSL: classified review ---
  type Visibility                           = core.Visibility
  type Public                               = core.Public
  type Internal                             = core.Internal
  type Secret                               = core.Secret
  type TopSecret                            = core.TopSecret
  type Classified[+A, L <: core.Visibility] = core.Classified[A, L]
  val Classified = core.Classified
  type CanSee[Viewer <: core.Visibility, Data <: core.Visibility] = core.CanSee[Viewer, Data]
  val CanSee = core.CanSee

  type Evaluation[P, O] = core.Evaluation[P, O]
  val Evaluation = core.Evaluation

  type TraceLogger = core.TraceLogger
  val TraceLogger = core.TraceLogger

  // --- core DSL: capability types ---
  type Depth              = core.Depth
  type Z                  = core.Z
  type S[N <: core.Depth] = core.S[N]
  type Depth0             = core.Depth0
  type Depth1             = core.Depth1
  type Depth2             = core.Depth2
  type Depth3             = core.Depth3

  type Capability                     = core.Capability
  type CanUseTools[T <: core.ToolSet] = core.CanUseTools[T]
  type CanSpawn[D <: core.Depth]      = core.CanSpawn[D]
  type CanReadMemory                  = core.CanReadMemory
  type CanWriteMemory                 = core.CanWriteMemory
  type CanEscalateHuman               = core.CanEscalateHuman
  type HasBudget                      = core.HasBudget
  type HasDirectoryScope              = core.HasDirectoryScope

  type DirectoryScope = core.DirectoryScope
  val DirectoryScope = core.DirectoryScope
  type BuilderConfig = core.BuilderConfig
  val BuilderConfig = core.BuilderConfig

  type ToolSet       = core.ToolSet
  type AllTools      = core.AllTools
  type ReadOnlyTools = core.ReadOnlyTools
  type CustomTools   = core.CustomTools

  type ReadOnlyCaps                    = core.ReadOnlyCaps
  type FullCaps                        = core.FullCaps
  type SupervisorCaps[D <: core.Depth] = core.SupervisorCaps[D]

  type ToolSurface = core.ToolSurface
  val ToolSurface = core.ToolSurface

  type DelegationPolicy = core.DelegationPolicy
  val DelegationPolicy = core.DelegationPolicy

  // TypedAgent and AgentBuilder take type parameters — export companions
  val TypedAgent   = core.TypedAgent
  val AgentBuilder = core.AgentBuilder

  // Type class evidence
  type HasSpawn[C] = core.HasSpawn[C]
  val HasSpawn = core.HasSpawn
  type HasToolsCap[C] = core.HasToolsCap[C]
  val HasToolsCap = core.HasToolsCap
  type DepthLTE[A <: core.Depth, B <: core.Depth] = core.DepthLTE[A, B]
  val DepthLTE = core.DepthLTE
  type DepthValue[D <: core.Depth] = core.DepthValue[D]
  val DepthValue = core.DepthValue

  // --- core DSL: A2A types ---
  type A2ARemoteAgent[-P, -I, +O] = core.a2a.A2ARemoteAgent[P, I, O]
  type A2AEndpoint                = core.a2a.A2AEndpoint
  type CanDelegateA2A             = core.a2a.CanDelegateA2A

  // --- core DSL: MCP types ---
  type McpToolSurface = core.mcp.McpToolSurface
  val McpToolSurface = core.mcp.McpToolSurface
  type McpResource = core.mcp.McpResource
  val McpResource = core.mcp.McpResource
  type McpResourceContent = core.mcp.McpResourceContent
  val McpResourceContent = core.mcp.McpResourceContent
  type McpPrompt = core.mcp.McpPrompt
  val McpPrompt = core.mcp.McpPrompt
  type McpPromptArgument = core.mcp.McpPromptArgument
  val McpPromptArgument = core.mcp.McpPromptArgument
  type HasMcpTools     = core.mcp.HasMcpTools
  type HasMcpResources = core.mcp.HasMcpResources
  type HasMcpPrompts   = core.mcp.HasMcpPrompts
  type FullMcpCaps     = core.mcp.FullMcpCaps

  // --- interop package ---
  val ClaudeInterpreter = interop.claude.ClaudeInterpreter
  val ClaudeEventMapper = interop.claude.EventMapper
  val A2AInterpreter    = interop.a2a.A2AInterpreter
  val A2AServerAdapter  = interop.a2a.A2AServerAdapter
  val McpToolLoader     = interop.mcp.McpToolLoader
  val CodexInterpreter  = interop.codex.CodexInterpreter
  val CodexEventMapper  = interop.codex.CodexEventMapper

  // --- Codex types ---
  type CodexClient = codex.CodexClient
  val CodexClient = codex.CodexClient
  type CodexThread        = codex.CodexThread
  type CodexClientOptions = codex.CodexClientOptions
  val CodexClientOptions = codex.CodexClientOptions
  type CodexConfigValue = codex.CodexConfigValue
  val CodexConfigValue = codex.CodexConfigValue
  type CodexInput         = codex.CodexInput
  type CodexThreadOptions = codex.CodexThreadOptions
  val CodexThreadOptions = codex.CodexThreadOptions
  type CodexTurnOptions = codex.CodexTurnOptions
  val CodexTurnOptions = codex.CodexTurnOptions
  type CodexInputItem = codex.CodexInputItem
  val CodexInputItem = codex.CodexInputItem
  type CodexEvent = codex.CodexEvent
  val CodexEvent = codex.CodexEvent
  type CodexItem = codex.CodexItem
  val CodexItem = codex.CodexItem
  type CodexTurn       = codex.CodexTurn
  type CodexUsage      = codex.CodexUsage
  type AbortSignal     = codex.AbortSignal
  type AbortController = codex.AbortController
  val AbortController = codex.AbortController
  type SandboxMode = codex.SandboxMode
  val SandboxMode = codex.SandboxMode
  type ApprovalMode = codex.ApprovalMode
  val ApprovalMode = codex.ApprovalMode
  type ModelReasoningEffort = codex.ModelReasoningEffort
  val ModelReasoningEffort = codex.ModelReasoningEffort
  type WebSearchMode = codex.WebSearchMode
  val WebSearchMode = codex.WebSearchMode

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

  /**
   * Extension methods for AgentMessage streams providing ergonomic message processing.
   *
   * These extensions work with any environment type R (not just Any), making them
   * compatible with service-based streams like ClaudeAgent.query().
   */
  extension [R](stream: ZStream[R, AgentError, AgentMessage])

    /**
     * Extract only text content from messages.
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

    /**
     * Extract only tool use requests from messages.
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

    /**
     * Take messages until a Result message is received (inclusive).
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

    /**
     * Collect all messages and return a QueryResult.
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
      sink: QueryCollector.MessageSink = QueryCollector.noSink,
    ): ZIO[R, AgentError, QueryResult] =
      QueryCollector.collect(stream, policy, sink)

    /**
     * Extract only assistant messages from the stream.
     *
     * Filters to only AssistantMessage types, useful when you only
     * care about Claude's responses.
     */
    def assistantOnly: ZStream[R, AgentError, AgentMessage.Assistant] =
      stream.collect { case a: AgentMessage.Assistant => a }

    /**
     * Extract only streaming events for real-time text display.
     *
     * Filters to StreamEvent messages and extracts text deltas,
     * useful for displaying text as it arrives.
     */
    def streamingText: ZStream[R, AgentError, String] =
      stream
        .collect {
          case AgentMessage.StreamEvent(event, _, _, _) =>
            event.delta.collect { case StreamDelta.TextDelta(t) => t }
        }
        .collect { case Some(text) => text }

    /**
     * Log all messages as they pass through (for debugging).
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
  end extension
end scalagent
