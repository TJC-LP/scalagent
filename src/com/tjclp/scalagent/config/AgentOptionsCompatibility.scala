package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Compatibility shims for SDK features that are not yet available on top-level Options. */
object AgentOptionsCompatibility:

  private val InternalMainAgentPrefix = "__scalagent_preloaded_skills"

  def prepare(options: AgentOptions): AgentOptions =
    val normalizedOptions = normalizeSettingSources(options)

    if normalizedOptions.skills.isEmpty then normalizedOptions
    else
      augmentExistingMainAgent(normalizedOptions)
        .orElse(synthesizeInternalMainAgent(normalizedOptions))
        .getOrElse(injectResolvedSkills(normalizedOptions))

  private def normalizeSettingSources(options: AgentOptions): AgentOptions =
    if options.skills.nonEmpty && options.settingSources.isEmpty then
      options.copy(settingSources = SettingSource.userAndProject)
    else options

  private def augmentExistingMainAgent(options: AgentOptions): Option[AgentOptions] =
    options.agent.flatMap { agentName =>
      options.agents.get(agentName).map { definition =>
        options.copy(
          agents = options.agents.updated(
            agentName,
            definition.copy(skills = (definition.skills ++ options.skills.map(_.value)).distinct),
          )
        )
      }
    }

  private def synthesizeInternalMainAgent(options: AgentOptions): Option[AgentOptions] =
    Option.when(options.agent.isEmpty && options.systemPrompt.isEmpty) {
      val internalAgentName = nextInternalAgentName(options)
      options.copy(
        agents = options.agents + (
          internalAgentName -> AgentDefinition(
            description = "Internal scalagent compatibility agent for main-thread preloaded skills",
            prompt = "",
            skills = options.skills.map(_.value),
          )
        ),
        agent = Some(internalAgentName),
      )
    }

  private def injectResolvedSkills(options: AgentOptions): AgentOptions =
    val resolvedSkillDocs = resolveSkillDocs(options)
    if resolvedSkillDocs.isEmpty then options
    else
      val skillPrompt  = renderResolvedSkills(resolvedSkillDocs)
      val mergedPrompt = options.systemPrompt match
        case Some(SystemPromptConfig.Custom(prompt)) =>
          SystemPromptConfig.Custom(s"$prompt\n\n$skillPrompt")
        case Some(SystemPromptConfig.Preset(preset, append)) =>
          val mergedAppend =
            append.filter(_.nonEmpty).map(existing => s"$existing\n\n$skillPrompt").orElse(Some(skillPrompt))
          SystemPromptConfig.Preset(preset, mergedAppend)
        case None =>
          SystemPromptConfig.Preset("claude_code", Some(skillPrompt))

      options.copy(systemPrompt = Some(mergedPrompt))

  private def renderResolvedSkills(skills: List[ResolvedSkill]): String =
    val bodies = skills.map { skill => s"## Skill: ${skill.name.value}\n\n${skill.content.trim}" }

    (
      "Preloaded skills for this session. Treat these as already-loaded instructions and context. " +
        "Do not spend a runtime Skill tool turn reloading them."
    ) + "\n\n" + bodies.mkString("\n\n")

  private def resolveSkillDocs(options: AgentOptions): List[ResolvedSkill] =
    options.skills.flatMap(resolveSkillDoc(_, options))

  private def resolveSkillDoc(skill: SkillName, options: AgentOptions): Option[ResolvedSkill] =
    if skill.isPluginSkill then None
    else
      skillSearchRoots(options)
        .map(root => NodePath.join(root, skill.baseName, "SKILL.md"))
        .find(NodeCompatFs.existsSync)
        .map(path => ResolvedSkill(skill, path, NodeCompatFs.readFileSync(path, "utf8")))

  private def skillSearchRoots(options: AgentOptions): List[String] =
    options.settingSources.distinct.flatMap {
      case SettingSource.User      => Some(NodePath.join(NodeOs.homedir(), ".claude", "skills"))
      case SettingSource.Project   => Some(NodePath.join(resolveWorkingDirectory(options), ".claude", "skills"))
      case SettingSource.Local     => Some(NodePath.join(resolveWorkingDirectory(options), ".claude", "skills"))
      case SettingSource.Custom(_) => None
    }

  private def resolveWorkingDirectory(options: AgentOptions): String =
    options.cwd.getOrElse(NodeProcess.cwd())

  private def nextInternalAgentName(options: AgentOptions): String =
    Iterator
      .from(0)
      .map { index =>
        if index == 0 then InternalMainAgentPrefix
        else s"$InternalMainAgentPrefix-$index"
      }
      .find(name => !options.agents.contains(name))
      .getOrElse(InternalMainAgentPrefix)

  private final case class ResolvedSkill(
    name: SkillName,
    path: String,
    content: String)
end AgentOptionsCompatibility

@js.native
@JSImport("node:fs", JSImport.Namespace)
private object NodeCompatFs extends js.Object:
  def existsSync(path: String): Boolean                    = js.native
  def readFileSync(path: String, encoding: String): String = js.native

@js.native
@JSImport("node:os", JSImport.Namespace)
private object NodeOs extends js.Object:
  def homedir(): String = js.native

@js.native
@JSImport("node:path", JSImport.Namespace)
private object NodePath extends js.Object:
  def join(parts: String*): String = js.native

@js.native
@JSImport("node:process", JSImport.Namespace)
private object NodeProcess extends js.Object:
  def cwd(): String = js.native
