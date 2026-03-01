package com.tjclp.scalagent.config

import zio.json.*

/** Controls how much effort Claude puts into its response.
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

  /** Maximum effort (Opus 4.6 only) */
  case Max

  /** Convert to raw string for SDK */
  def toRaw: String = this match
    case Low    => "low"
    case Medium => "medium"
    case High   => "high"
    case Max    => "max"

object Effort:
  given JsonEncoder[Effort] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[Effort] = JsonDecoder[String].mapOrFail {
    case "low"    => Right(Low)
    case "medium" => Right(Medium)
    case "high"   => Right(High)
    case "max"    => Right(Max)
    case other    => Left(s"Unknown effort level: $other")
  }

  def fromString(s: String): Effort = s match
    case "low"    => Low
    case "medium" => Medium
    case "high"   => High
    case "max"    => Max
    case _        => High // default
