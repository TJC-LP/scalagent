package com.tjclp.claude.agent.config

import zio.json._

/** Output format style for Claude Agent SDK responses.
  *
  * Controls how the SDK formats output during execution.
  */
enum OutputStyle:
  /** Default text output */
  case Text

  /** JSON formatted output */
  case Json

  /** Streaming JSON output */
  case StreamJson

  /** Silent mode - minimal output */
  case Silent

  /** Custom/unknown output style for forward compatibility */
  case Custom(value: String)

  /** Convert to raw JavaScript string value */
  def toRaw: String = this match
    case Text       => "text"
    case Json       => "json"
    case StreamJson => "stream-json"
    case Silent     => "silent"
    case Custom(v)  => v

object OutputStyle:
  given JsonEncoder[OutputStyle] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[OutputStyle] = JsonDecoder[String].map(fromString)

  /** Parse an output style from string, returning the appropriate enum case. */
  def fromString(s: String): OutputStyle = s match
    case "text"        => Text
    case "json"        => Json
    case "stream-json" => StreamJson
    case "silent"      => Silent
    case other         => Custom(other)

  /** Default output style */
  val default: OutputStyle = Text
