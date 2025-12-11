package com.tjclp.claude.agent.hooks

import com.tjclp.claude.agent.tools.ToolName

/** Common predicate helpers for hook composition.
  *
  * Use these with the `when` and `unless` combinators to create targeted hooks.
  *
  * Example:
  * {{{
  * import HookPredicates._
  *
  * val securityHook = HookCallback.blockTools(ToolName.Bash)
  *   .when(isPreToolUse)
  *   .unless(forTool(ToolName.Read))
  *
  * val loggingHook = HookCallback.logging(println)
  *   .when(isToolEvent)
  * }}}
  */
object HookPredicates:

  // ============================================================================
  // Event Type Predicates
  // ============================================================================

  /** Matches PreToolUse events (before tool execution) */
  val isPreToolUse: HookInput => Boolean = {
    case _: HookInput.PreToolUse => true
    case _                       => false
  }

  /** Matches PostToolUse events (after successful tool execution) */
  val isPostToolUse: HookInput => Boolean = {
    case _: HookInput.PostToolUse => true
    case _                        => false
  }

  /** Matches PostToolUseFailure events (after failed tool execution) */
  val isPostToolUseFailure: HookInput => Boolean = {
    case _: HookInput.PostToolUseFailure => true
    case _                               => false
  }

  /** Matches PermissionRequest events */
  val isPermissionRequest: HookInput => Boolean = {
    case _: HookInput.PermissionRequest => true
    case _                              => false
  }

  /** Matches any tool-related event (PreToolUse, PostToolUse, PostToolUseFailure, PermissionRequest) */
  val isToolEvent: HookInput => Boolean = {
    case _: HookInput.PreToolUse        => true
    case _: HookInput.PostToolUse       => true
    case _: HookInput.PostToolUseFailure => true
    case _: HookInput.PermissionRequest => true
    case _                              => false
  }

  /** Matches SessionStart events */
  val isSessionStart: HookInput => Boolean = {
    case _: HookInput.SessionStart => true
    case _                         => false
  }

  /** Matches SessionEnd events */
  val isSessionEnd: HookInput => Boolean = {
    case _: HookInput.SessionEnd => true
    case _                       => false
  }

  /** Matches any session lifecycle event */
  val isSessionEvent: HookInput => Boolean = {
    case _: HookInput.SessionStart => true
    case _: HookInput.SessionEnd   => true
    case _                         => false
  }

  /** Matches SubagentStart events */
  val isSubagentStart: HookInput => Boolean = {
    case _: HookInput.SubagentStart => true
    case _                          => false
  }

  /** Matches SubagentStop events */
  val isSubagentStop: HookInput => Boolean = {
    case _: HookInput.SubagentStop => true
    case _                         => false
  }

  /** Matches any subagent event */
  val isSubagentEvent: HookInput => Boolean = {
    case _: HookInput.SubagentStart => true
    case _: HookInput.SubagentStop  => true
    case _                          => false
  }

  /** Matches Notification events */
  val isNotification: HookInput => Boolean = {
    case _: HookInput.Notification => true
    case _                         => false
  }

  /** Matches UserPromptSubmit events */
  val isUserPromptSubmit: HookInput => Boolean = {
    case _: HookInput.UserPromptSubmit => true
    case _                             => false
  }

  /** Matches Stop events */
  val isStop: HookInput => Boolean = {
    case _: HookInput.Stop => true
    case _                 => false
  }

  /** Matches PreCompact events */
  val isPreCompact: HookInput => Boolean = {
    case _: HookInput.PreCompact => true
    case _                       => false
  }

  // ============================================================================
  // Tool Name Predicates
  // ============================================================================

  /** Matches events for a specific tool.
    *
    * Example:
    * {{{
    * val onlyBash = hook.when(forTool(ToolName.Bash))
    * }}}
    */
  def forTool(tool: ToolName): HookInput => Boolean = { input =>
    extractToolName(input).contains(tool)
  }

  /** Matches events for any of the specified tools.
    *
    * Example:
    * {{{
    * val fileOps = hook.when(forTools(ToolName.Read, ToolName.Write, ToolName.Edit))
    * }}}
    */
  def forTools(tools: ToolName*): HookInput => Boolean = { input =>
    extractToolName(input).exists(tools.contains)
  }

  /** Matches events for file operation tools (Read, Write, Edit, Glob, Grep) */
  val isFileOperation: HookInput => Boolean = { input =>
    extractToolName(input).exists(ToolName.isFileOperation)
  }

  /** Matches events for dangerous tools (Bash, Write, Edit) */
  val isDangerousTool: HookInput => Boolean = { input =>
    extractToolName(input).exists(ToolName.isDangerous)
  }

  /** Matches events for read-only tools (Read, Glob, Grep, WebFetch, WebSearch) */
  val isReadOnlyTool: HookInput => Boolean = { input =>
    extractToolName(input).exists(ToolName.isReadOnly)
  }

  // ============================================================================
  // Compound Predicates
  // ============================================================================

  /** Combines predicates with AND logic */
  def and(p1: HookInput => Boolean, p2: HookInput => Boolean): HookInput => Boolean =
    input => p1(input) && p2(input)

  /** Combines predicates with OR logic */
  def or(p1: HookInput => Boolean, p2: HookInput => Boolean): HookInput => Boolean =
    input => p1(input) || p2(input)

  /** Negates a predicate */
  def not(p: HookInput => Boolean): HookInput => Boolean =
    input => !p(input)

  // ============================================================================
  // Extension Methods for Predicate Composition
  // ============================================================================

  extension (p: HookInput => Boolean)
    /** Combine with another predicate using AND */
    def &&(other: HookInput => Boolean): HookInput => Boolean =
      and(p, other)

    /** Combine with another predicate using OR */
    def ||(other: HookInput => Boolean): HookInput => Boolean =
      or(p, other)

    /** Negate this predicate */
    def unary_! : HookInput => Boolean =
      not(p)

  // ============================================================================
  // Helper Methods
  // ============================================================================

  /** Extract tool name from hook input if applicable */
  private def extractToolName(input: HookInput): Option[ToolName] =
    input match
      case e: HookInput.PreToolUse         => Some(e.toolName)
      case e: HookInput.PostToolUse        => Some(e.toolName)
      case e: HookInput.PostToolUseFailure => Some(e.toolName)
      case e: HookInput.PermissionRequest  => Some(e.toolName)
      case _                               => None
