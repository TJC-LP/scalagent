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

  /** Forward-compatible variant for unknown effort levels */
  case Custom(value: String)

  /** Convert to raw string for SDK */
  def toRaw: String = this match
    case Low       => "low"
    case Medium    => "medium"
    case High      => "high"
    case Max       => "max"
    case Custom(v) => v

object Effort:
  given JsonEncoder[Effort] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[Effort] = JsonDecoder[String].map(fromString)

  def fromString(s: String): Effort = s match
    case "low"    => Low
    case "medium" => Medium
    case "high"   => High
    case "max"    => Max
    case other    => Custom(other)
