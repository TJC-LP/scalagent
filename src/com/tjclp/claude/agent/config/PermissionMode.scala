package com.tjclp.claude.agent.config

import scala.scalajs.js
import zio.json._

/** Permission mode for tool execution.
  *
  * Controls how the SDK handles tool permissions during query execution.
  */
enum PermissionMode:
  /** Default mode - prompt user for each tool use */
  case Default

  /** Accept file edits automatically */
  case AcceptEdits

  /** Bypass all permission checks (dangerous) */
  case BypassPermissions

  /** Plan mode - generate plans without execution */
  case Plan

  /** Don't ask for permissions, deny if not pre-approved */
  case DontAsk

  /** Custom/unknown permission mode for forward compatibility */
  case Custom(value: String)

  /** Convert to raw JavaScript string value */
  def toRaw: String = this match
    case Default           => "default"
    case AcceptEdits       => "acceptEdits"
    case BypassPermissions => "bypassPermissions"
    case Plan              => "plan"
    case DontAsk           => "dontAsk"
    case Custom(v)         => v

object PermissionMode:
  // JSON codecs using string conversion (not derived - handles raw strings properly)
  given JsonEncoder[PermissionMode] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[PermissionMode] = JsonDecoder[String].map(fromString)

  def fromString(s: String): PermissionMode = s match
    case "default"           => Default
    case "acceptEdits"       => AcceptEdits
    case "bypassPermissions" => BypassPermissions
    case "plan"              => Plan
    case "dontAsk"           => DontAsk
    case other               => Custom(other)
