package com.tjclp.scalagent.hooks

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio.json._
import zio.json.ast.Json

/** Output types for hook responses.
  *
  * Hooks can control agent behavior by returning different output types.
  */
sealed trait HookOutput:
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object

object HookOutput:

  /** Continue execution normally.
    *
    * @param systemMessage
    *   Optional message to inject into the conversation context
    * @param suppressOutput
    *   Whether to hide any output from this hook
    */
  final case class Continue(
      systemMessage: Option[String] = None,
      suppressOutput: Boolean = false
  ) extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(continue = true)
      systemMessage.foreach(msg => obj.systemMessage = msg)
      if suppressOutput then obj.suppressOutput = true
      obj.asInstanceOf[js.Object]

  /** Block execution with a reason.
    *
    * @param reason
    *   Reason for blocking (shown to user/model)
    * @param stopReason
    *   Optional reason to stop the entire session
    */
  final case class Block(
      reason: String,
      stopReason: Option[String] = None
  ) extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        continue = false,
        reason = reason
      )
      stopReason.foreach(r => obj.stopReason = r)
      obj.asInstanceOf[js.Object]

  /** Make a permission decision (for PermissionRequest hooks).
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
      updatedInput: Option[Json] = None
  ) extends HookOutput:
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

  /** Modify tool input before execution (for PreToolUse hooks).
    *
    * @param updatedInput
    *   The modified input to use
    * @param systemMessage
    *   Optional message to inject
    */
  final case class ModifyInput(
      updatedInput: Json,
      systemMessage: Option[String] = None
  ) extends HookOutput:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        continue = true,
        hookSpecificOutput = js.Dynamic.literal(
          updatedInput = js.JSON.parse(updatedInput.toJson)
        )
      )
      systemMessage.foreach(msg => obj.systemMessage = msg)
      obj.asInstanceOf[js.Object]

  /** Request async processing with timeout.
    *
    * @param timeoutMs
    *   Maximum time to wait for async completion
    */
  final case class Async(
      timeoutMs: Int = 30000
  ) extends HookOutput:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          async = true,
          asyncTimeout = timeoutMs
        )
        .asInstanceOf[js.Object]

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
