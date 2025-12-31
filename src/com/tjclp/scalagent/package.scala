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

  /** Convenience type alias for the main service */
  type ClaudeAgentService = ClaudeAgent

  // ============================================================================
  // Re-exports for single-import convenience
  // ============================================================================

  // Config
  export config.{AgentOptions, Model, PermissionMode, SessionMode, StructuredOutput}
  export config.{McpServerConfig, OutputFormat, SystemPromptConfig, SandboxSettings}
  export config.{PluginConfig, AgentDefinition, AgentModel, SettingSource}

  // Messages
  export messages.{AgentMessage, ContentBlock, ResultOutcome, ErrorReason}
  export messages.{Role, StopReason, ModelUsage, PerModelUsage, SystemEvent}
  export messages.{ApiAssistantMessage, ApiUserMessage, StreamDelta, ImageSource}

  // Errors
  export errors.AgentError

  // Session
  export session.{ClaudeSession, SessionState, Open, Closed}

  // Tools
  export tools.{ToolDef, ToolInput, ToolResult, ToolContent, ToolName, JsonSchema, ToolFiles}

  // Types (ID wrappers)
  export types.{SessionId, ToolUseId, MessageUuid, SubagentId}

  // Hooks
  export hooks.{HookEvent, HookCallback, HookInput, HookOutput}

  // Permissions
  export permissions.{CanUseTool, PermissionResult}

  // MCP
  export mcp.{McpServer, McpTool}

  // A2A
  export a2a.{A2AClient, A2AServer, A2ATool, AgentCard, AgentSkill, A2AResponse, TaskId, MessageId}
  export a2a.{sendText, streamText}

  // Macro annotations
  export macros.{Tool, Param, description}

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
    def collectResult: ZIO[R, AgentError, QueryResult] =
      stream.runCollect.map { chunk =>
        val messages = chunk.toList
        val outcome = messages.collectFirst { case AgentMessage.Result(o, _, _) => o }
        QueryResult(
          messages,
          outcome.getOrElse(
            ResultOutcome.Error(
              reason = ErrorReason.DuringExecution,
              durationMs = 0,
              durationApiMs = 0,
              numTurns = 0,
              totalCostUsd = 0.0,
              usage = ModelUsage.empty,
              modelUsage = Map.empty,
              permissionDenials = Nil,
              errors = List("No result message received")
            )
          )
        )
      }

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
