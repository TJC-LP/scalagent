package com.tjclp.scalagent.config

import com.tjclp.scalagent.json.StringEnumJsonCodec
import zio.json.*

/**
 * Controls how much effort Claude puts into its response.
 *
 * Works with adaptive thinking to guide thinking depth.
 */
enum Effort:
  /** Minimal thinking, fastest responses */
  case Low

  /** Moderate thinking */
  case Medium

  /** Deep reasoning (default) */
  case High

  /** Deeper than high. Supported by Opus 4.8 and Opus 4.7. */
  case XHigh

  /** Maximum effort. Supported by Opus 4.8, Opus 4.7, Opus 4.6, and Sonnet 4.6. */
  case Max

  /** Forward-compatible variant for unknown effort levels */
  case Custom(value: String)

  /** Convert to raw string for SDK */
  def toRaw: String = this match
    case Low       => "low"
    case Medium    => "medium"
    case High      => "high"
    case XHigh     => "xhigh"
    case Max       => "max"
    case Custom(v) => v
end Effort

object Effort:
  given JsonEncoder[Effort] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[Effort] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): Effort = s match
    case "low"    => Low
    case "medium" => Medium
    case "high"   => High
    case "xhigh"  => XHigh
    case "max"    => Max
    case other    => Custom(other)
