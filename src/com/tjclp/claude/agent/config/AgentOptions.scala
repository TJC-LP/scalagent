package com.tjclp.claude.agent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio.json._

/** Configuration options for Claude Agent queries.
  *
  * This mirrors the TypeScript SDK's `Options` interface.
  */
final case class AgentOptions(
    // Core options
    model: Option[String] = None,
    cwd: Option[String] = None,
    systemPrompt: Option[SystemPromptConfig] = None,

    // Tool configuration
    tools: Option[ToolsConfig] = None,
    allowedTools: Option[List[String]] = None,
    disallowedTools: Option[List[String]] = None,

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
    continueSession: Boolean = false,
    resume: Option[String] = None,

    // Output
    outputFormat: Option[OutputFormat] = None,
    includePartialMessages: Boolean = false,

    // Advanced
    additionalDirectories: List[String] = Nil,
    env: Map[String, String] = Map.empty
):
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object =
    val obj = js.Dynamic.literal()

    model.foreach(m => obj.model = m)
    cwd.foreach(c => obj.cwd = c)
    systemPrompt.foreach(sp => obj.systemPrompt = sp.toRaw)
    tools.foreach(t => obj.tools = t.toRaw)
    allowedTools.foreach(at => obj.allowedTools = at.toJSArray)
    disallowedTools.foreach(dt => obj.disallowedTools = dt.toJSArray)
    maxTurns.foreach(mt => obj.maxTurns = mt)
    maxBudgetUsd.foreach(mb => obj.maxBudgetUsd = mb)
    maxThinkingTokens.foreach(mtt => obj.maxThinkingTokens = mtt)
    permissionMode.foreach(pm => obj.permissionMode = pm.toRaw)

    if allowDangerouslySkipPermissions then
      obj.allowDangerouslySkipPermissions = true

    if mcpServers.nonEmpty then
      obj.mcpServers = js.Dictionary(mcpServers.view.mapValues(_.toRaw).toSeq*)

    if continueSession then obj.continue = true
    resume.foreach(r => obj.resume = r)

    outputFormat.foreach(of => obj.outputFormat = of.toRaw)
    if includePartialMessages then obj.includePartialMessages = true

    if additionalDirectories.nonEmpty then
      obj.additionalDirectories = additionalDirectories.toJSArray

    if env.nonEmpty then
      obj.env = js.Dictionary(env.toSeq*)

    obj.asInstanceOf[js.Object]

object AgentOptions:
  /** Default options (empty configuration) */
  val default: AgentOptions = AgentOptions()

  given JsonDecoder[AgentOptions] = DeriveJsonDecoder.gen[AgentOptions]
  given JsonEncoder[AgentOptions] = DeriveJsonEncoder.gen[AgentOptions]

  // Extension methods for fluent builder pattern
  extension (opts: AgentOptions)
    def withModel(m: String): AgentOptions = opts.copy(model = Some(m))
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

    def withContinueSession: AgentOptions =
      opts.copy(continueSession = true)

    def withResume(sessionId: String): AgentOptions =
      opts.copy(resume = Some(sessionId))

    def withSystemPrompt(prompt: SystemPromptConfig): AgentOptions =
      opts.copy(systemPrompt = Some(prompt))

    def withTools(config: ToolsConfig): AgentOptions =
      opts.copy(tools = Some(config))

    def withAllowedTools(tools: String*): AgentOptions =
      opts.copy(allowedTools = Some(tools.toList))

    def withDisallowedTools(tools: String*): AgentOptions =
      opts.copy(disallowedTools = Some(tools.toList))

    def withEnv(env: Map[String, String]): AgentOptions =
      opts.copy(env = env)

    def withAdditionalDirectories(dirs: String*): AgentOptions =
      opts.copy(additionalDirectories = dirs.toList)

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
