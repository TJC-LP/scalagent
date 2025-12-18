package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.mcp.McpToolName

/** Subagent definition for specialized AI assistants.
  *
  * Subagents provide context isolation, parallelization, and specialized expertise. They can have restricted tool
  * access and use different models than the main agent.
  *
  * Example:
  * {{{
  * val reviewer = AgentDefinition(
  *   description = "Expert code reviewer for security and quality",
  *   prompt = "You are a code review specialist...",
  *   tools = Some(List(ToolName.Read, ToolName.Grep, ToolName.Glob)),
  *   model = Some(AgentModel.Sonnet)
  * )
  *
  * AgentOptions.default.withAgent("code-reviewer", reviewer)
  * }}}
  */
final case class AgentDefinition(
    /** Natural language description of when to use this agent */
    description: String,
    /** The agent's system prompt defining its role and behavior */
    prompt: String,
    /** Allowed tools (inherits all if None) */
    tools: Option[List[ToolName]] = None,
    /** Explicitly disallowed tools */
    disallowedTools: Option[List[ToolName]] = None,
    /** Model override for this agent */
    model: Option[AgentModel] = None,
    /** Inherit MCP tools from parent's mcpServers config.
      * If true (default), agent can use MCP tools defined at AgentOptions level. If false, agent only has access to
      * tools explicitly listed.
      */
    inheritMcpTools: Boolean = true
):
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object =
    val obj = js.Dynamic.literal(
      description = description,
      prompt = prompt
    )
    tools.foreach(t => obj.tools = t.map(_.raw).toJSArray)
    disallowedTools.foreach(dt => obj.disallowedTools = dt.map(_.raw).toJSArray)
    model.foreach(m => obj.model = m.raw)
    // inheritMcpTools is SDK default behavior (true), no flag needed
    // When false, rely on explicit tools whitelist
    obj.asInstanceOf[js.Object]

object AgentDefinition:
  /** Create a read-only analysis agent (Read, Grep, Glob only) */
  def readOnly(
      description: String,
      prompt: String,
      model: Option[AgentModel] = None
  ): AgentDefinition =
    AgentDefinition(
      description = description,
      prompt = prompt,
      tools = Some(List(ToolName.Read, ToolName.Grep, ToolName.Glob)),
      model = model
    )

  /** Create an agent with full tool access (inherits all) */
  def fullAccess(
      description: String,
      prompt: String,
      model: Option[AgentModel] = None
  ): AgentDefinition =
    AgentDefinition(
      description = description,
      prompt = prompt,
      tools = None, // Inherits all
      model = model
    )

  /** Create an agent with specific MCP tools allowed.
    *
    * Example:
    * {{{
    * val agent = AgentDefinition.withMcpTools(
    *   description = "Weather specialist",
    *   prompt = "You provide weather info..."
    * )(WeatherTools.getWeather, WeatherTools.getForecast)(ToolName.Read)
    * }}}
    */
  def withMcpTools(description: String, prompt: String, model: Option[AgentModel] = None)(
      mcpTools: McpToolName*
  )(builtinTools: ToolName*): AgentDefinition =
    AgentDefinition(
      description = description,
      prompt = prompt,
      tools = Some(builtinTools.toList ++ mcpTools.map(_.toToolName).toList),
      model = model
    )

  /** Create an agent that cannot use any MCP tools (only explicit builtin tools) */
  def noMcpTools(
      description: String,
      prompt: String,
      tools: ToolName*
  ): AgentDefinition =
    AgentDefinition(
      description = description,
      prompt = prompt,
      tools = Some(tools.toList),
      inheritMcpTools = false
    )

  given JsonEncoder[AgentDefinition] = DeriveJsonEncoder.gen[AgentDefinition]
  given JsonDecoder[AgentDefinition] = DeriveJsonDecoder.gen[AgentDefinition]
