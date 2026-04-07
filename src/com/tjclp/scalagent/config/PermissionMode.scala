package com.tjclp.scalagent.config

import com.tjclp.scalagent.json.StringEnumJsonCodec
import scala.scalajs.js
import zio.json.*

/** Permission mode for tool execution.
  *
  * Controls how the SDK handles tool permissions during query execution.
  *
  * Evaluation order: Hooks → Deny rules → Permission mode → Allow rules → canUseTool callback.
  *
  * @see
  *   [[https://docs.anthropic.com/en/docs/agent-sdk/permissions SDK Permissions docs]]
  */
enum PermissionMode:
  /** Default mode — unmatched tools trigger the canUseTool callback. */
  case Default

  /** Auto-accept file edits (Edit, Write) and filesystem commands (mkdir, rm, mv, cp).
    * Other tools still require normal permissions.
    */
  case AcceptEdits

  /** Bypass all permission checks. Hooks and deny rules still apply.
    * Use with caution — all subagents inherit this mode.
    */
  case BypassPermissions

  /** Plan mode — no tool execution, Claude plans without making changes. */
  case Plan

  /** Deny instead of prompting. Tools pre-approved by allowedTools or allow
    * rules run normally; everything else is denied. canUseTool is never called.
    */
  case DontAsk

  /** Model-classified approvals — the SDK uses a model classifier to approve
    * or deny each tool call automatically. No human prompting required.
    *
    * Ideal for programmatic agents that need smart per-tool decisions without
    * pre-listing every allowed tool (like DontAsk requires).
    *
    * Wire value: `"auto"` — matches the TypeScript SDK's `PermissionMode` union type.
    * @see [[https://docs.anthropic.com/en/docs/agent-sdk/permissions#available-modes SDK permission modes]]
    */
  case Auto

  /** Custom/unknown permission mode for forward compatibility */
  case Custom(value: String)

  /** Convert to raw JavaScript string value */
  def toRaw: String = this match
    case Default           => "default"
    case AcceptEdits       => "acceptEdits"
    case BypassPermissions => "bypassPermissions"
    case Plan              => "plan"
    case DontAsk           => "dontAsk"
    case Auto              => "auto"
    case Custom(v)         => v

object PermissionMode:
  // JSON codecs using string conversion (not derived - handles raw strings properly)
  given JsonEncoder[PermissionMode] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[PermissionMode] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): PermissionMode = s match
    case "default"           => Default
    case "acceptEdits"       => AcceptEdits
    case "bypassPermissions" => BypassPermissions
    case "plan"              => Plan
    case "dontAsk"           => DontAsk
    case "auto"              => Auto
    case other               => Custom(other)
