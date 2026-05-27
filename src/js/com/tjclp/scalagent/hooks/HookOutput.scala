package com.tjclp.scalagent.hooks

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*
import zio.json.ast.Json

/**
 * Output types for hook responses.
 *
 * Hooks can control agent behavior by returning different output types.
 */
sealed trait HookOutput:
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object

object HookOutput:

  /**
   * Continue execution normally.
   *
   * @param systemMessage
   *   Optional message to inject into the conversation context
   * @param suppressOutput
   *   Whether to hide any output from this hook
   */
  final case class Continue(
    systemMessage: Option[String] = None,
    suppressOutput: Boolean = false)
      extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(continue = true)
      systemMessage.foreach(msg => obj.systemMessage = msg)
      if suppressOutput then obj.suppressOutput = true
      obj.asInstanceOf[js.Object]

  /**
   * Block execution with a reason.
   *
   * @param reason
   *   Reason for blocking (shown to user/model)
   * @param stopReason
   *   Optional reason to stop the entire session
   */
  final case class Block(
    reason: String,
    stopReason: Option[String] = None)
      extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        continue = false,
        reason = reason,
      )
      stopReason.foreach(r => obj.stopReason = r)
      obj.asInstanceOf[js.Object]

  /**
   * Make a permission decision (for PermissionRequest hooks).
   *
   * @param approve
   *   Whether to approve the permission request
   * @param reason
   *   Optional reason for the decision
   * @param updatedInput
   *   Optional modified input to use instead
   */
  final case class Decision(
    approve: Boolean,
    reason: Option[String] = None,
    updatedInput: Option[Json] = None)
      extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        decision = if approve then "approve" else "block"
      )
      reason.foreach(r => obj.reason = r)
      updatedInput.foreach { input =>
        obj.hookSpecificOutput = js.Dynamic.literal(
          updatedInput = js.JSON.parse(input.toJson)
        )
      }
      obj.asInstanceOf[js.Object]

  /**
   * Modify tool input before execution (for PreToolUse hooks).
   *
   * @param updatedInput
   *   The modified input to use
   * @param systemMessage
   *   Optional message to inject
   */
  final case class ModifyInput(
    updatedInput: Json,
    systemMessage: Option[String] = None)
      extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        continue = true,
        hookSpecificOutput = js.Dynamic.literal(
          updatedInput = js.JSON.parse(updatedInput.toJson)
        ),
      )
      systemMessage.foreach(msg => obj.systemMessage = msg)
      obj.asInstanceOf[js.Object]

  /**
   * Request async processing with timeout.
   *
   * @param timeoutMs
   *   Maximum time to wait for async completion
   */
  final case class Async(
    timeoutMs: Int = 30000)
      extends HookOutput:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          async = true,
          asyncTimeout = timeoutMs,
        )
        .asInstanceOf[js.Object]

  /**
   * Return watch paths (for CwdChanged/FileChanged hooks).
   *
   * @param watchPaths
   *   Additional file paths to watch for changes
   * @param systemMessage
   *   Optional message to inject
   */
  final case class WatchPaths(
    watchPaths: List[String],
    systemMessage: Option[String] = None)
      extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        continue = true,
        hookSpecificOutput = js.Dynamic.literal(
          watchPaths = watchPaths.toJSArray
        ),
      )
      systemMessage.foreach(msg => obj.systemMessage = msg)
      obj.asInstanceOf[js.Object]

  /**
   * Return worktree path (for WorktreeCreate hooks).
   *
   * @param worktreePath
   *   Absolute path to the created worktree
   */
  final case class WorktreePath(
    worktreePath: String)
      extends HookOutput:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          continue = true,
          hookSpecificOutput = js.Dynamic.literal(
            worktreePath = worktreePath
          ),
        )
        .asInstanceOf[js.Object]

  /**
   * Retry a denied permission (for PermissionDenied hooks).
   *
   * @param retry
   *   Whether to retry the tool call
   */
  final case class Retry(
    retry: Boolean = true)
      extends HookOutput:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          continue = true,
          hookSpecificOutput = js.Dynamic.literal(
            retry = retry
          ),
        )
        .asInstanceOf[js.Object]

  /**
   * Session start output with optional initial message and watch paths.
   *
   * @param initialUserMessage
   *   Optional initial user message to inject
   * @param watchPaths
   *   Optional file paths to watch for changes
   * @param reloadSkills
   *   When true, re-discover skills from disk before the session resumes.
   *   Requires SDK 0.3.152+.
   * @param sessionTitle
   *   Override the session title from the hook. Skips automatic title
   *   generation. Requires SDK 0.3.152+.
   */
  final case class SessionStartOutput(
    initialUserMessage: Option[String] = None,
    watchPaths: List[String] = Nil,
    reloadSkills: Boolean = false,
    sessionTitle: Option[String] = None)
      extends HookOutput:
    def toRaw: js.Object =
      val specific = js.Dynamic.literal()
      initialUserMessage.foreach(msg => specific.initialUserMessage = msg)
      if watchPaths.nonEmpty then specific.watchPaths = watchPaths.toJSArray
      if reloadSkills then specific.reloadSkills = true
      sessionTitle.foreach(t => specific.sessionTitle = t)
      js.Dynamic
        .literal(
          continue = true,
          hookSpecificOutput = specific,
        )
        .asInstanceOf[js.Object]

  /**
   * Make a permission decision for a tool call (for PreToolUse hooks).
   *
   * Unlike `Block` (which stops the agent session), this denies the specific
   * tool call via `hookSpecificOutput.permissionDecision` and lets the agent
   * continue with alternative approaches.
   *
   * @param allow
   *   Whether to allow the tool call
   * @param reason
   *   Reason for the decision (shown to the model)
   * @param systemMessage
   *   Optional message to inject into the conversation
   */
  final case class ToolPermission(
    allow: Boolean,
    reason: Option[String] = None,
    systemMessage: Option[String] = None)
      extends HookOutput:
    def toRaw: js.Object =
      val specific = js.Dynamic.literal(
        hookEventName = "PreToolUse",
        permissionDecision = if allow then "allow" else "deny",
      )
      reason.foreach(r => specific.permissionDecisionReason = r)
      val obj = js.Dynamic.literal(hookSpecificOutput = specific)
      systemMessage.foreach(msg => obj.systemMessage = msg)
      obj.asInstanceOf[js.Object]

  // Convenience constructors

  /** Continue execution without any modifications */
  val continue: HookOutput = Continue()

  /** Continue with a system message injected */
  def continueWith(message: String): HookOutput = Continue(systemMessage = Some(message))

  /** Block execution with the given reason */
  def block(reason: String): HookOutput = Block(reason)

  /** Approve a permission request */
  val approve: HookOutput = Decision(approve = true)

  /** Approve with a reason */
  def approveWith(reason: String): HookOutput = Decision(approve = true, reason = Some(reason))

  /** Deny a permission request */
  def deny(reason: String): HookOutput = Decision(approve = false, reason = Some(reason))

  /** Continue with watch paths for CwdChanged/FileChanged hooks */
  def withWatchPaths(paths: List[String]): HookOutput = WatchPaths(paths)

  /** Allow a tool call (for PreToolUse hooks) */
  val allowTool: HookOutput = ToolPermission(allow = true)

  /** Deny a tool call with reason (for PreToolUse hooks) */
  def denyTool(reason: String): HookOutput =
    ToolPermission(allow = false, reason = Some(reason))

  /** Return worktree path for WorktreeCreate hooks */
  def worktreePath(path: String): HookOutput = WorktreePath(path)

  /** Retry a denied permission */
  val retry: HookOutput = Retry()
end HookOutput
