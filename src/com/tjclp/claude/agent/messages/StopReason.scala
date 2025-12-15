package com.tjclp.claude.agent.messages

import zio.json._

/** Reason why the assistant stopped generating a response.
  *
  * Indicates what caused Claude to stop producing output.
  */
enum StopReason:
  /** Natural end of turn - Claude finished its response */
  case EndTurn

  /** Hit maximum token limit */
  case MaxTokens

  /** Hit a stop sequence */
  case StopSequence

  /** Stopped to execute a tool */
  case ToolUse

  /** Custom/unknown stop reason for forward compatibility */
  case Custom(value: String)

  /** Convert to raw JavaScript string value */
  def toRaw: String = this match
    case EndTurn      => "end_turn"
    case MaxTokens    => "max_tokens"
    case StopSequence => "stop_sequence"
    case ToolUse      => "tool_use"
    case Custom(v)    => v

object StopReason:
  // JSON codecs
  given JsonEncoder[StopReason] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[StopReason] = JsonDecoder[String].map(fromString)

  /** Parse a stop reason from string, returning the appropriate enum case. */
  def fromString(s: String): StopReason = s match
    case "end_turn"      => EndTurn
    case "max_tokens"    => MaxTokens
    case "stop_sequence" => StopSequence
    case "tool_use"      => ToolUse
    case other           => Custom(other)
