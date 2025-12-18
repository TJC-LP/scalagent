package com.tjclp.scalagent.permissions

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio.json._
import com.tjclp.scalagent.config.PermissionMode
import com.tjclp.scalagent.hooks.PermissionBehavior

/** Runtime permission updates that can be applied during execution.
  *
  * These allow dynamically modifying permission rules based on tool execution results.
  */
sealed trait PermissionUpdate:
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object

object PermissionUpdate:

  /** Add new permission rules */
  final case class AddRules(rules: List[PermissionRule]) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "addRules",
          rules = rules.map(_.toRaw).toJSArray
        )
        .asInstanceOf[js.Object]

  /** Replace all rules with new ones */
  final case class ReplaceRules(rules: List[PermissionRule]) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "replaceRules",
          rules = rules.map(_.toRaw).toJSArray
        )
        .asInstanceOf[js.Object]

  /** Remove rules matching criteria */
  final case class RemoveRules(toolNames: List[String]) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "removeRules",
          toolNames = toolNames.toJSArray
        )
        .asInstanceOf[js.Object]

  /** Change the permission mode */
  final case class SetMode(mode: PermissionMode) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "setMode",
          mode = mode.toRaw
        )
        .asInstanceOf[js.Object]

  /** Add additional directories for file access */
  final case class AddDirectories(paths: List[String]) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "addDirectories",
          paths = paths.toJSArray
        )
        .asInstanceOf[js.Object]

  /** Remove directories from allowed list */
  final case class RemoveDirectories(paths: List[String]) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "removeDirectories",
          paths = paths.toJSArray
        )
        .asInstanceOf[js.Object]

  // Convenience constructors

  /** Add a rule to allow a tool */
  def allowTool(toolName: String): PermissionUpdate =
    AddRules(List(PermissionRule(toolName, PermissionBehavior.Allow)))

  /** Add a rule to deny a tool */
  def denyTool(toolName: String): PermissionUpdate =
    AddRules(List(PermissionRule(toolName, PermissionBehavior.Deny)))

  /** Add multiple tool rules at once */
  def addRules(rules: PermissionRule*): PermissionUpdate =
    AddRules(rules.toList)

  /** Change to a different permission mode */
  def setMode(mode: PermissionMode): PermissionUpdate =
    SetMode(mode)

  /** Allow access to additional directories */
  def addDirs(paths: String*): PermissionUpdate =
    AddDirectories(paths.toList)

/** A permission rule for a specific tool.
  *
  * @param toolName
  *   Name of the tool this rule applies to
  * @param behavior
  *   Permission behavior (allow/deny/ask)
  * @param prefix
  *   Optional path prefix for file-based tools
  */
final case class PermissionRule(
    toolName: String,
    behavior: PermissionBehavior,
    prefix: Option[String] = None
):
  def toRaw: js.Object =
    val obj = js.Dynamic.literal(
      toolName = toolName,
      behavior = behavior.toRaw
    )
    prefix.foreach(p => obj.prefix = p)
    obj.asInstanceOf[js.Object]

object PermissionRule:
  given JsonDecoder[PermissionRule] = DeriveJsonDecoder.gen[PermissionRule]
  given JsonEncoder[PermissionRule] = DeriveJsonEncoder.gen[PermissionRule]

  /** Allow a tool */
  def allow(toolName: String): PermissionRule =
    PermissionRule(toolName, PermissionBehavior.Allow)

  /** Deny a tool */
  def deny(toolName: String): PermissionRule =
    PermissionRule(toolName, PermissionBehavior.Deny)

  /** Ask for permission for a tool */
  def ask(toolName: String): PermissionRule =
    PermissionRule(toolName, PermissionBehavior.Ask)

  /** Allow a tool with path prefix restriction */
  def allowWithPrefix(toolName: String, prefix: String): PermissionRule =
    PermissionRule(toolName, PermissionBehavior.Allow, Some(prefix))
