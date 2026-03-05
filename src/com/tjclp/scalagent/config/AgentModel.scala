package com.tjclp.scalagent.config

import com.tjclp.scalagent.json.StringEnumJsonCodec
import zio.json.*

/** Model selection for subagents.
  *
  * Controls which model a subagent uses. If omitted or Inherit, uses the main agent's model.
  *
  * Example:
  * {{{
  * val agent = AgentDefinition(
  *   description = "Code reviewer",
  *   prompt = "You review code...",
  *   model = Some(AgentModel.Sonnet)
  * )
  * }}}
  */
enum AgentModel(val raw: String):
  case Sonnet extends AgentModel("sonnet")
  case Opus extends AgentModel("opus")
  case Haiku extends AgentModel("haiku")
  case Inherit extends AgentModel("inherit")

object AgentModel:
  def fromString(s: String): AgentModel = s.toLowerCase match
    case "sonnet"  => Sonnet
    case "opus"    => Opus
    case "haiku"   => Haiku
    case "inherit" => Inherit
    case _         => Inherit // Default to inherit for unknown

  given JsonEncoder[AgentModel] = StringEnumJsonCodec.encoder(_.raw)
  given JsonDecoder[AgentModel] = StringEnumJsonCodec.decoder(fromString)
