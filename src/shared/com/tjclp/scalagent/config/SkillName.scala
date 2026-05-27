package com.tjclp.scalagent.config

import com.tjclp.scalagent.json.OpaqueStringJsonCodec
import zio.json.{JsonDecoder, JsonEncoder}

/**
 * Type-safe wrapper for skill names.
 *
 * Skills follow the naming pattern: "plugin-name:skill-name" for plugin skills, or just "skill-name" for user/project
 * skills.
 *
 * Example:
 * {{{
 * val pluginSkill = SkillName("document-skills:pdf")
 * val userSkill = SkillName.userSkill("my-custom-skill")
 *
 * // Extract parts
 * pluginSkill.isPluginSkill    // true
 * pluginSkill.pluginName       // Some("document-skills")
 * pluginSkill.baseName         // "pdf"
 * }}}
 */
opaque type SkillName = String

object SkillName:
  def apply(name: String): SkillName = name

  /** Create a skill name for a user-defined skill */
  def userSkill(name: String): SkillName = name

  /** Create a skill name for a plugin skill */
  def pluginSkill(pluginName: String, skillName: String): SkillName =
    s"$pluginName:$skillName"

  extension (name: SkillName)
    def value: String = name

    /** Check if this is a plugin skill (contains colon) */
    def isPluginSkill: Boolean = name.contains(":")

    /** Extract plugin name if this is a plugin skill */
    def pluginName: Option[String] =
      if isPluginSkill then Some(name.takeWhile(_ != ':'))
      else None

    /** Extract the base skill name (after colon for plugin skills, full name otherwise) */
    def baseName: String =
      if isPluginSkill then name.dropWhile(_ != ':').drop(1)
      else name

  given JsonEncoder[SkillName] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[SkillName] = OpaqueStringJsonCodec.decoder(apply)
end SkillName
