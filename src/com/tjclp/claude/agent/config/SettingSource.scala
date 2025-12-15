package com.tjclp.claude.agent.config

import zio.json._

/** Setting source for filesystem-based configuration loading.
  *
  * Controls which filesystem settings are loaded, including Skills, plugins, and slash commands.
  *
  *   - `User` - Global user settings (`~/.claude/settings.json`, `~/.claude/skills/`)
  *   - `Project` - Project settings (`.claude/settings.json`, `.claude/skills/`)
  *   - `Local` - Local settings (`.claude/settings.local.json`)
  *
  * When omitted or empty, no filesystem settings are loaded (SDK isolation mode).
  *
  * Example:
  * {{{
  * val options = AgentOptions.default
  *   .withSettingSources(SettingSource.User, SettingSource.Project)
  *   .withAllowedTools(ToolName.Skill, ToolName.Read)
  * }}}
  */
enum SettingSource(val raw: String):
  case User extends SettingSource("user")
  case Project extends SettingSource("project")
  case Local extends SettingSource("local")
  case Custom(override val raw: String) extends SettingSource(raw)

object SettingSource:
  def fromString(s: String): SettingSource = s match
    case "user"    => User
    case "project" => Project
    case "local"   => Local
    case other     => Custom(other)

  given JsonEncoder[SettingSource] = JsonEncoder[String].contramap(_.raw)
  given JsonDecoder[SettingSource] = JsonDecoder[String].map(fromString)

  /** Common preset: Load user and project settings (typical for Skills) */
  val userAndProject: List[SettingSource] = List(User, Project)

  /** Common preset: Load all settings */
  val all: List[SettingSource] = List(User, Project, Local)
