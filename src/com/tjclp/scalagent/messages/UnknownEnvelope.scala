package com.tjclp.scalagent.messages

import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.types.{MessageUuid, SessionId, ToolUseId}

/** Preserved raw metadata for unknown or forward-compatible SDK payloads. */
final case class UnknownEnvelope(
  raw: Json,
  rawType: String,
  rawSubtype: Option[String] = None,
  uuid: Option[MessageUuid] = None,
  sessionId: Option[SessionId] = None,
  parentToolUseId: Option[ToolUseId] = None)

object UnknownEnvelope:
  given JsonDecoder[UnknownEnvelope] = DeriveJsonDecoder.gen[UnknownEnvelope]
  given JsonEncoder[UnknownEnvelope] = DeriveJsonEncoder.gen[UnknownEnvelope]
