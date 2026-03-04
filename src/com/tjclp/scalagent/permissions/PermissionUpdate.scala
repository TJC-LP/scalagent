package com.tjclp.scalagent.permissions

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*
import com.tjclp.scalagent.config.PermissionMode
import com.tjclp.scalagent.hooks.PermissionBehavior

/** Runtime permission updates that can be applied during execution.
  *
  * Mirrors the SDK PermissionUpdate union.
  */
sealed trait PermissionUpdate:
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object

object PermissionUpdate:

  final case class AddRules(
      behavior: PermissionBehavior,
      rules: List[PermissionRule],
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "addRules",
          rules = rules.map(_.toRuleValueRaw).toJSArray,
          behavior = behavior.toRaw,
          destination = destination.toRaw
        )
        .asInstanceOf[js.Object]

  final case class ReplaceRules(
      behavior: PermissionBehavior,
      rules: List[PermissionRule],
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "replaceRules",
          rules = rules.map(_.toRuleValueRaw).toJSArray,
          behavior = behavior.toRaw,
          destination = destination.toRaw
        )
        .asInstanceOf[js.Object]

  final case class RemoveRules(
      behavior: PermissionBehavior,
      rules: List[PermissionRule],
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "removeRules",
          rules = rules.map(_.toRuleValueRaw).toJSArray,
          behavior = behavior.toRaw,
          destination = destination.toRaw
        )
        .asInstanceOf[js.Object]

  final case class SetMode(
      mode: PermissionMode,
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "setMode",
          mode = mode.toRaw,
          destination = destination.toRaw
        )
        .asInstanceOf[js.Object]

  final case class AddDirectories(
      directories: List[String],
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "addDirectories",
          directories = directories.toJSArray,
          destination = destination.toRaw
        )
        .asInstanceOf[js.Object]

  final case class RemoveDirectories(
      directories: List[String],
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ) extends PermissionUpdate:
    def toRaw: js.Object =
      js.Dynamic
        .literal(
          `type` = "removeDirectories",
          directories = directories.toJSArray,
          destination = destination.toRaw
        )
        .asInstanceOf[js.Object]

  /** Parse SDK suggestion/update object. */
  def fromRaw(raw: js.Dynamic): Option[PermissionUpdate] =
    val tpe = raw.`type`.asInstanceOf[js.UndefOr[String]].toOption.getOrElse("")
    val destination = parseDestination(raw.destination.asInstanceOf[js.UndefOr[String]].toOption)
      .getOrElse(PermissionUpdateDestination.Session)

    tpe match
      case "addRules" =>
        parseBehavior(raw.behavior.asInstanceOf[js.UndefOr[String]].toOption).map { behavior =>
          AddRules(
            behavior = behavior,
            rules = parseRules(raw, behavior),
            destination = destination
          )
        }
      case "replaceRules" =>
        parseBehavior(raw.behavior.asInstanceOf[js.UndefOr[String]].toOption).map { behavior =>
          ReplaceRules(
            behavior = behavior,
            rules = parseRules(raw, behavior),
            destination = destination
          )
        }
      case "removeRules" =>
        parseBehavior(raw.behavior.asInstanceOf[js.UndefOr[String]].toOption).map { behavior =>
          RemoveRules(
            behavior = behavior,
            rules = parseRules(raw, behavior),
            destination = destination
          )
        }
      case "setMode" =>
        raw.mode.asInstanceOf[js.UndefOr[String]].toOption.map { mode =>
          SetMode(
            mode = PermissionMode.fromString(mode),
            destination = destination
          )
        }
      case "addDirectories" =>
        Some(
          AddDirectories(
            directories = parseStringArray(raw, "directories", "paths"),
            destination = destination
          )
        )
      case "removeDirectories" =>
        Some(
          RemoveDirectories(
            directories = parseStringArray(raw, "directories", "paths"),
            destination = destination
          )
        )
      case _ => None

  /** Add a rule to allow a tool. */
  def allowTool(
      toolName: String,
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ): PermissionUpdate =
    AddRules(PermissionBehavior.Allow, List(PermissionRule.allow(toolName)), destination)

  /** Add a rule to deny a tool. */
  def denyTool(
      toolName: String,
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ): PermissionUpdate =
    AddRules(PermissionBehavior.Deny, List(PermissionRule.deny(toolName)), destination)

  /** Add multiple rules with the same behavior. */
  def addRules(
      behavior: PermissionBehavior,
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  )(rules: PermissionRule*): PermissionUpdate =
    AddRules(behavior, rules.toList, destination)

  /** Legacy convenience: behavior is inferred from the first rule (or Ask if empty). */
  def addRules(rules: PermissionRule*): PermissionUpdate =
    val behavior = rules.headOption.map(_.behavior).getOrElse(PermissionBehavior.Ask)
    val normalized = rules.toList.map(_.copy(behavior = behavior))
    AddRules(behavior, normalized, PermissionUpdateDestination.Session)

  /** Change to a different permission mode. */
  def setMode(
      mode: PermissionMode,
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  ): PermissionUpdate =
    SetMode(mode, destination)

  /** Allow access to additional directories. */
  def addDirs(
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  )(paths: String*): PermissionUpdate =
    AddDirectories(paths.toList, destination)

  /** Backwards-compatible overload targeting session destination. */
  def addDirs(paths: String*): PermissionUpdate =
    AddDirectories(paths.toList, PermissionUpdateDestination.Session)

  /** Remove access to directories. */
  def removeDirs(
      destination: PermissionUpdateDestination = PermissionUpdateDestination.Session
  )(paths: String*): PermissionUpdate =
    RemoveDirectories(paths.toList, destination)

  /** Backwards-compatible overload targeting session destination. */
  def removeDirs(paths: String*): PermissionUpdate =
    RemoveDirectories(paths.toList, PermissionUpdateDestination.Session)

  private def parseRules(raw: js.Dynamic, behavior: PermissionBehavior): List[PermissionRule] =
    val parsed = raw.rules.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].toOption.map(_.toList.map { r =>
      PermissionRule(
        toolName = r.toolName.asInstanceOf[String],
        behavior = behavior,
        prefix = r.ruleContent.asInstanceOf[js.UndefOr[String]].toOption
      )
    })

    parsed.getOrElse(parseStringArray(raw, "toolNames").map(name => PermissionRule(name, behavior)))

  private def parseBehavior(raw: Option[String]): Option[PermissionBehavior] =
    raw match
      case Some("allow") => Some(PermissionBehavior.Allow)
      case Some("deny")  => Some(PermissionBehavior.Deny)
      case Some("ask")   => Some(PermissionBehavior.Ask)
      case _             => None

  private def parseDestination(raw: Option[String]): Option[PermissionUpdateDestination] =
    raw match
      case Some("userSettings")    => Some(PermissionUpdateDestination.UserSettings)
      case Some("projectSettings") => Some(PermissionUpdateDestination.ProjectSettings)
      case Some("localSettings")   => Some(PermissionUpdateDestination.LocalSettings)
      case Some("session")         => Some(PermissionUpdateDestination.Session)
      case Some("cliArg")          => Some(PermissionUpdateDestination.CliArg)
      case _                       => None

  private def parseStringArray(raw: js.Dynamic, fields: String*): List[String] =
    fields.toList
      .flatMap(field => raw.selectDynamic(field).asInstanceOf[js.UndefOr[js.Array[String]]].toOption)
      .headOption
      .map(_.toList)
      .getOrElse(Nil)

/** A permission rule value used by SDK permission updates. */
final case class PermissionRule(
    toolName: String,
    behavior: PermissionBehavior,
    prefix: Option[String] = None
):
  def toRuleValueRaw: js.Object =
    val obj = js.Dynamic.literal(toolName = toolName)
    prefix.foreach(content => obj.ruleContent = content)
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

/** Destination layer where permission updates should be applied. */
enum PermissionUpdateDestination:
  case UserSettings
  case ProjectSettings
  case LocalSettings
  case Session
  case CliArg

  def toRaw: String = this match
    case UserSettings    => "userSettings"
    case ProjectSettings => "projectSettings"
    case LocalSettings   => "localSettings"
    case Session         => "session"
    case CliArg          => "cliArg"

object PermissionUpdateDestination:
  given JsonEncoder[PermissionUpdateDestination] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[PermissionUpdateDestination] = JsonDecoder[String].mapOrFail {
    case "userSettings"    => Right(PermissionUpdateDestination.UserSettings)
    case "projectSettings" => Right(PermissionUpdateDestination.ProjectSettings)
    case "localSettings"   => Right(PermissionUpdateDestination.LocalSettings)
    case "session"         => Right(PermissionUpdateDestination.Session)
    case "cliArg"          => Right(PermissionUpdateDestination.CliArg)
    case other             => Left(s"Unknown permission update destination: $other")
  }
