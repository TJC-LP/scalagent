package com.tjclp.claude.agent.config

import munit.FunSuite
import scala.scalajs.js
import com.tjclp.claude.agent.tools.ToolName
// PermissionMode is in config package, not permissions

class AgentOptionsSpec extends FunSuite:

  // ============================================
  // Default Values
  // ============================================

  test("default options has all None/empty values"):
    val opts = AgentOptions.default
    assertEquals(opts.model, None)
    assertEquals(opts.cwd, None)
    assertEquals(opts.systemPrompt, None)
    assertEquals(opts.maxTurns, None)
    assertEquals(opts.maxBudgetUsd, None)
    assert(opts.mcpServers.isEmpty)
    assert(opts.hooks.isEmpty)

  test("AgentOptions.default is the same as AgentOptions()"):
    assertEquals(AgentOptions.default, AgentOptions())

  // ============================================
  // Builder Methods - Core Options
  // ============================================

  test("withModel sets the model"):
    val opts = AgentOptions.default.withModel(Model.Sonnet4)
    assertEquals(opts.model, Some(Model.Sonnet4))

  test("withModelId sets model from string"):
    val opts = AgentOptions.default.withModelId("custom-model-123")
    opts.model match
      case Some(Model.Custom(id)) => assertEquals(id, "custom-model-123")
      case other                  => fail(s"Expected Custom model, got $other")

  test("withCwd sets working directory"):
    val opts = AgentOptions.default.withCwd("/path/to/project")
    assertEquals(opts.cwd, Some("/path/to/project"))

  test("withSystemPrompt sets system prompt"):
    val opts = AgentOptions.default.withSystemPrompt(SystemPromptConfig.claudeCode)
    assertEquals(opts.systemPrompt, Some(SystemPromptConfig.claudeCode))

  // ============================================
  // Builder Methods - Limits
  // ============================================

  test("withMaxTurns sets max turns"):
    val opts = AgentOptions.default.withMaxTurns(10)
    assertEquals(opts.maxTurns, Some(10))

  test("withMaxTurns rejects non-positive values"):
    intercept[IllegalArgumentException] {
      AgentOptions.default.withMaxTurns(0)
    }
    intercept[IllegalArgumentException] {
      AgentOptions.default.withMaxTurns(-5)
    }

  test("withMaxBudgetUsd sets budget"):
    val opts = AgentOptions.default.withMaxBudgetUsd(5.0)
    assertEquals(opts.maxBudgetUsd, Some(5.0))

  test("withMaxBudgetUsd rejects non-positive values"):
    intercept[IllegalArgumentException] {
      AgentOptions.default.withMaxBudgetUsd(0.0)
    }
    intercept[IllegalArgumentException] {
      AgentOptions.default.withMaxBudgetUsd(-1.0)
    }

  test("withMaxThinkingTokens sets thinking tokens"):
    val opts = AgentOptions.default.withMaxThinkingTokens(1000)
    assertEquals(opts.maxThinkingTokens, Some(1000))

  test("withMaxThinkingTokens rejects non-positive values"):
    intercept[IllegalArgumentException] {
      AgentOptions.default.withMaxThinkingTokens(0)
    }

  // ============================================
  // Builder Methods - Permissions
  // ============================================

  test("withPermissionMode sets permission mode"):
    val opts = AgentOptions.default.withPermissionMode(PermissionMode.Default)
    assertEquals(opts.permissionMode, Some(PermissionMode.Default))

  test("withBypassPermissions enables bypass"):
    val opts = AgentOptions.default.withBypassPermissions
    assertEquals(opts.permissionMode, Some(PermissionMode.BypassPermissions))
    assertEquals(opts.allowDangerouslySkipPermissions, true)

  // ============================================
  // Builder Methods - Tools
  // ============================================

  test("withAllowedTools sets allowed tools"):
    val opts = AgentOptions.default.withAllowedTools(ToolName.Read, ToolName.Glob)
    assertEquals(opts.allowedTools, Some(List(ToolName.Read, ToolName.Glob)))

  test("withDisallowedTools sets disallowed tools"):
    val opts = AgentOptions.default.withDisallowedTools(ToolName.Bash, ToolName.Write)
    assertEquals(opts.disallowedTools, Some(List(ToolName.Bash, ToolName.Write)))

  // ============================================
  // Builder Methods - Session
  // ============================================

  test("withContinueSession sets continue mode"):
    val opts = AgentOptions.default.withContinueSession
    assertEquals(opts.sessionMode, SessionMode.Continue)

  test("withResume sets resume mode with session ID"):
    import com.tjclp.claude.agent.types.SessionId
    val opts = AgentOptions.default.withResume(SessionId("session-123"))
    opts.sessionMode match
      case SessionMode.Resume(id) => assertEquals(id.value, "session-123")
      case other                  => fail(s"Expected Resume, got $other")

  // ============================================
  // Builder Methods - Plugins and Agents
  // ============================================

  test("withPlugin adds a plugin"):
    val opts = AgentOptions.default.withPlugin(PluginConfig.local("./plugin"))
    assertEquals(opts.plugins.size, 1)

  test("withPlugins adds multiple plugins"):
    val opts = AgentOptions.default.withPlugins(
      PluginConfig.local("./plugin-a"),
      PluginConfig.local("./plugin-b")
    )
    assertEquals(opts.plugins.size, 2)

  test("withLocalPlugins adds local plugins by path"):
    val opts = AgentOptions.default.withLocalPlugins("./p1", "./p2", "./p3")
    assertEquals(opts.plugins.size, 3)

  test("withAgent adds an agent definition"):
    val agent = AgentDefinition(
      description = "Test agent",
      prompt = "Test prompt"
    )
    val opts = AgentOptions.default.withAgent("test", agent)
    assert(opts.agents.contains("test"))
    assertEquals(opts.agents("test"), agent)

  test("withReadOnlyAgent adds a read-only agent"):
    val opts = AgentOptions.default.withReadOnlyAgent(
      "analyzer",
      "Analyzes code",
      "You analyze code."
    )
    assert(opts.agents.contains("analyzer"))
    opts.agents("analyzer").tools match
      case Some(tools) =>
        assert(tools.contains(ToolName.Read))
        assert(tools.contains(ToolName.Grep))
        assert(tools.contains(ToolName.Glob))
      case None => fail("Expected tools to be defined")

  // ============================================
  // Builder Methods - Setting Sources
  // ============================================

  test("withSettingSources sets setting sources"):
    val opts = AgentOptions.default.withSettingSources(SettingSource.User, SettingSource.Project)
    assertEquals(opts.settingSources, List(SettingSource.User, SettingSource.Project))

  test("withSkillsEnabled sets sources and adds Skill tool"):
    val opts = AgentOptions.default.withSkillsEnabled
    assertEquals(opts.settingSources, SettingSource.userAndProject)
    opts.allowedTools match
      case Some(tools) => assert(tools.contains(ToolName.Skill))
      case None        => fail("Expected allowedTools to be set")

  // ============================================
  // Method Chaining
  // ============================================

  test("builder methods can be chained"):
    val opts = AgentOptions.default
      .withModel(Model.Sonnet4)
      .withCwd("/project")
      .withMaxTurns(10)
      .withMaxBudgetUsd(5.0)
      .withSystemPrompt(SystemPromptConfig.claudeCode)
      .withAllowedTools(ToolName.Read, ToolName.Glob)

    assertEquals(opts.model, Some(Model.Sonnet4))
    assertEquals(opts.cwd, Some("/project"))
    assertEquals(opts.maxTurns, Some(10))
    assertEquals(opts.maxBudgetUsd, Some(5.0))
    assertEquals(opts.systemPrompt, Some(SystemPromptConfig.claudeCode))
    assertEquals(opts.allowedTools, Some(List(ToolName.Read, ToolName.Glob)))

  // ============================================
  // toRaw Serialization
  // ============================================

  test("toRaw creates JS object"):
    val opts = AgentOptions.default.withModel(Model.Sonnet4)
    val raw = opts.toRaw
    assert(raw.isInstanceOf[js.Object])

  test("toRaw includes model when set"):
    val opts = AgentOptions.default.withModel(Model.Sonnet4)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.model.asInstanceOf[String], "claude-sonnet-4-20250514")

  test("toRaw includes systemPrompt when set"):
    val opts = AgentOptions.default.withSystemPrompt(SystemPromptConfig.claudeCode)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val sp = raw.systemPrompt.asInstanceOf[js.Dynamic]
    assertEquals(sp.`type`.asInstanceOf[String], "preset")
    assertEquals(sp.preset.asInstanceOf[String], "claude_code")

  test("toRaw includes maxTurns when set"):
    val opts = AgentOptions.default.withMaxTurns(15)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.maxTurns.asInstanceOf[Int], 15)

  test("toRaw includes allowedTools when set"):
    val opts = AgentOptions.default.withAllowedTools(ToolName.Read, ToolName.Bash)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val tools = raw.allowedTools.asInstanceOf[js.Array[String]]
    assert(tools.contains("Read"))
    assert(tools.contains("Bash"))

  test("toRaw includes settingSources when set"):
    val opts = AgentOptions.default.withSettingSources(SettingSource.User, SettingSource.Project)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val sources = raw.settingSources.asInstanceOf[js.Array[String]]
    assert(sources.contains("user"))
    assert(sources.contains("project"))

  test("toRaw includes plugins when set"):
    val opts = AgentOptions.default.withLocalPlugins("./plugin")
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val plugins = raw.plugins.asInstanceOf[js.Array[js.Dynamic]]
    assertEquals(plugins.length, 1)
    assertEquals(plugins(0).`type`.asInstanceOf[String], "local")

  test("toRaw includes agents when set"):
    val opts = AgentOptions.default.withAgent(
      "test",
      AgentDefinition("Desc", "Prompt")
    )
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val agents = raw.agents.asInstanceOf[js.Dictionary[js.Dynamic]]
    assert(agents.contains("test"))
    assertEquals(agents("test").description.asInstanceOf[String], "Desc")

  test("toRaw omits undefined optional fields"):
    val opts = AgentOptions.default
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.model))
    assert(js.isUndefined(raw.maxTurns))
    assert(js.isUndefined(raw.systemPrompt))

  test("toRaw handles continue session mode"):
    val opts = AgentOptions.default.withContinueSession
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.continue.asInstanceOf[Boolean], true)

  test("toRaw handles resume session mode"):
    import com.tjclp.claude.agent.types.SessionId
    val opts = AgentOptions.default.withResume(SessionId("sess-123"))
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.resume.asInstanceOf[String], "sess-123")
