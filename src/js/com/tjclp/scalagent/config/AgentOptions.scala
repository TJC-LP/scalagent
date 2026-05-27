package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.annotation.targetName
import zio.*
import zio.json.*
import com.tjclp.scalagent.hooks.*
import com.tjclp.scalagent.mcp.McpToolName
import com.tjclp.scalagent.permissions.*
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{SessionId, SessionUuid}

/** Token budget for background tasks. */
final case class TaskBudget(total: Int)

/**
 * Configuration options for Claude Agent queries.
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
  /**
   * When false, disables session persistence to disk. Sessions will not be saved
   * to ~/.claude/projects/ and cannot be resumed later. Useful for ephemeral
   * or automated workflows where session history is not needed.
   * Default: true (sessions are persisted)
   */
  persistSession: Boolean = true,

  // Output
  outputFormat: Option[OutputFormat] = None,
  includePartialMessages: Boolean = false,

  // Advanced
  additionalDirectories: List[String] = Nil,
  /**
   * Environment variables for the Claude Code subprocess.
   *
   * As of SDK 0.2.113, passing a non-empty `env` map **replaces** the subprocess's
   * inherited `process.env` — to retain inherited variables, spread them explicitly
   * via `sys.env ++ Map("MY_VAR" -> "x")`.
   *
   * Notable subprocess-level env vars:
   *   - `MCP_CONNECTION_NONBLOCKING=0` — restore the pre-0.3.142 blocking MCP
   *     server connect behavior. Defaults to non-blocking otherwise; use
   *     [[McpServerConfig.Stdio.alwaysLoad]] (or per-server equivalents) to
   *     opt individual servers back into blocking startup.
   */
  env: Map[String, String] = Map.empty,

  /**
   * Custom session title. When provided, the session uses this title and skips
   * automatic title generation. Has no effect on the persisted title when resuming
   * an existing session. Requires SDK 0.2.113+.
   */
  title: Option[String] = None,

  // Beta features (e.g., "context-1m-2025-08-07")
  betaFeatures: List[String] = Nil,

  // Fallback model to use if primary fails
  fallbackModel: Option[Model] = None,

  // File checkpointing for rewind capability
  enableFileCheckpointing: Boolean = false,

  // Sandbox settings
  sandboxSettings: Option[SandboxSettings] = None,

  // Extra CLI arguments (for options not directly exposed)
  extraArgs: Map[String, Option[String]] = Map.empty,

  // Hooks
  hooks: Map[HookEvent, List[HookCallback]] = Map.empty,

  // Permission callback
  canUseTool: Option[CanUseTool] = None,

  // Setting sources for filesystem-based configuration (Skills, plugins, slash commands).
  // Empty is serialized as [] to preserve scalagent's isolated default on SDK 0.3.x.
  settingSources: List[SettingSource] = List.empty,

  // Plugins to load
  plugins: List[PluginConfig] = List.empty,

  // Preloaded main-thread skills
  skills: List[SkillName] = List.empty,

  // Subagents
  agents: Map[String, AgentDefinition] = Map.empty,

  // Agent name for the main thread (equivalent to --agent CLI flag)
  // The agent must be defined in the `agents` option or in settings
  agent: Option[String] = None,

  // Thinking/reasoning configuration
  /**
   * Controls Claude's thinking/reasoning behavior.
   * When set, takes precedence over the deprecated maxThinkingTokens.
   */
  thinking: Option[ThinkingConfig] = None,

  /**
   * Controls how much effort Claude puts into its response.
   * Works with adaptive thinking to guide thinking depth.
   */
  effort: Option[Effort] = None,

  /**
   * Enable prompt suggestions. When true, the agent emits a prompt_suggestion
   * message after each turn with a predicted next user prompt.
   */
  promptSuggestions: Boolean = false,

  /**
   * Use a specific session ID for the conversation instead of an auto-generated one.
   * Must be a valid UUID.
   */
  sessionId: Option[String] = None,

  // Debug controls
  /**
   * Enable debug mode for the Claude Code process.
   * When true, enables verbose debug logging (equivalent to `--debug` CLI flag).
   */
  debug: Boolean = false,

  /**
   * Write debug logs to a specific file path.
   * Implicitly enables debug mode.
   */
  debugFile: Option[String] = None,

  /**
   * Enforce strict validation of MCP server configurations.
   * When true, invalid configurations will cause errors instead of warnings.
   */
  strictMcpConfig: Boolean = false,

  /** Per-tool configuration (e.g., askUserQuestion preview format). */
  toolConfig: Option[ToolConfig] = None,

  /** Inline settings object or path to a settings file. */
  settings: Option[SettingsConfig] = None,

  /**
   * Embedder-provided policy-tier settings pushed in-memory to the spawned CLI,
   * merged on top of file-based settings without touching the filesystem.
   * Unlike [[settings]], managed settings must be inline because the SDK does
   * not load file paths for this option.
   * Requires SDK 0.2.118+.
   */
  managedSettings: Option[ManagedSettings] = None,

  // Runtime configuration

  /** Runtime executable to use for spawning Claude Code. */
  executable: Option[Executable] = None,

  /** Additional arguments to pass to the runtime executable. */
  executableArgs: List[String] = Nil,

  /** Path to the Claude Code executable binary. */
  pathToClaudeCodeExecutable: Option[String] = None,

  /** Route permission requests through an MCP tool instead of the default prompt. */
  permissionPromptToolName: Option[String] = None,

  /**
   * Callback for capturing stderr output from the Claude Code process.
   * This is a synchronous callback that does not require ZIO bridging.
   */
  stderr: Option[String => Unit] = None,

  /**
   * Enable periodic AI-generated progress summaries for running subagents.
   * When enabled, the subagent's conversation is forked every ~30 seconds to
   * produce a short present-tense description emitted on task_progress events.
   */
  agentProgressSummaries: Boolean = false,

  /**
   * When true, subagent text deltas are forwarded to the parent's stream as
   * normal assistant text events instead of being scoped to the subagent run.
   * Requires SDK 0.2.119+.
   */
  forwardSubagentText: Boolean = false,

  /** GCP authentication refresh command (e.g., "gcloud auth application-default login"). */
  gcpAuthRefresh: Option[String] = None,

  /**
   * Callback for handling MCP elicitation requests.
   * Called when an MCP server requests user input and no hook handles it.
   * The callback receives an `ElicitationRequest` and options with `signal: AbortSignal`,
   * and must return a `Promise<ElicitationResult>`.
   */
  onElicitation: Option[js.Function2[js.Dynamic, js.Dynamic, js.Promise[js.Dynamic]]] = None,

  /** Token budget for background tasks (beta feature: task-budgets-2026-03-13). */
  taskBudget: Option[TaskBudget] = None,

  /** When true, emits hook_started, hook_progress, and hook_response system messages. */
  includeHookEvents: Boolean = false,

  /**
   * Custom process spawner for VMs, containers, or remote execution.
   * Receives SpawnOptions and must return a SpawnedProcess-compatible object.
   */
  spawnClaudeCodeProcess: Option[js.Function1[js.Dynamic, js.Dynamic]] = None):
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

    if allowDangerouslySkipPermissions then obj.allowDangerouslySkipPermissions = true

    if mcpServers.nonEmpty then obj.mcpServers = js.Dictionary(mcpServers.view.mapValues(_.toRaw).toSeq*)

    // Session mode handling
    sessionMode match
      case SessionMode.New        => () // Default, no flag needed
      case SessionMode.Continue   => obj.continue = true
      case SessionMode.Resume(id) => obj.resume = id.value
      case SessionMode.Fork(id)   =>
        obj.resume = id.value
        obj.forkSession = true
      case SessionMode.ResumeAt(id, msgUuid) =>
        obj.resume = id.value
        obj.resumeSessionAt = msgUuid.value

    // Session persistence (default is true, only set if false)
    if !persistSession then obj.persistSession = false

    // Fallback model
    fallbackModel.foreach(m => obj.fallbackModel = m.id)

    // File checkpointing
    if enableFileCheckpointing then obj.enableFileCheckpointing = true

    outputFormat.foreach(of => obj.outputFormat = of.toRaw)
    if includePartialMessages then obj.includePartialMessages = true

    if additionalDirectories.nonEmpty then obj.additionalDirectories = additionalDirectories.toJSArray

    if env.nonEmpty then obj.env = js.Dictionary(env.toSeq*)

    title.foreach(t => obj.title = t)

    if betaFeatures.nonEmpty then obj.betas = betaFeatures.toJSArray

    sandboxSettings.foreach(ss => obj.sandbox = ss.toRaw)

    // Extra CLI arguments
    if extraArgs.nonEmpty then
      obj.extraArgs = js.Dictionary(extraArgs.toSeq.map {
        case (k, v) =>
          k -> v.map(_.asInstanceOf[js.Any]).getOrElse(null)
      }*)

    obj.settingSources = settingSources.map(_.raw).toJSArray

    if plugins.nonEmpty then obj.plugins = plugins.map(_.toRaw).toJSArray

    if agents.nonEmpty then obj.agents = js.Dictionary(agents.view.mapValues(_.toRaw).toSeq*)

    agent.foreach(a => obj.agent = a)

    // Thinking/reasoning
    thinking.foreach(tc => obj.thinking = tc.toRaw)
    effort.foreach(e => obj.effort = e.toRaw)
    if promptSuggestions then obj.promptSuggestions = true
    sessionId.foreach(sid => obj.sessionId = sid)

    // Debug controls
    if debug then obj.debug = true
    debugFile.foreach(df => obj.debugFile = df)
    if strictMcpConfig then obj.strictMcpConfig = true
    toolConfig.foreach(tc => obj.toolConfig = tc.toRaw)
    settings.foreach(s => obj.settings = s.toRaw)
    managedSettings.foreach(s => obj.managedSettings = s.toRaw)

    // Runtime configuration
    executable.foreach(e => obj.executable = e.raw)
    if executableArgs.nonEmpty then obj.executableArgs = executableArgs.toJSArray
    pathToClaudeCodeExecutable.foreach(p => obj.pathToClaudeCodeExecutable = p)
    permissionPromptToolName.foreach(n => obj.permissionPromptToolName = n)
    stderr.foreach(cb => obj.stderr = js.Any.fromFunction1(cb))
    if agentProgressSummaries then obj.agentProgressSummaries = true
    if forwardSubagentText then obj.forwardSubagentText = true
    gcpAuthRefresh.foreach(cmd => obj.gcpAuthRefresh = cmd)
    onElicitation.foreach(cb => obj.onElicitation = cb)
    taskBudget.foreach { budget => obj.taskBudget = js.Dynamic.literal(total = budget.total) }
    if includeHookEvents then obj.includeHookEvents = true
    spawnClaudeCodeProcess.foreach(fn => obj.spawnClaudeCodeProcess = fn)

    // Note: Hooks are converted separately in ClaudeAgent when calling query()
    // because they require a ZIO Runtime to bridge Scala→JS callbacks

    obj.asInstanceOf[js.Object]
  end toRaw

  /**
   * Convert hooks to raw JavaScript format.
   *
   * This requires a Runtime to bridge ZIO callbacks to JS functions.
   */
  def hooksToRaw(runtime: Runtime[Any]): js.Dictionary[js.Array[js.Object]] =
    if hooks.isEmpty then js.Dictionary()
    else
      js.Dictionary(
        hooks.toSeq.map {
          case (event, callbacks) =>
            val matcher = js.Dynamic.literal(
              hooks = callbacks.map(cb => HookCallback.toRawJs(cb, runtime)).toJSArray
            )
            event.toRaw -> js.Array(matcher.asInstanceOf[js.Object])
        }*
      )

  /** Convert agent definitions to raw JavaScript format, including runtime hooks when present. */
  def agentsToRaw(runtime: Runtime[Any]): js.Dictionary[js.Object] =
    if agents.isEmpty then js.Dictionary()
    else
      js.Dictionary(
        agents.toSeq.map {
          case (name, agentDef) =>
            val raw =
              if agentDef.hasHooks then agentDef.toRawWithHooks(runtime)
              else agentDef.toRaw
            name -> raw
        }*
      )

  /**
   * Convert canUseTool to raw JavaScript format.
   *
   * This requires a Runtime to bridge ZIO callbacks to JS functions.
   */
  def canUseToolToRaw(
    runtime: Runtime[Any]
  ): js.UndefOr[js.Function3[String, js.Any, js.Dynamic, js.Promise[js.Object]]] =
    canUseTool.map(handler => CanUseTool.toRawJs(handler, runtime)).orUndefined
end AgentOptions

object AgentOptions:
  private def requirePositiveInt(field: String, value: Int): PositiveInt =
    PositiveInt(value) match
      case Right(valid) => valid
      case Left(_)      =>
        throw new IllegalArgumentException(s"$field must be positive, got: $value")

  private def requirePositiveDouble(field: String, value: Double): PositiveDouble =
    PositiveDouble(value) match
      case Right(valid) => valid
      case Left(_)      =>
        throw new IllegalArgumentException(s"$field must be positive, got: $value")

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

    /**
     * Set maximum turns (must be positive).
     * @throws IllegalArgumentException if n <= 0
     */
    def withMaxTurns(n: Int): AgentOptions =
      withMaxTurns(requirePositiveInt("maxTurns", n))

    /** Set maximum turns using a validated positive integer. */
    @targetName("withMaxTurnsPositive")
    def withMaxTurns(n: PositiveInt): AgentOptions =
      opts.copy(maxTurns = Some(n.value))

    /**
     * Set maximum budget in USD (must be positive).
     * @throws IllegalArgumentException if b <= 0
     */
    def withMaxBudgetUsd(b: Double): AgentOptions =
      withMaxBudgetUsd(requirePositiveDouble("maxBudgetUsd", b))

    /** Set maximum budget in USD using a validated positive value. */
    @targetName("withMaxBudgetUsdPositive")
    def withMaxBudgetUsd(b: PositiveDouble): AgentOptions =
      opts.copy(maxBudgetUsd = Some(b.value))

    /**
     * Set maximum thinking tokens (must be positive).
     * @throws IllegalArgumentException if t <= 0
     */
    def withMaxThinkingTokens(t: Int): AgentOptions =
      withMaxThinkingTokens(requirePositiveInt("maxThinkingTokens", t))

    /** Set maximum thinking tokens using a validated positive integer. */
    @targetName("withMaxThinkingTokensPositive")
    def withMaxThinkingTokens(t: PositiveInt): AgentOptions =
      opts.copy(maxThinkingTokens = Some(t.value))

    def withPermissionMode(pm: PermissionMode): AgentOptions =
      opts.copy(permissionMode = Some(pm))

    def withBypassPermissions: AgentOptions =
      opts.copy(
        permissionMode = Some(PermissionMode.BypassPermissions),
        allowDangerouslySkipPermissions = true,
      )

    def withMcpServer(name: String, config: McpServerConfig): AgentOptions =
      opts.copy(mcpServers = opts.mcpServers + (name -> config))

    /**
     * Register an MCP server factory — creates a fresh Protocol instance per session.
     * Use this instead of withMcpServer when the server will be used by concurrent A2A sessions.
     */
    def withMcpServerFactory(name: String, factory: McpServerConfig.SdkFactory): AgentOptions =
      opts.copy(mcpServers = opts.mcpServers + (name -> factory))

    def withIncludePartialMessages: AgentOptions =
      opts.copy(includePartialMessages = true)

    /** Set the session mode */
    def withSessionMode(mode: SessionMode): AgentOptions =
      opts.copy(sessionMode = mode)

    /** Continue the current session */
    def withContinueSession: AgentOptions =
      opts.copy(sessionMode = SessionMode.Continue)

    /**
     * Resume a specific session by ID or name.
     *
     * @param sessionId Can be a UUID or a human-readable name assigned via `/rename`
     */
    def withResume(sessionId: SessionId): AgentOptions =
      opts.copy(sessionMode = SessionMode.Resume(sessionId))

    /**
     * Resume a session by its human-readable name.
     *
     * Session names are assigned using the `/rename` command in Claude Code.
     * This is equivalent to `withResume(SessionId.fromName(name))`.
     *
     * Example:
     * {{{
     * // Resume a session named "my-feature-branch"
     * AgentOptions.default.withResumeByName("my-feature-branch")
     * }}}
     */
    def withResumeByName(name: String): AgentOptions =
      opts.copy(sessionMode = SessionMode.Resume(SessionId.fromName(name)))

    /**
     * Fork from an existing session, creating a new branch.
     * The original session is preserved and a new session ID is created.
     *
     * @param sessionId Can be a UUID or a human-readable name assigned via `/rename`
     */
    def withFork(sessionId: SessionId): AgentOptions =
      opts.copy(sessionMode = SessionMode.Fork(sessionId))

    /**
     * Fork a session by its human-readable name.
     *
     * Creates a new branch from the named session without modifying the original.
     * Session names are assigned using the `/rename` command in Claude Code.
     */
    def withForkByName(name: String): AgentOptions =
      opts.copy(sessionMode = SessionMode.Fork(SessionId.fromName(name)))

    /**
     * Resume a session from a specific message UUID.
     * Only messages up to and including the specified message are restored.
     */
    def withResumeAt(sessionId: SessionId, messageUuid: com.tjclp.scalagent.types.MessageUuid): AgentOptions =
      opts.copy(sessionMode = SessionMode.ResumeAt(sessionId, messageUuid))

    /**
     * Disable session persistence (sessions won't be saved to disk).
     * Useful for ephemeral or automated workflows.
     */
    def withNoPersistence: AgentOptions =
      opts.copy(persistSession = false)

    /**
     * Set a custom session ID via extraArgs.
     * @deprecated Use [[withSessionId]] which validates UUID format and uses the proper sessionId field.
     */
    @deprecated("Use withSessionId which validates UUID format", "0.2.63")
    def withCustomSessionId(uuid: String): AgentOptions =
      opts.copy(extraArgs = opts.extraArgs + ("session-id" -> Some(uuid)))

    /** Set a fallback model to use if the primary model fails. */
    def withFallbackModel(m: Model): AgentOptions =
      opts.copy(fallbackModel = Some(m))

    /**
     * Enable file checkpointing for rewind capability.
     * When enabled, files can be rewound to their state at any user message.
     */
    def withFileCheckpointing: AgentOptions =
      opts.copy(enableFileCheckpointing = true)

    /**
     * Add an extra CLI argument.
     * Use None for boolean flags (e.g., `--some-flag`).
     * Use Some(value) for arguments with values (e.g., `--key value`).
     */
    def withExtraArg(key: String, value: Option[String] = None): AgentOptions =
      opts.copy(extraArgs = opts.extraArgs + (key -> value))

    def withSystemPrompt(prompt: SystemPromptConfig): AgentOptions =
      opts.copy(systemPrompt = Some(prompt))

    /** Set a custom system prompt string */
    def withSystemPrompt(prompt: String): AgentOptions =
      opts.copy(systemPrompt = Some(SystemPromptConfig.Custom(prompt)))

    /** Append to the claude_code preset system prompt */
    def appendSystemPrompt(append: String): AgentOptions =
      opts.copy(systemPrompt = Some(SystemPromptConfig.claudeCodeWith(append)))

    def withTools(config: ToolsConfig): AgentOptions =
      opts.copy(tools = Some(config))

    /** Set allowed tools using type-safe ToolName enum */
    def withAllowedTools(tools: ToolName*): AgentOptions =
      opts.copy(allowedTools = Some(tools.toList))

    /**
     * Apply safe tool default: if allowedTools is unset, restrict to no tools.
     * Preserves any explicit allowedTools already configured.
     * Used by DSL interpreters to ensure agents have no tool access unless opted in.
     */
    def withSafeToolDefault: AgentOptions =
      if opts.allowedTools.isEmpty then opts.copy(allowedTools = Some(Nil))
      else opts

    /** Set disallowed tools using type-safe ToolName enum */
    def withDisallowedTools(tools: ToolName*): AgentOptions =
      opts.copy(disallowedTools = Some(tools.toList))

    /** Set a custom session title (SDK 0.2.113+). */
    def withTitle(t: String): AgentOptions = opts.copy(title = Some(t))

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

    /**
     * Block specific tools via PreToolUse hook.
     *
     * Example:
     * {{{
     * options.withBlockedTools(ToolName.Bash, ToolName.Write)
     * }}}
     */
    def withBlockedTools(toolNames: ToolName*): AgentOptions =
      opts.withHook(HookEvent.PreToolUse, HookCallback.blockTools(toolNames*))

    /**
     * Auto-approve specific tools via PermissionRequest hook.
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

    /**
     * Allow only specific tools via canUseTool.
     *
     * Example:
     * {{{
     * options.withOnlyAllowedTools(ToolName.Read, ToolName.Glob, ToolName.Grep)
     * }}}
     */
    def withOnlyAllowedTools(toolNames: ToolName*): AgentOptions =
      opts.copy(canUseTool = Some(CanUseTool.allowOnly(toolNames*)))

    /**
     * Deny specific tools via canUseTool.
     *
     * Example:
     * {{{
     * options.withDeniedTools(ToolName.Bash, ToolName.Write)
     * }}}
     */
    def withDeniedTools(toolNames: ToolName*): AgentOptions =
      opts.copy(canUseTool = Some(CanUseTool.denyTools(toolNames*)))

    /**
     * Configure setting sources for filesystem-based features like Skills.
     *
     * Controls loading of Skills, plugins, and slash commands from filesystem.
     * When empty (default), scalagent serializes an explicit empty array so SDK
     * 0.3.x runs in isolation mode with no filesystem settings.
     *
     * Example:
     * {{{
     * AgentOptions.default
     *   .withSettingSources(SettingSource.User, SettingSource.Project)
     * }}}
     */
    def withSettingSources(sources: SettingSource*): AgentOptions =
      opts.copy(settingSources = sources.toList)

    /**
     * Enable Skills from user and project directories.
     *
     * Convenience method that sets settingSources to user+project and adds Skill to allowed tools.
     *
     * Example:
     * {{{
     * AgentOptions.default.withSkillsEnabled
     * }}}
     */
    def withSkillsEnabled: AgentOptions =
      opts.copy(
        settingSources = SettingSource.userAndProject,
        allowedTools = opts.allowedTools match
          case Some(tools) if !tools.contains(ToolName.Skill) =>
            Some(tools :+ ToolName.Skill)
          case Some(tools) => Some(tools)
          case None        => Some(List(ToolName.Skill)),
      )

    /** Add Skill to allowed tools (requires settingSources to be configured separately). */
    def withSkillTool: AgentOptions =
      opts.copy(
        allowedTools = opts.allowedTools match
          case Some(tools) if !tools.contains(ToolName.Skill) =>
            Some(tools :+ ToolName.Skill)
          case Some(tools) => Some(tools)
          case None        => Some(List(ToolName.Skill))
      )

    /**
     * Preload skills into the main conversation context.
     *
     * Unlike [[withSkillsEnabled]], this does not rely on runtime `Skill` tool turns.
     * Skills are resolved through the compatibility layer before the SDK call is made.
     */
    def withSkills(skillNames: SkillName*): AgentOptions =
      opts.copy(skills = (opts.skills ++ skillNames).distinct)

    /** Preload skills by raw string name. */
    @targetName("withSkillsStrings")
    def withSkills(skillNames: String*): AgentOptions =
      withSkills(skillNames.map(SkillName.apply)*)

    /**
     * Add a single plugin configuration.
     *
     * Example:
     * {{{
     * options.withPlugin(PluginConfig.local("./my-plugin"))
     * }}}
     */
    def withPlugin(plugin: PluginConfig): AgentOptions =
      opts.copy(plugins = opts.plugins :+ plugin)

    /**
     * Add multiple plugin configurations.
     *
     * Example:
     * {{{
     * options.withPlugins(
     *   PluginConfig.local("./plugin-a"),
     *   PluginConfig.local("./plugin-b")
     * )
     * }}}
     */
    def withPlugins(newPlugins: PluginConfig*): AgentOptions =
      opts.copy(plugins = opts.plugins ++ newPlugins)

    /**
     * Add local plugins by path (convenience method).
     *
     * Example:
     * {{{
     * options.withLocalPlugins("./plugin-a", "/path/to/plugin-b")
     * }}}
     */
    def withLocalPlugins(paths: String*): AgentOptions =
      opts.copy(plugins = opts.plugins ++ paths.map(PluginConfig.Local(_)))

    /**
     * Add a subagent definition.
     *
     * Example:
     * {{{
     * options.withAgent("code-reviewer", AgentDefinition(
     *   description = "Expert code reviewer",
     *   prompt = "You are a code review specialist..."
     * ))
     * }}}
     */
    def withAgent(name: String, definition: AgentDefinition): AgentOptions =
      opts.copy(agents = opts.agents + (name -> definition))

    /** Add multiple subagent definitions. */
    def withAgents(newAgents: (String, AgentDefinition)*): AgentOptions =
      opts.copy(agents = opts.agents ++ newAgents)

    /**
     * Add a read-only analysis agent (convenience method).
     *
     * Creates an agent with only Read, Grep, and Glob tools.
     *
     * Example:
     * {{{
     * options.withReadOnlyAgent(
     *   "analyzer",
     *   "Analyzes code architecture",
     *   "You are an architecture analyst..."
     * )
     * }}}
     */
    def withReadOnlyAgent(
      name: String,
      description: String,
      prompt: String,
    ): AgentOptions =
      opts.copy(agents = opts.agents + (name -> AgentDefinition.readOnly(description, prompt)))

    /**
     * Add an agent with access to specific MCP tools.
     *
     * Example:
     * {{{
     * options
     *   .withMcpServer("weather", weatherServer)
     *   .withAgentUsingMcp(
     *     "weather-assistant",
     *     description = "Weather specialist",
     *     prompt = "You provide weather info...",
     *     mcpTools = List(WeatherTools.getWeather),
     *     builtinTools = List(ToolName.Read)
     *   )
     * }}}
     */
    def withAgentUsingMcp(
      name: String,
      description: String,
      prompt: String,
      mcpTools: List[McpToolName],
      builtinTools: List[ToolName] = Nil,
      model: Option[AgentModel] = None,
    ): AgentOptions =
      opts.copy(agents =
        opts.agents + (name -> AgentDefinition(
          description = description,
          prompt = prompt,
          tools = Some(builtinTools ++ mcpTools.map(_.toToolName)),
          model = model,
        ))
      )

    /**
     * Set the main thread agent by name.
     *
     * The agent's system prompt, tool restrictions, and model will be applied to the main conversation.
     * The agent must be defined either in the `agents` option or in settings.
     *
     * This is equivalent to the `--agent` CLI flag.
     *
     * Example:
     * {{{
     * AgentOptions.default
     *   .withAgentDefinition("reviewer", AgentDefinition(
     *     description = "Reviews code for best practices",
     *     prompt = "You are a code reviewer..."
     *   ))
     *   .withMainAgent("reviewer")
     * }}}
     */
    def withMainAgent(agentName: String): AgentOptions =
      opts.copy(agent = Some(agentName))

    /**
     * Configure structured output with type-safe schema derivation.
     *
     * Requires a StructuredOutput type class instance for the output type, which provides both JSON Schema generation
     * and type-safe parsing.
     *
     * Example:
     * {{{
     * case class Result(summary: String, score: Int)
     * object Result:
     *   given Schema[Result] = DeriveSchema.gen[Result]
     *   given JsonDecoder[Result] = DeriveJsonDecoder.gen[Result]
     *   given StructuredOutput[Result] = StructuredOutput.derive[Result]
     *
     * val options = AgentOptions.default.withStructuredOutput[Result]
     * }}}
     */
    def withStructuredOutput[A](using so: StructuredOutput[A]): AgentOptions =
      opts.copy(outputFormat = Some(StructuredOutput.toOutputFormat[A]))

    /**
     * Configure structured output with an explicit OutputFormat.
     *
     * Use this when you have a pre-built JSON Schema as zio.json.ast.Json.
     *
     * Example:
     * {{{
     * val schema = Json.Obj("type" -> Json.Str("object"), ...)
     * options.withOutputFormat(OutputFormat(schema))
     * }}}
     */
    def withOutputFormat(format: OutputFormat): AgentOptions =
      opts.copy(outputFormat = Some(format))

    /**
     * Enable debug mode for verbose logging.
     *
     * @param enabled
     *   Whether debug mode is enabled (default: true)
     * @param file
     *   Optional file path to write debug logs to
     *
     * Example:
     * {{{
     * options.withDebug()                          // Enable debug to stderr
     * options.withDebug(file = Some("/tmp/debug.log"))  // Write to file
     * }}}
     */
    def withDebug(enabled: Boolean = true, file: Option[String] = None): AgentOptions =
      opts.copy(debug = enabled, debugFile = file)

    /**
     * Set thinking configuration for Claude's reasoning behavior.
     *
     * Takes precedence over the deprecated maxThinkingTokens.
     *
     * Example:
     * {{{
     * options.withThinking(ThinkingConfig.adaptive)
     * options.withThinking(ThinkingConfig.enabled(8192))
     * }}}
     */
    def withThinking(config: ThinkingConfig): AgentOptions =
      opts.copy(thinking = Some(config))

    /** Enable adaptive thinking (Opus 4.6+). */
    def withAdaptiveThinking: AgentOptions =
      opts.copy(thinking = Some(ThinkingConfig.Adaptive))

    /**
     * Set the effort level for Claude's responses.
     *
     * Example:
     * {{{
     * options.withEffort(Effort.High)
     * options.withEffort(Effort.Max)  // Opus 4.6 only
     * }}}
     */
    def withEffort(e: Effort): AgentOptions =
      opts.copy(effort = Some(e))

    /** Enable prompt suggestions after each turn. */
    def withPromptSuggestions: AgentOptions =
      opts.copy(promptSuggestions = true)

    /** Set a specific session ID (must be a valid UUID). */
    def withSessionId(uuid: String): AgentOptions =
      withSessionId(
        SessionUuid(uuid) match
          case Right(valid)  => valid
          case Left(message) => throw new IllegalArgumentException(message)
      )

    /** Set a specific session ID using a validated UUID wrapper. */
    @targetName("withSessionIdValidated")
    def withSessionId(uuid: SessionUuid): AgentOptions =
      opts.copy(sessionId = Some(uuid.value))

    /**
     * Enable strict MCP server configuration validation.
     *
     * When enabled, invalid MCP server configurations will cause errors
     * instead of warnings.
     */
    def withStrictMcpConfig: AgentOptions =
      opts.copy(strictMcpConfig = true)

    /**
     * Set per-tool configuration.
     *
     * Example:
     * {{{
     * options.withToolConfig(ToolConfig(askUserQuestionPreviewFormat = Some("html")))
     * }}}
     */
    def withToolConfig(config: ToolConfig): AgentOptions =
      opts.copy(toolConfig = Some(config))

    /**
     * Set inline settings or settings file path.
     *
     * Example:
     * {{{
     * options.withSettings(SettingsConfig.Path("/path/to/settings.json"))
     * options.withSettings(SettingsConfig.Inline(js.Dynamic.literal(...)))
     * }}}
     */
    def withSettings(config: SettingsConfig): AgentOptions =
      opts.copy(settings = Some(config))

    /**
     * Set embedder-provided policy-tier settings pushed in-memory to the spawned CLI.
     * Merged on top of file-based settings. Requires SDK 0.2.118+.
     */
    def withManagedSettings(config: ManagedSettings): AgentOptions =
      opts.copy(managedSettings = Some(config))

    /** Set inline managed settings directly from a raw settings object. */
    def withInlineManagedSettings(settings: js.Object): AgentOptions =
      opts.copy(managedSettings = Some(ManagedSettings(settings)))

    /** Set the runtime executable for Claude Code. */
    def withExecutable(exe: Executable): AgentOptions =
      opts.copy(executable = Some(exe))

    /** Set additional arguments for the runtime executable. */
    def withExecutableArgs(args: String*): AgentOptions =
      opts.copy(executableArgs = args.toList)

    /** Set the path to the Claude Code executable binary. */
    def withPathToClaudeCode(path: String): AgentOptions =
      opts.copy(pathToClaudeCodeExecutable = Some(path))

    /** Route permission requests through an MCP tool. */
    def withPermissionPromptToolName(name: String): AgentOptions =
      opts.copy(permissionPromptToolName = Some(name))

    /** Set a callback for capturing stderr output from Claude Code. */
    def withStderr(callback: String => Unit): AgentOptions =
      opts.copy(stderr = Some(callback))

    /** Enable periodic AI-generated progress summaries for subagents. */
    def withAgentProgressSummaries: AgentOptions =
      opts.copy(agentProgressSummaries = true)

    /**
     * Forward subagent text deltas to the parent's stream as normal assistant
     * text events. Requires SDK 0.2.119+.
     */
    def withForwardSubagentText: AgentOptions =
      opts.copy(forwardSubagentText = true)

    /** Set GCP authentication refresh command. */
    def withGcpAuthRefresh(command: String): AgentOptions =
      opts.copy(gcpAuthRefresh = Some(command))

    /** Set the elicitation callback for handling MCP user-input requests. */
    def withOnElicitation(callback: js.Function2[js.Dynamic, js.Dynamic, js.Promise[js.Dynamic]]): AgentOptions =
      opts.copy(onElicitation = Some(callback))
  end extension
end AgentOptions

/** System prompt configuration */
enum SystemPromptConfig:
  /** Custom system prompt string */
  case Custom(prompt: String)

  /** Use a preset with optional append */
  case Preset(preset: String, append: Option[String] = None)

  /** Convert to raw JavaScript value */
  def toRaw: js.Any = this match
    case Custom(p)              => p.asInstanceOf[js.Any]
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
    case Preset(p)       =>
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
        schema = js.JSON.parse(schema.toString),
      )
      .asInstanceOf[js.Object]

object OutputFormat:
  given JsonDecoder[OutputFormat] = DeriveJsonDecoder.gen[OutputFormat]
  given JsonEncoder[OutputFormat] = DeriveJsonEncoder.gen[OutputFormat]

/** Per-tool configuration options */
final case class ToolConfig(
  askUserQuestionPreviewFormat: Option[String] = None):
  def toRaw: js.Object =
    val obj = js.Dynamic.literal()
    askUserQuestionPreviewFormat.foreach { fmt => obj.askUserQuestion = js.Dynamic.literal(previewFormat = fmt) }
    obj.asInstanceOf[js.Object]

object ToolConfig:
  /** Create a ToolConfig with HTML preview format for askUserQuestion */
  def htmlPreviews: ToolConfig = ToolConfig(askUserQuestionPreviewFormat = Some("html"))

  /** Create a ToolConfig with markdown preview format (default) */
  def markdownPreviews: ToolConfig = ToolConfig(askUserQuestionPreviewFormat = Some("markdown"))

/** Settings configuration — either an inline object or a file path */
enum SettingsConfig:
  case Path(path: String)
  case Inline(settings: js.Object)

  def toRaw: js.Any = this match
    case Path(p)     => p.asInstanceOf[js.Any]
    case Inline(obj) => obj.asInstanceOf[js.Any]

/** Inline-only managed settings payload. */
final case class ManagedSettings(settings: js.Object):
  def toRaw: js.Object = settings

object ManagedSettings:
  def inline(settings: js.Object): ManagedSettings =
    ManagedSettings(settings)

/** Runtime executable for spawning Claude Code. */
enum Executable(val raw: String):
  case Bun  extends Executable("bun")
  case Deno extends Executable("deno")
  case Node extends Executable("node")
