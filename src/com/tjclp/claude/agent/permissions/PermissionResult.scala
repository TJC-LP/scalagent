package com.tjclp.claude.agent.permissions

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio.json._
import zio.json.ast.Json

/** Result of a permission decision.
  *
  * Returned from canUseTool handler to allow or deny tool execution.
  */
sealed trait PermissionResult:
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object

object PermissionResult:

  /** Allow the tool execution.
    *
    * @param updatedInput
    *   Optional modified input to use instead of original
    * @param updatedPermissions
    *   Optional permission updates to apply
    */
  final case class Allow(
      updatedInput: Option[Json] = None,
      updatedPermissions: List[PermissionUpdate] = Nil
  ) extends PermissionResult:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(behavior = "allow")
      updatedInput.foreach { input =>
        obj.updatedInput = js.JSON.parse(input.toJson)
      }
      if updatedPermissions.nonEmpty then
        obj.updatedPermissions = updatedPermissions.map(_.toRaw).toJSArray
      obj.asInstanceOf[js.Object]

  /** Deny the tool execution.
    *
    * @param message
    *   Message explaining why permission was denied
    * @param interrupt
    *   If true, stop the entire agent execution
    */
  final case class Deny(
      message: String,
      interrupt: Boolean = false
  ) extends PermissionResult:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        behavior = "deny",
        message = message
      )
      if interrupt then obj.interrupt = true
      obj.asInstanceOf[js.Object]

  // Convenience constructors

  /** Allow with no modifications */
  val allow: PermissionResult = Allow()

  /** Allow with modified input */
  def allowWith(updatedInput: Json): PermissionResult =
    Allow(updatedInput = Some(updatedInput))

  /** Allow and update permissions for future tools */
  def allowAndUpdate(updates: PermissionUpdate*): PermissionResult =
    Allow(updatedPermissions = updates.toList)

  /** Deny with message */
  def deny(message: String): PermissionResult = Deny(message)

  /** Deny and stop entire agent */
  def denyAndInterrupt(message: String): PermissionResult =
    Deny(message, interrupt = true)
