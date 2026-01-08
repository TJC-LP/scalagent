package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.mcp.McpToolName
import com.tjclp.scalagent.hooks.{HookCallback, HookConfig, HookEvent}

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
    inheritMcpTools: Boolean = true,
    /** Permission mode override for this agent.
      * Controls how this agent handles tool permission requests.
      * If None, inherits from parent AgentOptions.
      */
    permissionMode: Option[PermissionMode] = None,
    /** Hooks for this agent's lifecycle events.
      * Allows customizing agent behavior at specific points like PreToolUse, PostToolUse, etc.
      * Supports both shell command hooks (serializable) and callback hooks (runtime-only).
      * Added in Claude Code 2.1.0.
      */
    hooks: Map[HookEvent, List[HookConfig]] = Map.empty
):
  /** Convert to raw JavaScript object for SDK.
    *
    * Note: Hooks require a Runtime for conversion. Use `toRawWithHooks` when
    * agent has hooks configured.
    */
  def toRaw: js.Object =
    val obj = js.Dynamic.literal(
      description = description,
      prompt = prompt
    )
    tools.foreach(t => obj.tools = t.map(_.raw).toJSArray)
    disallowedTools.foreach(dt => obj.disallowedTools = dt.map(_.raw).toJSArray)
    model.foreach(m => obj.model = m.raw)
    permissionMode.foreach(pm => obj.permissionMode = pm.toRaw)
    // inheritMcpTools is SDK default behavior (true), no flag needed
    // When false, rely on explicit tools whitelist
    // Note: hooks are not included here - use toRawWithHooks when hooks are configured
    obj.asInstanceOf[js.Object]

  /** Convert to raw JavaScript object with hooks.
    *
    * This requires a Runtime to bridge ZIO callbacks to JS functions.
    */
  def toRawWithHooks(runtime: zio.Runtime[Any]): js.Object =
    val obj = toRaw.asInstanceOf[js.Dynamic]
    if hooks.nonEmpty then
      obj.hooks = js.Dictionary(
        hooks.toSeq.map { case (event, configs) =>
          event.toRaw -> configs.map(_.toRaw(runtime)).toJSArray
        }*
      )
    obj.asInstanceOf[js.Object]

  /** Check if this agent has hooks configured */
  def hasHooks: Boolean = hooks.nonEmpty

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

  // Note: Callback hooks contain functions that cannot be JSON serialized.
  // Shell hooks ARE serializable. Codecs handle both appropriately.
  given JsonEncoder[AgentDefinition] = JsonEncoder[zio.json.ast.Json].contramap { agent =>
    import zio.json.ast.Json
    var fields: List[(String, Json)] = List(
      "description" -> Json.Str(agent.description),
      "prompt" -> Json.Str(agent.prompt),
      "inheritMcpTools" -> Json.Bool(agent.inheritMcpTools)
    )
    agent.tools.foreach(t => fields = fields :+ ("tools" -> Json.Arr(t.map(tn => Json.Str(tn.raw))*)))
    agent.disallowedTools.foreach(dt =>
      fields = fields :+ ("disallowedTools" -> Json.Arr(dt.map(tn => Json.Str(tn.raw))*))
    )
    agent.model.foreach(m => fields = fields :+ ("model" -> Json.Str(m.raw)))
    agent.permissionMode.foreach(pm => fields = fields :+ ("permissionMode" -> Json.Str(pm.toRaw)))
    // Serialize hooks (shell hooks are fully serializable, callbacks get markers)
    if agent.hooks.nonEmpty then
      val hooksJson = agent.hooks.map { case (event, configs) =>
        event.toRaw -> Json.Arr(configs.map(_.toJson.fromJson[Json].toOption.get)*)
      }
      fields = fields :+ ("hooks" -> Json.Obj(zio.Chunk.fromIterable(hooksJson.toSeq)*))
    Json.Obj(zio.Chunk.fromIterable(fields)*)
  }

  given JsonDecoder[AgentDefinition] = JsonDecoder[zio.json.ast.Json].mapOrFail {
    case json: zio.json.ast.Json.Obj =>
      val fields = json.fields.toMap
      for
        description <- fields.get("description").flatMap(_.asString).toRight("Missing description")
        prompt <- fields.get("prompt").flatMap(_.asString).toRight("Missing prompt")
      yield
        // Parse hooks from JSON (only shell hooks can be restored)
        val hooksMap: Map[HookEvent, List[HookConfig]] = fields.get("hooks").flatMap(_.asObject) match
          case Some(hooksObj) =>
            hooksObj.fields.toMap.flatMap { case (eventRaw, configsJson) =>
              val event = HookEvent.fromString(eventRaw)
              val configs = configsJson.asArray.toList.flatten.flatMap { configJson =>
                configJson.as[HookConfig].toOption
              }
              if configs.nonEmpty then Some(event -> configs) else None
            }
          case None => Map.empty

        AgentDefinition(
          description = description,
          prompt = prompt,
          tools = fields.get("tools").flatMap(_.asArray).map(_.flatMap(_.asString).map(ToolName.fromString).toList),
          disallowedTools =
            fields.get("disallowedTools").flatMap(_.asArray).map(_.flatMap(_.asString).map(ToolName.fromString).toList),
          model = fields.get("model").flatMap(_.asString).map(AgentModel.fromString),
          inheritMcpTools = fields.get("inheritMcpTools").flatMap(_.asBoolean).getOrElse(true),
          permissionMode = fields.get("permissionMode").flatMap(_.asString).map(PermissionMode.fromString),
          hooks = hooksMap
        )
    case _ => Left("Expected JSON object")
  }

  // Extension methods for fluent builder pattern
  extension (agent: AgentDefinition)
    /** Add a permission mode override */
    def withPermissionMode(mode: PermissionMode): AgentDefinition =
      agent.copy(permissionMode = Some(mode))

    /** Add hooks for an event */
    def withHooks(event: HookEvent, configs: HookConfig*): AgentDefinition =
      val existing = agent.hooks.getOrElse(event, List.empty)
      agent.copy(hooks = agent.hooks + (event -> (existing ++ configs.toList)))

    /** Add a shell command hook */
    def withShellHook(event: HookEvent, matcher: String, command: String): AgentDefinition =
      withHooks(event, HookConfig.shell(matcher, command))

    /** Add a callback hook */
    def withCallbackHook(event: HookEvent, callback: HookCallback): AgentDefinition =
      withHooks(event, HookConfig.callback(callback))

    /** Add a callback hook with matcher */
    def withCallbackHook(event: HookEvent, matcher: String, callback: HookCallback): AgentDefinition =
      withHooks(event, HookConfig.callback(matcher, callback))
