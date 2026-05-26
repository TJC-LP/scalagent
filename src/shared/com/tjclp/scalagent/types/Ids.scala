package com.tjclp.scalagent.types

import com.tjclp.scalagent.json.OpaqueStringJsonCodec
import zio.json.{JsonDecoder, JsonEncoder}

/**
 * Type-safe opaque wrappers for string identifiers.
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

/**
 * Unique identifier for a Claude session.
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
    def value: String     = id
    def isEmpty: Boolean  = value.isEmpty
    def nonEmpty: Boolean = !value.isEmpty

  given JsonEncoder[SessionId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[SessionId] = OpaqueStringJsonCodec.decoder(apply)

/**
 * Canonical UUID identifier for explicit session ID assignment.
 *
 * This is stricter than [[SessionId]], which can also represent human-readable
 * names assigned via `/rename`.
 */
opaque type SessionUuid = String
object SessionUuid:
  private val uuidPattern = "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
  private val uuidRegex   = uuidPattern.r

  def apply(uuid: String): Either[String, SessionUuid] =
    if isValid(uuid) then Right(uuid)
    else Left(s"sessionId must be a valid UUID, got: $uuid")

  def unsafe(uuid: String): SessionUuid = uuid

  def isValid(uuid: String): Boolean =
    uuidRegex.matches(uuid)

  extension (uuid: SessionUuid) def value: String = uuid

  given JsonEncoder[SessionUuid] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[SessionUuid] = OpaqueStringJsonCodec.decoderOrFail(apply)

/** Unique identifier for a tool use request */
opaque type ToolUseId = String
object ToolUseId:
  def apply(s: String): ToolUseId = s
  extension (id: ToolUseId)
    def value: String     = id
    def isEmpty: Boolean  = value.isEmpty
    def nonEmpty: Boolean = !value.isEmpty

  given JsonEncoder[ToolUseId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[ToolUseId] = OpaqueStringJsonCodec.decoder(apply)

/** Unique identifier for a message */
opaque type MessageUuid = String
object MessageUuid:
  def apply(s: String): MessageUuid = s
  extension (id: MessageUuid)
    def value: String    = id
    def isEmpty: Boolean = value.isEmpty

  given JsonEncoder[MessageUuid] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[MessageUuid] = OpaqueStringJsonCodec.decoder(apply)

/** Unique identifier for a subagent */
opaque type SubagentId = String
object SubagentId:
  def apply(s: String): SubagentId = s
  extension (id: SubagentId)
    def value: String    = id
    def isEmpty: Boolean = value.isEmpty

  given JsonEncoder[SubagentId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[SubagentId] = OpaqueStringJsonCodec.decoder(apply)

/** API-level message identifier */
opaque type ApiMessageId = String
object ApiMessageId:
  def apply(s: String): ApiMessageId = s
  extension (id: ApiMessageId)
    def value: String    = id
    def isEmpty: Boolean = value.isEmpty

  given JsonEncoder[ApiMessageId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[ApiMessageId] = OpaqueStringJsonCodec.decoder(apply)
