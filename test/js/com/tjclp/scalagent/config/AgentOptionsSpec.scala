package com.tjclp.scalagent.config

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import scala.scalajs.js
import com.tjclp.scalagent.tools.ToolName
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

  test("withModelAndEffort sets compile-time validated model and effort"):
    val opts = AgentOptions.default.withModelAndEffort(Model.Opus4_8, Effort.Max)
    assertEquals(opts.model, Some(Model.Opus4_8))
    assertEquals(opts.effort, Some(Effort.Max))

  test("withModelAndEffort accepts supported built-in effort combinations"):
    assert(typeChecks("""
      import com.tjclp.scalagent.config.*

      AgentOptions.default.withModelAndEffort(Model.Fable5, Effort.XHigh)
      AgentOptions.default.withModelAndEffort(Model.Fable5, Effort.Max)
      AgentOptions.default.withModelAndEffort(Model.Sonnet5, Effort.XHigh)
      AgentOptions.default.withModelAndEffort(Model.Sonnet5, Effort.Max)
      AgentOptions.default.withModelAndEffort(Model.Opus4_8, Effort.XHigh)
      AgentOptions.default.withModelAndEffort(Model.Opus4_7, Effort.XHigh)
      AgentOptions.default.withModelAndEffort(Model.Opus4_8, Effort.Max)
      AgentOptions.default.withModelAndEffort(Model.Opus4_7, Effort.Max)
      AgentOptions.default.withModelAndEffort(Model.Opus4_6, Effort.Max)
      AgentOptions.default.withModelAndEffort(Model.Sonnet4_6, Effort.Max)
      AgentOptions.default.withModelAndEffort(Model.Haiku4_5, Effort.High)
    """))

  test("withModelAndEffort rejects unsupported built-in effort combinations"):
    assert(!typeChecks("""
      import com.tjclp.scalagent.config.*

      AgentOptions.default.withModelAndEffort(Model.Haiku4_5, Effort.Max)
    """))
    assert(!typeChecks("""
      import com.tjclp.scalagent.config.*

      AgentOptions.default.withModelAndEffort(Model.Sonnet4_6, Effort.XHigh)
    """))
    assert(!typeChecks("""
      import com.tjclp.scalagent.config.*

      AgentOptions.default.withModelAndEffort(Model.Custom("foo"), Effort.Max)
    """))

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

  test("withMaxTurns accepts PositiveInt"):
    val opts = AgentOptions.default.withMaxTurns(PositiveInt.unsafe(12))
    assertEquals(opts.maxTurns, Some(12))

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

  test("withMaxBudgetUsd accepts PositiveDouble"):
    val opts = AgentOptions.default.withMaxBudgetUsd(PositiveDouble.unsafe(7.5))
    assertEquals(opts.maxBudgetUsd, Some(7.5))

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

  test("withMaxThinkingTokens accepts PositiveInt"):
    val opts = AgentOptions.default.withMaxThinkingTokens(PositiveInt.unsafe(2048))
    assertEquals(opts.maxThinkingTokens, Some(2048))

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
    import com.tjclp.scalagent.types.SessionId
    val opts = AgentOptions.default.withResume(SessionId("session-123"))
    opts.sessionMode match
      case SessionMode.Resume(id) => assertEquals(id.value, "session-123")
      case other                  => fail(s"Expected Resume, got $other")

  test("withResumeByName sets resume mode with human-readable name"):
    val opts = AgentOptions.default.withResumeByName("my-feature-branch")
    opts.sessionMode match
      case SessionMode.Resume(id) => assertEquals(id.value, "my-feature-branch")
      case other                  => fail(s"Expected Resume, got $other")

  test("withForkByName sets fork mode with human-readable name"):
    val opts = AgentOptions.default.withForkByName("my-feature-branch")
    opts.sessionMode match
      case SessionMode.Fork(id) => assertEquals(id.value, "my-feature-branch")
      case other                => fail(s"Expected Fork, got $other")

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

  test("withSkills stores preloaded skill names"):
    val opts = AgentOptions.default.withSkills(SkillName("slides"), SkillName("spreadsheets"))
    assertEquals(opts.skills, List(SkillName("slides"), SkillName("spreadsheets")))

  test("withSkills string overload accepts raw skill names"):
    val opts = AgentOptions.default.withSkills("slides", "spreadsheets")
    assertEquals(opts.skills, List(SkillName("slides"), SkillName("spreadsheets")))

  test("prepare adds default setting sources for top-level skills"):
    val prepared = AgentOptionsCompatibility.prepare(AgentOptions.default.withSkills("slides"))
    assertEquals(prepared.settingSources, SettingSource.userAndProject)

  test("prepare augments an existing main agent with preloaded skills"):
    val prepared = AgentOptionsCompatibility.prepare(
      AgentOptions.default
        .withAgent("main", AgentDefinition(description = "Main agent", prompt = "You are the main agent."))
        .withMainAgent("main")
        .withSkills("slides")
    )

    assertEquals(prepared.agent, Some("main"))
    assertEquals(prepared.agents("main").skills, List("slides"))

  test("prepare synthesizes a main agent when only top-level skills are provided"):
    val prepared = AgentOptionsCompatibility.prepare(AgentOptions.default.withSkills("slides"))
    val agentName = prepared.agent.getOrElse(fail("Expected a synthesized main agent"))

    assert(agentName.startsWith("__scalagent_preloaded_skills"))
    assertEquals(prepared.agents(agentName).skills, List("slides"))
    assertEquals(prepared.agents(agentName).prompt, "")

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
    import com.tjclp.scalagent.types.SessionId
    val opts = AgentOptions.default.withResume(SessionId("sess-123"))
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.resume.asInstanceOf[String], "sess-123")

  // ============================================
  // P1 Features - Session Forking
  // ============================================

  test("withFork sets fork session mode"):
    import com.tjclp.scalagent.types.SessionId
    val opts = AgentOptions.default.withFork(SessionId("sess-456"))
    opts.sessionMode match
      case SessionMode.Fork(id) => assertEquals(id.value, "sess-456")
      case other => fail(s"Expected Fork, got $other")

  test("toRaw handles fork session mode"):
    import com.tjclp.scalagent.types.SessionId
    val opts = AgentOptions.default.withFork(SessionId("sess-456"))
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.resume.asInstanceOf[String], "sess-456")
    assertEquals(raw.forkSession.asInstanceOf[Boolean], true)

  test("withResumeAt sets resume at specific message"):
    import com.tjclp.scalagent.types.{SessionId, MessageUuid}
    val opts = AgentOptions.default.withResumeAt(SessionId("sess-789"), MessageUuid("msg-abc"))
    opts.sessionMode match
      case SessionMode.ResumeAt(sessId, msgId) =>
        assertEquals(sessId.value, "sess-789")
        assertEquals(msgId.value, "msg-abc")
      case other => fail(s"Expected ResumeAt, got $other")

  test("toRaw handles resume at session mode"):
    import com.tjclp.scalagent.types.{SessionId, MessageUuid}
    val opts = AgentOptions.default.withResumeAt(SessionId("sess-789"), MessageUuid("msg-abc"))
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.resume.asInstanceOf[String], "sess-789")
    assertEquals(raw.resumeSessionAt.asInstanceOf[String], "msg-abc")

  // ============================================
  // P1 Features - Session Persistence
  // ============================================

  test("default persistSession is true"):
    val opts = AgentOptions.default
    assertEquals(opts.persistSession, true)

  test("withNoPersistence disables session persistence"):
    val opts = AgentOptions.default.withNoPersistence
    assertEquals(opts.persistSession, false)

  test("toRaw includes persistSession false when disabled"):
    val opts = AgentOptions.default.withNoPersistence
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.persistSession.asInstanceOf[Boolean], false)

  test("toRaw omits persistSession when true (default)"):
    val opts = AgentOptions.default
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.persistSession))

  // ============================================
  // P1 Features - Custom Session ID
  // ============================================

  test("withCustomSessionId sets session-id in extraArgs"):
    val opts = AgentOptions.default.withCustomSessionId("my-uuid-123")
    assertEquals(opts.extraArgs.get("session-id"), Some(Some("my-uuid-123")))

  test("withSessionId sets sessionId for valid UUID"):
    val uuid = "123e4567-e89b-12d3-a456-426614174000"
    val opts = AgentOptions.default.withSessionId(uuid)
    assertEquals(opts.sessionId, Some(uuid))

  test("withSessionId accepts SessionUuid"):
    import com.tjclp.scalagent.types.SessionUuid
    val uuid = SessionUuid("123e4567-e89b-12d3-a456-426614174000").toOption.get
    val opts = AgentOptions.default.withSessionId(uuid)
    assertEquals(opts.sessionId, Some(uuid.value))

  test("withSessionId rejects invalid UUID"):
    intercept[IllegalArgumentException] {
      AgentOptions.default.withSessionId("not-a-uuid")
    }

  test("toRaw includes extraArgs with custom session ID"):
    val opts = AgentOptions.default.withCustomSessionId("my-uuid-123")
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val extra = raw.extraArgs.asInstanceOf[js.Dictionary[js.Any]]
    assertEquals(extra("session-id").asInstanceOf[String], "my-uuid-123")

  // ============================================
  // P1 Features - Fallback Model
  // ============================================

  test("withFallbackModel sets fallback model"):
    val opts = AgentOptions.default.withFallbackModel(Model.Haiku4_5)
    assertEquals(opts.fallbackModel, Some(Model.Haiku4_5))

  test("toRaw includes fallbackModel when set"):
    val opts = AgentOptions.default.withFallbackModel(Model.Haiku4_5)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.fallbackModel.asInstanceOf[String], "claude-haiku-4-5-20251001")

  // ============================================
  // P1 Features - File Checkpointing
  // ============================================

  test("default enableFileCheckpointing is false"):
    val opts = AgentOptions.default
    assertEquals(opts.enableFileCheckpointing, false)

  test("withFileCheckpointing enables file checkpointing"):
    val opts = AgentOptions.default.withFileCheckpointing
    assertEquals(opts.enableFileCheckpointing, true)

  test("toRaw includes enableFileCheckpointing when true"):
    val opts = AgentOptions.default.withFileCheckpointing
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.enableFileCheckpointing.asInstanceOf[Boolean], true)

  test("toRaw omits enableFileCheckpointing when false"):
    val opts = AgentOptions.default
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.enableFileCheckpointing))

  // ============================================
  // P1 Features - Extra Args
  // ============================================

  test("withExtraArg adds argument with value"):
    val opts = AgentOptions.default.withExtraArg("custom-flag", Some("value"))
    assertEquals(opts.extraArgs.get("custom-flag"), Some(Some("value")))

  test("withExtraArg adds boolean flag (None value)"):
    val opts = AgentOptions.default.withExtraArg("some-flag", None)
    assertEquals(opts.extraArgs.get("some-flag"), Some(None))

  test("toRaw includes extraArgs"):
    val opts = AgentOptions.default
      .withExtraArg("key1", Some("val1"))
      .withExtraArg("flag", None)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val extra = raw.extraArgs.asInstanceOf[js.Dictionary[js.Any]]
    assertEquals(extra("key1").asInstanceOf[String], "val1")
    assert(extra("flag") == null)

  // ============================================
  // v0.2.2 Features - Main Agent
  // ============================================

  test("withMainAgent sets main thread agent"):
    val opts = AgentOptions.default
      .withAgent("reviewer", AgentDefinition("Reviews code", "You are a reviewer"))
      .withMainAgent("reviewer")
    assertEquals(opts.agent, Some("reviewer"))

  test("toRaw includes agent when set"):
    val opts = AgentOptions.default.withMainAgent("my-agent")
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.agent.asInstanceOf[String], "my-agent")

  test("toRaw omits agent when not set"):
    val opts = AgentOptions.default
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.agent))

  // ============================================
  // Runtime Configuration Fields (SDK 0.2.71)
  // ============================================

  test("withExecutable sets runtime executable"):
    val opts = AgentOptions.default.withExecutable(Executable.Bun)
    assertEquals(opts.executable, Some(Executable.Bun))

  test("toRaw includes executable when set"):
    val opts = AgentOptions.default.withExecutable(Executable.Node)
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.executable.asInstanceOf[String], "node")

  test("toRaw omits executable when not set"):
    val raw = AgentOptions.default.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.executable))

  test("withExecutableArgs sets runtime args"):
    val opts = AgentOptions.default.withExecutableArgs("--max-old-space-size=4096", "--harmony")
    assertEquals(opts.executableArgs, List("--max-old-space-size=4096", "--harmony"))

  test("toRaw includes executableArgs when non-empty"):
    val opts = AgentOptions.default.withExecutableArgs("--flag")
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val args = raw.executableArgs.asInstanceOf[js.Array[String]]
    assertEquals(args.length, 1)
    assertEquals(args(0), "--flag")

  test("toRaw omits executableArgs when empty"):
    val raw = AgentOptions.default.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.executableArgs))

  test("withPathToClaudeCode sets executable path"):
    val opts = AgentOptions.default.withPathToClaudeCode("/usr/local/bin/claude")
    assertEquals(opts.pathToClaudeCodeExecutable, Some("/usr/local/bin/claude"))

  test("toRaw includes pathToClaudeCodeExecutable when set"):
    val opts = AgentOptions.default.withPathToClaudeCode("/usr/local/bin/claude")
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.pathToClaudeCodeExecutable.asInstanceOf[String], "/usr/local/bin/claude")

  test("withPermissionPromptToolName sets MCP tool routing"):
    val opts = AgentOptions.default.withPermissionPromptToolName("mcp__auth__approve")
    assertEquals(opts.permissionPromptToolName, Some("mcp__auth__approve"))

  test("toRaw includes permissionPromptToolName when set"):
    val opts = AgentOptions.default.withPermissionPromptToolName("mcp__auth__approve")
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.permissionPromptToolName.asInstanceOf[String], "mcp__auth__approve")

  test("withStderr sets stderr callback"):
    var captured = ""
    val opts = AgentOptions.default.withStderr(s => captured = s)
    assert(opts.stderr.isDefined)

  test("toRaw includes stderr as JS function when set"):
    val opts = AgentOptions.default.withStderr(_ => ())
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(js.typeOf(raw.stderr), "function")

  // ============================================
  // Agent Progress Summaries (SDK 0.2.72)
  // ============================================

  test("withAgentProgressSummaries enables summaries"):
    val opts = AgentOptions.default.withAgentProgressSummaries
    assertEquals(opts.agentProgressSummaries, true)

  test("toRaw includes agentProgressSummaries when enabled"):
    val opts = AgentOptions.default.withAgentProgressSummaries
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.agentProgressSummaries.asInstanceOf[Boolean], true)

  test("toRaw omits agentProgressSummaries when disabled"):
    val raw = AgentOptions.default.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.agentProgressSummaries))

  test("withGcpAuthRefresh sets GCP auth command"):
    val opts = AgentOptions.default.withGcpAuthRefresh("gcloud auth application-default login")
    assertEquals(opts.gcpAuthRefresh, Some("gcloud auth application-default login"))

  test("toRaw includes gcpAuthRefresh when set"):
    val opts = AgentOptions.default.withGcpAuthRefresh("gcloud auth login")
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.gcpAuthRefresh.asInstanceOf[String], "gcloud auth login")

  // ============================================
  // AgentDefinition.maxTurns (SDK 0.2.71)
  // ============================================

  test("AgentDefinition withMaxTurns sets max turns"):
    val agent = AgentDefinition("Desc", "Prompt").withMaxTurns(5)
    assertEquals(agent.maxTurns, Some(5))

  test("AgentDefinition withMaxTurns rejects non-positive values"):
    intercept[IllegalArgumentException] {
      AgentDefinition("Desc", "Prompt").withMaxTurns(0)
    }
    intercept[IllegalArgumentException] {
      AgentDefinition("Desc", "Prompt").withMaxTurns(-3)
    }

  test("AgentDefinition toRaw includes maxTurns when set"):
    val agent = AgentDefinition("Desc", "Prompt").withMaxTurns(10)
    val raw = agent.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.maxTurns.asInstanceOf[Int], 10)

  test("AgentDefinition toRaw omits maxTurns when not set"):
    val agent = AgentDefinition("Desc", "Prompt")
    val raw = agent.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.maxTurns))

  test("AgentDefinition maxTurns JSON round-trip"):
    import zio.json.*
    val agent = AgentDefinition("Desc", "Prompt").withMaxTurns(7)
    val json = agent.toJson
    val decoded = json.fromJson[AgentDefinition]
    decoded match
      case Right(a) => assertEquals(a.maxTurns, Some(7))
      case Left(err) => fail(s"Decode failed: $err")

  // ============================================
  // Misc
  // ============================================

  test("PermissionMode.fromString parses unknown modes"):
    val custom = PermissionMode.fromString("some-custom-mode")
    assertEquals(custom.toRaw, "some-custom-mode")

  test("PermissionMode handles unsupported delegate as Custom"):
    assertEquals(PermissionMode.fromString("delegate"), PermissionMode.Custom("delegate"))

  // ============================================
  // Runtime Configuration Fields (SDK 0.2.113)
  // ============================================

  test("withTitle sets the session title"):
    val opts = AgentOptions.default.withTitle("My Custom Session")
    assertEquals(opts.title, Some("My Custom Session"))

  test("toRaw emits title when set"):
    val opts = AgentOptions.default.withTitle("Hello")
    val raw = opts.toRaw.asInstanceOf[scala.scalajs.js.Dynamic]
    assertEquals(raw.title.asInstanceOf[String], "Hello")

  test("toRaw omits title when unset"):
    val opts = AgentOptions.default
    val raw = opts.toRaw.asInstanceOf[scala.scalajs.js.Dynamic]
    assert(raw.title.asInstanceOf[scala.scalajs.js.UndefOr[String]].isEmpty)

  // ============================================
  // MCP per-tool permission policy (SDK 0.2.111)
  // ============================================

  test("McpServerConfig.HTTP emits per-tool permission_policy when configured"):
    val cfg = McpServerConfig.HTTP(
      url = "https://mcp.example.com",
      tools = List(
        McpServerToolPolicy(ToolName.fromString("read_doc"), McpToolPolicy.AlwaysAllow),
        McpServerToolPolicy(ToolName.fromString("delete_doc"), McpToolPolicy.AlwaysDeny)
      )
    )
    val raw = cfg.toRaw.asInstanceOf[scala.scalajs.js.Dynamic]
    assertEquals(raw.`type`.asInstanceOf[String], "http")
    val tools = raw.tools.asInstanceOf[scala.scalajs.js.Array[scala.scalajs.js.Dynamic]]
    assertEquals(tools.length, 2)
    assertEquals(tools(0).name.asInstanceOf[String], "read_doc")
    assertEquals(tools(0).permission_policy.asInstanceOf[String], "always_allow")
    assertEquals(tools(1).permission_policy.asInstanceOf[String], "always_deny")

  test("McpServerConfig.SSE omits tools key when none configured"):
    val cfg = McpServerConfig.SSE(url = "https://sse.example.com")
    val raw = cfg.toRaw.asInstanceOf[scala.scalajs.js.Dynamic]
    assert(raw.tools.asInstanceOf[scala.scalajs.js.UndefOr[scala.scalajs.js.Any]].isEmpty)

  test("McpToolPolicy.fromString is forward-compatible"):
    assertEquals(McpToolPolicy.fromString("always_allow"), McpToolPolicy.AlwaysAllow)
    assertEquals(McpToolPolicy.fromString("always_ask"), McpToolPolicy.AlwaysAsk)
    assertEquals(McpToolPolicy.fromString("always_deny"), McpToolPolicy.AlwaysDeny)
    assertEquals(McpToolPolicy.fromString("future_value"), McpToolPolicy.Custom("future_value"))
