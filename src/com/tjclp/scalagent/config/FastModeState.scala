package com.tjclp.scalagent.config

import zio.json.*

/** Fast mode state: off, in cooldown after rate limit, or actively enabled. */
enum FastModeState:
  case Off
  case Cooldown
  case On
  case Custom(value: String)

  def toRaw: String = this match
    case Off        => "off"
    case Cooldown   => "cooldown"
    case On         => "on"
    case Custom(v)  => v

object FastModeState:
  given JsonEncoder[FastModeState] = JsonEncoder[String].contramap(_.toRaw)
  given JsonDecoder[FastModeState] = JsonDecoder[String].map(fromString)

  def fromString(s: String): FastModeState = s match
    case "off"      => Off
    case "cooldown" => Cooldown
    case "on"       => On
    case other      => Custom(other)
