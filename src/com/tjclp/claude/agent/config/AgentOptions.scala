package com.tjclp.claude.agent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio._
import zio.json._
import com.tjclp.claude.agent.hooks._
import com.tjclp.claude.agent.permissions._
import com.tjclp.claude.agent.tools.ToolName

/** Configuration options for Claude Agent queries.
  *
  * This mirrors the TypeScript SDK's `Options` interface with type-safe improvements.
  */
final case class AgentOptions(
    // Core options
    model: Option[Model] = None,
    cwd: Option[String] = None,
    systemPrompt: Option[SystemPromptConfig] = None,

    // Tool configuration
    tools: Option[ToolsConfig] = None,
    allowedTools: Option[List[ToolName]] = None,
    disallowedTools: Option[List[ToolName]] = None,

    // Limits
    maxTurns: Option[Int] = None,
    maxBudgetUsd: Option[Double] = None,
    maxThinkingTokens: Option[Int] = None,

    // Permission handling
    permissionMode: Option[PermissionMode] = None,
    allowDangerouslySkipPermissions: Boolean = false,

    // MCP servers
    mcpServers: Map[String, McpServerConfig] = Map.empty,

    // Session management
    sessionMode: SessionMode = SessionMode.New,

    // Output
    outputFormat: Option[OutputFormat] = None,
    includePartialMessages: Boolean = false,

    // Advanced
    additionalDirectories: List[String] = Nil,
    env: Map[String, String] = Map.empty,

    // Beta features (e.g., "context-1m-2025-08-07")
    betaFeatures: List[String] = Nil,

    // Sandbox settings
    sandboxSettings: Option[SandboxSettings] = None,

    // Hooks
    hooks: Map[HookEvent, List[HookCallback]] = Map.empty,

    // Permission callback
    canUseTool: Option[CanUseTool] = None
):
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object =
    val obj = js.Dynamic.literal()

    model.foreach(m => obj.model = m.id)
    cwd.foreach(c => obj.cwd = c)
    systemPrompt.foreach(sp => obj.systemPrompt = sp.toRaw)
    tools.foreach(t => obj.tools = t.toRaw)
    allowedTools.foreach(at => obj.allowedTools = at.map(_.raw).toJSArray)
    disallowedTools.foreach(dt => obj.disallowedTools = dt.map(_.raw).toJSArray)
    maxTurns.foreach(mt => obj.maxTurns = mt)
    maxBudgetUsd.foreach(mb => obj.maxBudgetUsd = mb)
    maxThinkingTokens.foreach(mtt => obj.maxThinkingTokens = mtt)
    permissionMode.foreach(pm => obj.permissionMode = pm.toRaw)

    if allowDangerouslySkipPermissions then
      obj.allowDangerouslySkipPermissions = true

    if mcpServers.nonEmpty then
      obj.mcpServers = js.Dictionary(mcpServers.view.mapValues(_.toRaw).toSeq*)

    // Session mode handling
    sessionMode match
      case SessionMode.New      => () // Default, no flag needed
      case SessionMode.Continue => obj.continue = true
      case SessionMode.Resume(id) => obj.resume = id

    outputFormat.foreach(of => obj.outputFormat = of.toRaw)
    if includePartialMessages then obj.includePartialMessages = true

    if additionalDirectories.nonEmpty then
      obj.additionalDirectories = additionalDirectories.toJSArray

    if env.nonEmpty then
      obj.env = js.Dictionary(env.toSeq*)

    if betaFeatures.nonEmpty then
      obj.betaFeatures = betaFeatures.toJSArray

    sandboxSettings.foreach(ss => obj.sandbox = ss.toRaw)

    // Note: Hooks are converted separately in ClaudeAgent when calling query()
    // because they require a ZIO Runtime to bridge Scala→JS callbacks

    obj.asInstanceOf[js.Object]

  /** Convert hooks to raw JavaScript format.
    *
    * This requires a Runtime to bridge ZIO callbacks to JS functions.
    */
  def hooksToRaw(runtime: Runtime[Any]): js.Dictionary[js.Array[js.Function1[js.Dynamic, js.Promise[js.Object]]]] =
    if hooks.isEmpty then js.Dictionary()
    else
      js.Dictionary(
        hooks.toSeq.map { case (event, callbacks) =>
          event.toRaw -> callbacks.map(cb => HookCallback.toRawJs(cb, runtime)).toJSArray
        }*
      )

  /** Convert canUseTool to raw JavaScript format.
    *
    * This requires a Runtime to bridge ZIO callbacks to JS functions.
    */
  def canUseToolToRaw(
      runtime: Runtime[Any]
  ): js.UndefOr[js.Function3[String, js.Any, js.Dynamic, js.Promise[js.Object]]] =
    canUseTool.map(handler => CanUseTool.toRawJs(handler, runtime)).orUndefined

object AgentOptions:
  /** Default options (empty configuration) */
  val default: AgentOptions = AgentOptions()

  // Note: JSON codecs excluded because hooks contain functions that can't be serialized.
  // Use toRaw for SDK serialization instead.

  // Extension methods for fluent builder pattern
  extension (opts: AgentOptions)
    /** Set the model using type-safe Model enum */
    def withModel(m: Model): AgentOptions = opts.copy(model = Some(m))

    /** Set the model using a string ID (for custom/new models) */
    def withModelId(id: String): AgentOptions = opts.copy(model = Some(Model.fromId(id)))

    def withCwd(c: String): AgentOptions = opts.copy(cwd = Some(c))
    def withMaxTurns(n: Int): AgentOptions = opts.copy(maxTurns = Some(n))
    def withMaxBudgetUsd(b: Double): AgentOptions = opts.copy(maxBudgetUsd = Some(b))
    def withMaxThinkingTokens(t: Int): AgentOptions = opts.copy(maxThinkingTokens = Some(t))

    def withPermissionMode(pm: PermissionMode): AgentOptions =
      opts.copy(permissionMode = Some(pm))

    def withBypassPermissions: AgentOptions =
      opts.copy(
        permissionMode = Some(PermissionMode.BypassPermissions),
        allowDangerouslySkipPermissions = true
      )

    def withMcpServer(name: String, config: McpServerConfig): AgentOptions =
      opts.copy(mcpServers = opts.mcpServers + (name -> config))

    def withIncludePartialMessages: AgentOptions =
      opts.copy(includePartialMessages = true)

    /** Set the session mode */
    def withSessionMode(mode: SessionMode): AgentOptions =
      opts.copy(sessionMode = mode)

    /** Continue the current session */
    def withContinueSession: AgentOptions =
      opts.copy(sessionMode = SessionMode.Continue)

    /** Resume a specific session by ID */
    def withResume(sessionId: String): AgentOptions =
      opts.copy(sessionMode = SessionMode.Resume(sessionId))

    def withSystemPrompt(prompt: SystemPromptConfig): AgentOptions =
      opts.copy(systemPrompt = Some(prompt))

    def withTools(config: ToolsConfig): AgentOptions =
      opts.copy(tools = Some(config))

    /** Set allowed tools using type-safe ToolName enum */
    def withAllowedTools(tools: ToolName*): AgentOptions =
      opts.copy(allowedTools = Some(tools.toList))

    /** Set disallowed tools using type-safe ToolName enum */
    def withDisallowedTools(tools: ToolName*): AgentOptions =
      opts.copy(disallowedTools = Some(tools.toList))

    def withEnv(env: Map[String, String]): AgentOptions =
      opts.copy(env = env)

    def withAdditionalDirectories(dirs: String*): AgentOptions =
      opts.copy(additionalDirectories = dirs.toList)

    def withBetaFeatures(features: String*): AgentOptions =
      opts.copy(betaFeatures = features.toList)

    def withSandbox(settings: SandboxSettings): AgentOptions =
      opts.copy(sandboxSettings = Some(settings))

    /** Add a hook callback for the specified event */
    def withHook(event: HookEvent, callback: HookCallback): AgentOptions =
      val existing = opts.hooks.getOrElse(event, Nil)
      opts.copy(hooks = opts.hooks + (event -> (existing :+ callback)))

    /** Add multiple hooks for the specified event */
    def withHooks(event: HookEvent, callbacks: HookCallback*): AgentOptions =
      val existing = opts.hooks.getOrElse(event, Nil)
      opts.copy(hooks = opts.hooks + (event -> (existing ++ callbacks)))

    /** Block specific tools via PreToolUse hook.
      *
      * Example:
      * {{{
      * options.withBlockedTools(ToolName.Bash, ToolName.Write)
      * }}}
      */
    def withBlockedTools(toolNames: ToolName*): AgentOptions =
      opts.withHook(HookEvent.PreToolUse, HookCallback.blockTools(toolNames*))

    /** Auto-approve specific tools via PermissionRequest hook.
      *
      * Example:
      * {{{
      * options.withAutoApprovedTools(ToolName.Read, ToolName.Glob)
      * }}}
      */
    def withAutoApprovedTools(toolNames: ToolName*): AgentOptions =
      opts.withHook(HookEvent.PermissionRequest, HookCallback.autoApprove(toolNames*))

    /** Set a custom permission handler for tool execution */
    def withCanUseTool(handler: CanUseTool): AgentOptions =
      opts.copy(canUseTool = Some(handler))

    /** Allow only specific tools via canUseTool.
      *
      * Example:
      * {{{
      * options.withOnlyAllowedTools(ToolName.Read, ToolName.Glob, ToolName.Grep)
      * }}}
      */
    def withOnlyAllowedTools(toolNames: ToolName*): AgentOptions =
      opts.copy(canUseTool = Some(CanUseTool.allowOnly(toolNames*)))

    /** Deny specific tools via canUseTool.
      *
      * Example:
      * {{{
      * options.withDeniedTools(ToolName.Bash, ToolName.Write)
      * }}}
      */
    def withDeniedTools(toolNames: ToolName*): AgentOptions =
      opts.copy(canUseTool = Some(CanUseTool.denyTools(toolNames*)))

/** System prompt configuration */
enum SystemPromptConfig:
  /** Custom system prompt string */
  case Custom(prompt: String)

  /** Use a preset with optional append */
  case Preset(preset: String, append: Option[String] = None)

  /** Convert to raw JavaScript value */
  def toRaw: js.Any = this match
    case Custom(p) => p.asInstanceOf[js.Any]
    case Preset(preset, append) =>
      val obj = js.Dynamic.literal(`type` = "preset", preset = preset)
      append.foreach(a => obj.append = a)
      obj.asInstanceOf[js.Any]

object SystemPromptConfig:
  given JsonDecoder[SystemPromptConfig] = DeriveJsonDecoder.gen[SystemPromptConfig]
  given JsonEncoder[SystemPromptConfig] = DeriveJsonEncoder.gen[SystemPromptConfig]

  /** Claude Code preset */
  val claudeCode: SystemPromptConfig = Preset("claude_code")

  /** Claude Code preset with appended instructions */
  def claudeCodeWith(append: String): SystemPromptConfig =
    Preset("claude_code", Some(append))

/** Tools configuration */
enum ToolsConfig:
  /** Specific list of tool names */
  case Specific(tools: List[String])

  /** Use a preset tool configuration */
  case Preset(preset: String)

  /** Convert to raw JavaScript value */
  def toRaw: js.Any = this match
    case Specific(tools) => tools.toJSArray.asInstanceOf[js.Any]
    case Preset(p) =>
      js.Dynamic.literal(`type` = "preset", preset = p).asInstanceOf[js.Any]

object ToolsConfig:
  given JsonDecoder[ToolsConfig] = DeriveJsonDecoder.gen[ToolsConfig]
  given JsonEncoder[ToolsConfig] = DeriveJsonEncoder.gen[ToolsConfig]

  /** Claude Code tools preset */
  val claudeCode: ToolsConfig = Preset("claude_code")

  /** Only allow specific tools */
  def only(tools: String*): ToolsConfig = Specific(tools.toList)

/** Output format configuration for structured output */
final case class OutputFormat(schema: zio.json.ast.Json):
  /** Convert to raw JavaScript object */
  def toRaw: js.Object =
    js.Dynamic
      .literal(
        `type` = "json_schema",
        schema = js.JSON.parse(schema.toString)
      )
      .asInstanceOf[js.Object]

object OutputFormat:
  given JsonDecoder[OutputFormat] = DeriveJsonDecoder.gen[OutputFormat]
  given JsonEncoder[OutputFormat] = DeriveJsonEncoder.gen[OutputFormat]
