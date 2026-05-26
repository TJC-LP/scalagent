package com.tjclp.scalagent.messages

import com.tjclp.scalagent.json.StringEnumJsonCodec
import zio.json.*

/**
 * Message role in the conversation.
 *
 * Represents the role of a message sender (user, assistant, or system).
 */
enum Role:
  /** User message */
  case User

  /** Assistant (Claude) message */
  case Assistant

  /** System message */
  case System

  /** Custom/unknown role for forward compatibility */
  case Custom(value: String)

  /** Convert to raw JavaScript string value */
  def toRaw: String = this match
    case User      => "user"
    case Assistant => "assistant"
    case System    => "system"
    case Custom(v) => v

object Role:
  // JSON codecs
  given JsonEncoder[Role] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[Role] = StringEnumJsonCodec.decoder(fromString)

  /** Parse a role from string, returning the appropriate enum case. */
  def fromString(s: String): Role = s match
    case "user"      => User
    case "assistant" => Assistant
    case "system"    => System
    case other       => Custom(other)
