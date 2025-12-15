package com.tjclp.claude.agent.types

import zio.json._

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

/** Unique identifier for a Claude session */
opaque type SessionId = String
object SessionId:
  def apply(s: String): SessionId = s
  extension (id: SessionId)
    def value: String = id
    def isEmpty: Boolean = id.value.isEmpty
    def nonEmpty: Boolean = id.value.nonEmpty

  // Use identity since opaque type is already String
  given JsonEncoder[SessionId] = JsonEncoder[String].contramap(identity)
  given JsonDecoder[SessionId] = JsonDecoder[String].map(apply)

/** Unique identifier for a tool use request */
opaque type ToolUseId = String
object ToolUseId:
  def apply(s: String): ToolUseId = s
  extension (id: ToolUseId)
    def value: String = id
    def isEmpty: Boolean = id.value.isEmpty
    def nonEmpty: Boolean = id.value.nonEmpty

  given JsonEncoder[ToolUseId] = JsonEncoder[String].contramap(identity)
  given JsonDecoder[ToolUseId] = JsonDecoder[String].map(apply)

/** Unique identifier for a message */
opaque type MessageUuid = String
object MessageUuid:
  def apply(s: String): MessageUuid = s
  extension (id: MessageUuid)
    def value: String = id
    def isEmpty: Boolean = id.value.isEmpty

  given JsonEncoder[MessageUuid] = JsonEncoder[String].contramap(identity)
  given JsonDecoder[MessageUuid] = JsonDecoder[String].map(apply)

/** Unique identifier for a subagent */
opaque type SubagentId = String
object SubagentId:
  def apply(s: String): SubagentId = s
  extension (id: SubagentId)
    def value: String = id
    def isEmpty: Boolean = id.value.isEmpty

  given JsonEncoder[SubagentId] = JsonEncoder[String].contramap(identity)
  given JsonDecoder[SubagentId] = JsonDecoder[String].map(apply)

/** API-level message identifier */
opaque type ApiMessageId = String
object ApiMessageId:
  def apply(s: String): ApiMessageId = s
  extension (id: ApiMessageId)
    def value: String = id
    def isEmpty: Boolean = id.value.isEmpty

  given JsonEncoder[ApiMessageId] = JsonEncoder[String].contramap(identity)
  given JsonDecoder[ApiMessageId] = JsonDecoder[String].map(apply)
