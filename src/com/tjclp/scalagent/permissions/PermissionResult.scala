package com.tjclp.scalagent.permissions

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.json.StringEnumJsonCodec
import com.tjclp.scalagent.types.ToolUseId

/** Classification of a permission decision. */
enum PermissionDecisionClassification:
  case UserTemporary
  case UserPermanent
  case UserReject
  case Custom(value: String)

  def toRaw: String = this match
    case UserTemporary => "user_temporary"
    case UserPermanent => "user_permanent"
    case UserReject    => "user_reject"
    case Custom(v)     => v

object PermissionDecisionClassification:
  given JsonEncoder[PermissionDecisionClassification] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[PermissionDecisionClassification] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): PermissionDecisionClassification = s match
    case "user_temporary" => UserTemporary
    case "user_permanent" => UserPermanent
    case "user_reject"    => UserReject
    case other            => Custom(other)

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
    * @param toolUseId
    *   Optional tool use ID this decision applies to
    * @param decisionClassification
    *   Optional classification of the permission decision
    */
  final case class Allow(
      updatedInput: Option[Json] = None,
      updatedPermissions: List[PermissionUpdate] = Nil,
      toolUseId: Option[ToolUseId] = None,
      decisionClassification: Option[PermissionDecisionClassification] = None
  ) extends PermissionResult:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(behavior = "allow")
      updatedInput.foreach { input =>
        obj.updatedInput = js.JSON.parse(input.toJson)
      }
      if updatedPermissions.nonEmpty then
        obj.updatedPermissions = updatedPermissions.map(_.toRaw).toJSArray
      toolUseId.foreach(id => obj.toolUseId = id.value)
      decisionClassification.foreach(dc => obj.decisionClassification = dc.toRaw)
      obj.asInstanceOf[js.Object]

  /** Deny the tool execution.
    *
    * @param message
    *   Message explaining why permission was denied
    * @param interrupt
    *   If true, stop the entire agent execution
    * @param toolUseId
    *   Optional tool use ID this decision applies to
    * @param decisionClassification
    *   Optional classification of the permission decision
    */
  final case class Deny(
      message: String,
      interrupt: Boolean = false,
      toolUseId: Option[ToolUseId] = None,
      decisionClassification: Option[PermissionDecisionClassification] = None
  ) extends PermissionResult:
    def toRaw: js.Object =
      val obj = js.Dynamic.literal(
        behavior = "deny",
        message = message
      )
      if interrupt then obj.interrupt = true
      toolUseId.foreach(id => obj.toolUseId = id.value)
      decisionClassification.foreach(dc => obj.decisionClassification = dc.toRaw)
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
