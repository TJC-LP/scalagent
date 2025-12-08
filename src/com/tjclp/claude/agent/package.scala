package com.tjclp.claude

/** Scala.JS facade for the Claude Agent SDK.
  *
  * This package provides a ZIO-based interface for interacting with the Claude Agent SDK. It offers:
  *
  *   - Type-safe message ADT (`AgentMessage`) matching the SDK's discriminated unions
  *   - Configuration builders (`AgentOptions`) with a fluent API
  *   - Streaming support via ZStream for consuming agent responses
  *   - Tool definition DSL for custom tools
  *
  * == Quick Start ==
  *
  * {{{
  * import com.tjclp.claude.agent._
  * import com.tjclp.claude.agent.config._
  * import com.tjclp.claude.agent.messages._
  * import zio._
  *
  * object MyApp extends ZIOAppDefault:
  *   val run =
  *     ClaudeAgent.query("Hello, Claude!")
  *       .tap {
  *         case AgentMessage.Assistant(msg, _, _, _, _) =>
  *           Console.printLine(extractText(msg))
  *         case _ => ZIO.unit
  *       }
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
  * @see
  *   [[ClaudeAgent]] for the main API
  * @see
  *   [[config.AgentOptions]] for configuration
  * @see
  *   [[messages.AgentMessage]] for message types
  */
package object agent {

  /** Convenience type alias for the main service */
  type ClaudeAgentService = ClaudeAgent

  /** Extract text content from an assistant message's content blocks */
  def extractText(msg: messages.ApiAssistantMessage): String =
    msg.content.collect { case messages.ContentBlock.Text(text) => text }.mkString

  /** Extract all tool use requests from an assistant message */
  def extractToolUses(msg: messages.ApiAssistantMessage): List[messages.ContentBlock.ToolUse] =
    msg.content.collect { case tu: messages.ContentBlock.ToolUse => tu }
}
