package com.tjclp.scalagent.types

import zio.json.{JsonDecoder, JsonEncoder}

/** Type-safe opaque wrappers for string identifiers.
  *
  * These provide zero-cost compile-time type safety for different ID types,
  * preventing accidental mixing of IDs (e.g., passing a SessionId where a ToolUseId is expected).
  *
  * Example:
  * {{{
  * val sessionId: SessionId = SessionId("session-123")
  * val toolUseId: ToolUseId = ToolUseId("tool-456")
  *
  * // This would be a compile error:
  * // def needsSession(id: SessionId): Unit = ???
  * // needsSession(toolUseId)  // Error: type mismatch
  * }}}
  */

/** Unique identifier for a Claude session.
  *
  * Can be either:
  *   - A UUID (e.g., "550e8400-e29b-41d4-a716-446655440000")
  *   - A human-readable name assigned via `/rename` command
  *
  * When used with `resume`, either format works. When used with `--session-id` CLI flag,
  * only valid UUIDs are accepted.
  *
  * Example:
  * {{{
  * // Resume by UUID
  * val byId = SessionId("550e8400-e29b-41d4-a716-446655440000")
  *
  * // Resume by human-readable name (assigned via /rename)
  * val byName = SessionId("my-feature-branch")
  * }}}
  */
opaque type SessionId = String
object SessionId:
  def apply(s: String): SessionId = s

  /** Create a SessionId from a human-readable name */
  def fromName(name: String): SessionId = name

  /** Create a SessionId from a UUID string */
  def fromUuid(uuid: String): SessionId = uuid

  extension (id: SessionId)
    def value: String = id
    def isEmpty: Boolean = value.isEmpty
    def nonEmpty: Boolean = !value.isEmpty

  // Use concrete instances (not implicit search) to avoid false "infinite loop" warnings
  given JsonEncoder[SessionId] = JsonEncoder.string.asInstanceOf[JsonEncoder[SessionId]]
  given JsonDecoder[SessionId] = JsonDecoder.string.asInstanceOf[JsonDecoder[SessionId]]

/** Unique identifier for a tool use request */
opaque type ToolUseId = String
object ToolUseId:
  def apply(s: String): ToolUseId = s
  extension (id: ToolUseId)
    def value: String = id
    def isEmpty: Boolean = value.isEmpty
    def nonEmpty: Boolean = !value.isEmpty

  given JsonEncoder[ToolUseId] = JsonEncoder.string.asInstanceOf[JsonEncoder[ToolUseId]]
  given JsonDecoder[ToolUseId] = JsonDecoder.string.asInstanceOf[JsonDecoder[ToolUseId]]

/** Unique identifier for a message */
opaque type MessageUuid = String
object MessageUuid:
  def apply(s: String): MessageUuid = s
  extension (id: MessageUuid)
    def value: String = id
    def isEmpty: Boolean = value.isEmpty

  given JsonEncoder[MessageUuid] = JsonEncoder.string.asInstanceOf[JsonEncoder[MessageUuid]]
  given JsonDecoder[MessageUuid] = JsonDecoder.string.asInstanceOf[JsonDecoder[MessageUuid]]

/** Unique identifier for a subagent */
opaque type SubagentId = String
object SubagentId:
  def apply(s: String): SubagentId = s
  extension (id: SubagentId)
    def value: String = id
    def isEmpty: Boolean = value.isEmpty

  given JsonEncoder[SubagentId] = JsonEncoder.string.asInstanceOf[JsonEncoder[SubagentId]]
  given JsonDecoder[SubagentId] = JsonDecoder.string.asInstanceOf[JsonDecoder[SubagentId]]

/** API-level message identifier */
opaque type ApiMessageId = String
object ApiMessageId:
  def apply(s: String): ApiMessageId = s
  extension (id: ApiMessageId)
    def value: String = id
    def isEmpty: Boolean = value.isEmpty

  given JsonEncoder[ApiMessageId] = JsonEncoder.string.asInstanceOf[JsonEncoder[ApiMessageId]]
  given JsonDecoder[ApiMessageId] = JsonDecoder.string.asInstanceOf[JsonDecoder[ApiMessageId]]
