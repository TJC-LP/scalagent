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

  /** Convert to raw JavaScript string value */
  def toRaw: String = this match
    case Default           => "default"
    case AcceptEdits       => "acceptEdits"
    case BypassPermissions => "bypassPermissions"
    case Plan              => "plan"
    case DontAsk           => "dontAsk"

object PermissionMode:
  given JsonDecoder[PermissionMode] = DeriveJsonDecoder.gen[PermissionMode]
  given JsonEncoder[PermissionMode] = DeriveJsonEncoder.gen[PermissionMode]

  def fromString(s: String): PermissionMode = s match
    case "default"           => Default
    case "acceptEdits"       => AcceptEdits
    case "bypassPermissions" => BypassPermissions
    case "plan"              => Plan
    case "dontAsk"           => DontAsk
    case other => throw new IllegalArgumentException(s"Unknown permission mode: $other")
