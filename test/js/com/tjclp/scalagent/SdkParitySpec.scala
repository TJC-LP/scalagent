package com.tjclp.scalagent

import munit.FunSuite
import scala.scalajs.js
import zio.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.core.{AgentEvent, SubagentContext}
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.hooks.*
import com.tjclp.scalagent.messages.{AgentMessage, InformationalLevel, SystemEvent}
import com.tjclp.scalagent.permissions.{CanUseTool, PermissionContext, PermissionResult}
import com.tjclp.scalagent.streaming.*
import com.tjclp.scalagent.tools.ToolName

/**
 * Surface tests for the Claude Agent SDK 0.3.x additions wrapped under
 * TJC-1130. Each test pins one new wrapper so future SDK churn surfaces
 * here first.
 */
class SdkParitySpec extends FunSuite:
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future

  private def run[A](effect: IO[AgentError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private def runUIO[A](effect: UIO[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  /** Run an async effect (e.g. wrapping a JS promise) without blocking, for JS test bodies. */
  private def runFuture[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(effect)
    }

  // ============================================
  // AgentOptions additions
  // ============================================

  test("forwardSubagentText defaults to false and is serialized only when true"):
    val off = AgentOptions.default
    assertEquals(off.forwardSubagentText, false)
    val rawOff = off.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(rawOff.forwardSubagentText.asInstanceOf[js.UndefOr[Boolean]].toOption, None)

    val on    = AgentOptions.default.withForwardSubagentText
    val rawOn = on.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(on.forwardSubagentText, true)
    assertEquals(rawOn.forwardSubagentText.asInstanceOf[Boolean], true)

  test("AgentOptions.default serializes empty settingSources for SDK isolation"):
    val raw     = AgentOptions.default.toRaw.asInstanceOf[js.Dynamic]
    val sources = raw.settingSources.asInstanceOf[js.Array[String]]
    assertEquals(sources.length, 0)

  test("managedSettings is serialized as inline managedSettings on toRaw"):
    val inline = js.Dynamic.literal(model = "claude-sonnet-4-20250514").asInstanceOf[js.Object]
    val opts =
      AgentOptions.default.withManagedSettings(ManagedSettings(inline))
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    val model = raw.managedSettings.asInstanceOf[js.Dynamic].model.asInstanceOf[String]
    assertEquals(model, "claude-sonnet-4-20250514")

  test("AgentDefinition.background is wired into toRaw"):
    val agent =
      AgentDefinition(description = "bg agent", prompt = "test", background = true)
    val raw = agent.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.background.asInstanceOf[Boolean], true)

  // ============================================
  // Hooks
  // ============================================

  test("HookEvent.MessageDisplay round-trips through raw + JSON"):
    assertEquals(HookEvent.MessageDisplay.toRaw, "MessageDisplay")
    assertEquals(HookEvent.fromString("MessageDisplay"), HookEvent.MessageDisplay)
    val json = HookEvent.MessageDisplay.toJson
    assertEquals(json.fromJson[HookEvent], Right(HookEvent.MessageDisplay))

  test("SessionStartOutput emits reloadSkills + sessionTitle"):
    val out =
      HookOutput.SessionStartOutput(reloadSkills = true, sessionTitle = Some("custom-title"))
    val raw      = out.toRaw.asInstanceOf[js.Dynamic]
    val specific = raw.hookSpecificOutput.asInstanceOf[js.Dynamic]
    assertEquals(specific.reloadSkills.asInstanceOf[Boolean], true)
    assertEquals(specific.sessionTitle.asInstanceOf[String], "custom-title")

  test("MessageDisplayOutput emits displayContent"):
    val raw      = HookOutput.displayContent("rewritten").toRaw.asInstanceOf[js.Dynamic]
    val specific = raw.hookSpecificOutput.asInstanceOf[js.Dynamic]
    assertEquals(specific.displayContent.asInstanceOf[String], "rewritten")

  // ============================================
  // Tools
  // ============================================

  test("new tool names round-trip"):
    val newTools = List(
      ToolName.Monitor,
      ToolName.TaskCreate,
      ToolName.TaskUpdate,
      ToolName.TaskGet,
      ToolName.TaskList,
    )
    newTools.foreach { t =>
      assertEquals(ToolName.fromString(t.raw), t)
      assertEquals(t.toJson.fromJson[ToolName], Right(t))
    }

  test("Monitor / TaskGet / TaskList are read-only"):
    assert(ToolName.isReadOnly(ToolName.Monitor))
    assert(ToolName.isReadOnly(ToolName.TaskGet))
    assert(ToolName.isReadOnly(ToolName.TaskList))
    assert(!ToolName.isReadOnly(ToolName.TaskCreate))
    assert(!ToolName.isReadOnly(ToolName.TaskUpdate))

  // ============================================
  // Errors
  // ============================================

  test("fromErrorReason lifts model_not_found into ModelNotFound"):
    AgentError.fromErrorReason("model_not_found", Some("claude-opus-99")) match
      case AgentError.ModelNotFound(model, _) =>
        assertEquals(model, "claude-opus-99")
      case other =>
        fail(s"Expected ModelNotFound, got $other")

  test("fromErrorReason model_not_found without details has clear message"):
    val err = AgentError.fromErrorReason("model_not_found")
    assertEquals(err.message, "Requested model not found")

  test("ModelNotFound.message renders available models when present"):
    val err = AgentError.ModelNotFound("x", List("a", "b"))
    assert(err.message.contains("Available"))

  // ============================================
  // MCP
  // ============================================

  test("McpServerConfig.Stdio.alwaysLoad propagates to toRaw"):
    val cfg = McpServerConfig.Stdio(command = "node", alwaysLoad = true)
    val raw = cfg.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.alwaysLoad.asInstanceOf[Boolean], true)

  test("McpServerConfig.SSE/HTTP alwaysLoad propagates to toRaw"):
    val sse  = McpServerConfig.SSE(url = "https://example.com/sse", alwaysLoad = true).toRaw.asInstanceOf[js.Dynamic]
    val http = McpServerConfig.HTTP(url = "https://example.com/mcp", alwaysLoad = true).toRaw.asInstanceOf[js.Dynamic]
    assertEquals(sse.alwaysLoad.asInstanceOf[Boolean], true)
    assertEquals(http.alwaysLoad.asInstanceOf[Boolean], true)

  test("McpServerConfig.Stdio omits alwaysLoad when false (default)"):
    val cfg = McpServerConfig.Stdio(command = "node")
    val raw = cfg.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.alwaysLoad.asInstanceOf[js.UndefOr[Boolean]].toOption, None)

  test("McpServerStatus.fromString parses pending / connected / failed / needs-auth"):
    assertEquals(McpServerStatus.fromString("pending"), McpServerStatus.Pending)
    assertEquals(McpServerStatus.fromString("connected"), McpServerStatus.Connected)
    assertEquals(McpServerStatus.fromString("failed"), McpServerStatus.Failed)
    assertEquals(McpServerStatus.fromString("needs-auth"), McpServerStatus.NeedsAuth)

  test("McpServerStatus.fromString falls back to Custom for unknown values"):
    McpServerStatus.fromString("starting") match
      case McpServerStatus.Custom(raw) => assertEquals(raw, "starting")
      case other                       => fail(s"Expected Custom, got $other")

  test("McpServerStatusInfo.connectionStatus parses raw status"):
    val info = McpServerStatusInfo(
      name = "srv",
      status = "pending",
      serverName = None,
      serverVersion = None,
    )
    assertEquals(info.connectionStatus, McpServerStatus.Pending)

  // ============================================
  // Startup + resolveSettings
  // ============================================

  test("WarmQueryHandle exposes queryRaw and becomes consumed"):
    var queryPrompt: Option[String] = None
    var closeCount                  = 0
    val rawQuery                    = js.Dynamic.literal()
    val rawWarm = js.Dynamic
      .literal(
        query = (prompt: js.Any) =>
          queryPrompt = Some(prompt.asInstanceOf[String])
          rawQuery,
        close = () => closeCount += 1,
      )
      .asInstanceOf[RawWarmQuery]

    val handle = WarmQueryHandle(rawWarm)
    run(handle.queryRaw("hello"))
    assertEquals(queryPrompt, Some("hello"))
    run(handle.queryRaw("again").either) match
      case Left(error) => assert(error.message.contains("already been consumed"))
      case Right(_)    => fail("Expected consumed handle to fail")
    runUIO(handle.discard)
    assertEquals(closeCount, 0)

  test("WarmQueryHandle.discard closes unused raw handle exactly once"):
    var closeCount = 0
    val rawWarm = js.Dynamic
      .literal(
        query = (_: js.Any) => js.Dynamic.literal(),
        close = () => closeCount += 1,
      )
      .asInstanceOf[RawWarmQuery]

    val handle = WarmQueryHandle(rawWarm)
    runUIO(handle.discard)
    assertEquals(closeCount, 1)
    runUIO(handle.discard)
    assertEquals(closeCount, 1)

  test("resolveSettings options can serialize explicit empty settingSources"):
    val opts = Claude
      .resolveSettingsOptions(None, Some(Nil), None)
      .getOrElse(fail("Expected resolveSettings options"))
    val sources = opts.settingSources.asInstanceOf[js.Array[String]]
    assertEquals(sources.length, 0)

  test("ResolvedSettings exposes structured JSON with raw escape hatch"):
    val raw = js.Dynamic.literal(
      effective = js.Dynamic.literal(
        model = "claude-sonnet-4-20250514",
        env = js.Dynamic.literal(tags = js.Array("prod", "review"), missing = null),
      ),
      provenance = js.Dynamic.literal(model = js.Dynamic.literal(source = "managed")),
      sources = js.Array(js.Dynamic.literal(kind = "managed"))
    )
    val resolved = ResolvedSettings.fromRaw(raw)
    assertEquals(resolved.effectiveModel, Some("claude-sonnet-4-20250514"))
    assertEquals(
      ResolvedSettings.field(resolved.effective, "env"),
      Some(Json.Obj("tags" -> Json.Arr(Json.Str("prod"), Json.Str("review")), "missing" -> Json.Null)),
    )
    assertEquals(resolved.provenanceFor("model"), Some(Json.Obj("source" -> Json.Str("managed"))))
    assertEquals(resolved.sources, List(Json.Obj("kind" -> Json.Str("managed"))))

  test("AgentEvent TextDelta can carry subagent context"):
    val event = AgentEvent.TextDelta("child text", Some(SubagentContext("reviewer", Some("review code"))))
    event match
      case text: AgentEvent.TextDelta =>
        assertEquals(text.subagentContext.map(_.subagentType), Some("reviewer"))
      case other => fail(s"Expected TextDelta, got $other")

  // ============================================
  // SDK 0.3.201 additions (TJC-1466)
  // ============================================

  test("AgentDefinition observer + observerMessage are wired into toRaw and omitted by default"):
    val bare = AgentDefinition(description = "d", prompt = "p").toRaw.asInstanceOf[js.Dynamic]
    assertEquals(bare.observer.asInstanceOf[js.UndefOr[String]].toOption, None)
    assertEquals(bare.observerMessage.asInstanceOf[js.UndefOr[String]].toOption, None)

    val watched = AgentDefinition(description = "d", prompt = "p")
      .withObserver("safety-observer", Some("Focus on file writes."))
    val raw = watched.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.observer.asInstanceOf[String], "safety-observer")
    assertEquals(raw.observerMessage.asInstanceOf[String], "Focus on file writes.")

  test("AgentDefinition observer fields round-trip through JSON"):
    val agent   = AgentDefinition(description = "d", prompt = "p").withObserver("watcher")
    val decoded = agent.toJson.fromJson[AgentDefinition]
    assertEquals(decoded.map(_.observer), Right(Some("watcher")))
    assertEquals(decoded.map(_.observerMessage), Right(None))

  test("SandboxSettings allowAppleEvents is serialized only when true"):
    val off = SandboxSettings.default.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(off.allowAppleEvents.asInstanceOf[js.UndefOr[Boolean]].toOption, None)
    val on = SandboxSettings.default.withAllowAppleEvents.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(on.allowAppleEvents.asInstanceOf[Boolean], true)

  test("SandboxCredentialsConfig serializes file deny + env var mask rules"):
    val config = SandboxCredentialsConfig(
      files = List(CredentialFileRule("~/.aws/credentials")),
      envVars = List(
        CredentialEnvVarRule("GITHUB_TOKEN", CredentialEnvVarMode.Mask, injectHosts = List("api.github.com")),
        CredentialEnvVarRule("SECRET_KEY", CredentialEnvVarMode.Deny),
      ),
    )
    val raw = SandboxSettings.default.withCredentials(config).toRaw.asInstanceOf[js.Dynamic]
    val creds = raw.credentials.asInstanceOf[js.Dynamic]
    val file  = creds.files.asInstanceOf[js.Array[js.Dynamic]](0)
    assertEquals(file.path.asInstanceOf[String], "~/.aws/credentials")
    assertEquals(file.mode.asInstanceOf[String], "deny")
    val masked = creds.envVars.asInstanceOf[js.Array[js.Dynamic]](0)
    assertEquals(masked.name.asInstanceOf[String], "GITHUB_TOKEN")
    assertEquals(masked.mode.asInstanceOf[String], "mask")
    assertEquals(masked.injectHosts.asInstanceOf[js.Array[String]].toList, List("api.github.com"))
    val denied = creds.envVars.asInstanceOf[js.Array[js.Dynamic]](1)
    assertEquals(denied.mode.asInstanceOf[String], "deny")
    assertEquals(denied.injectHosts.asInstanceOf[js.UndefOr[js.Any]].toOption, None)
    assertEquals(creds.allowPlaintextInject.asInstanceOf[js.UndefOr[Boolean]].toOption, None)

  test("McpServerToolPolicy org_max_permission is emitted only when set"):
    val plain = McpServerToolPolicy(ToolName.fromString("read_doc"), McpToolPolicy.AlwaysAllow)
    val plainRaw = plain.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(plainRaw.org_max_permission.asInstanceOf[js.UndefOr[String]].toOption, None)

    val capped =
      McpServerToolPolicy(ToolName.fromString("delete_doc"), McpToolPolicy.AlwaysAllow, Some(McpOrgMaxPermission.Ask))
    val cappedRaw = capped.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(cappedRaw.permission_policy.asInstanceOf[String], "always_allow")
    assertEquals(cappedRaw.org_max_permission.asInstanceOf[String], "ask")

  test("McpOrgMaxPermission falls back to Custom for unknown values"):
    assertEquals(McpOrgMaxPermission.fromString("blocked"), McpOrgMaxPermission.Blocked)
    McpOrgMaxPermission.fromString("quarantined") match
      case McpOrgMaxPermission.Custom(v) => assertEquals(v, "quarantined")
      case other                         => fail(s"Expected Custom, got $other")

  test("ReportFindings + MCP resource tool names round-trip"):
    val newTools = List(
      ToolName.ReportFindings,
      ToolName.ListMcpResources,
      ToolName.ReadMcpResource,
      ToolName.ReadMcpResourceDir,
    )
    newTools.foreach { t =>
      assertEquals(ToolName.fromString(t.raw), t)
      assertEquals(t.toJson.fromJson[ToolName], Right(t))
    }

  test("ModelInfo.fromRaw reads resolvedModel"):
    val raw = js.Dynamic.literal(
      value = "sonnet",
      displayName = "Sonnet",
      description = "alias",
      resolvedModel = "claude-sonnet-5",
    )
    assertEquals(ModelInfo.fromRaw(raw).resolvedModel, Some("claude-sonnet-5"))
    val bare = js.Dynamic.literal(value = "opus", displayName = "Opus")
    assertEquals(ModelInfo.fromRaw(bare).resolvedModel, None)

  test("listSessionsOptions serializes includeProgrammatic only when false"):
    val defaults = Claude.listSessionsOptions("/proj", 50, includeWorktrees = true, includeProgrammatic = true)
    assertEquals(defaults.includeProgrammatic.asInstanceOf[js.UndefOr[Boolean]].toOption, None)
    val filtered = Claude.listSessionsOptions("/proj", 50, includeWorktrees = true, includeProgrammatic = false)
    assertEquals(filtered.includeProgrammatic.asInstanceOf[Boolean], false)

  // ============================================
  // SDK 0.3.201 system message subtypes (TJC-1466)
  // ============================================

  private def systemEnvelope(subtype: String): js.Dynamic =
    js.Dynamic.literal(
      `type` = "system",
      subtype = subtype,
      uuid = "uuid-1",
      session_id = "session-1",
    )

  private def parseSystemEvent(raw: js.Dynamic): SystemEvent =
    MessageConverter.fromRaw(raw) match
      case AgentMessage.System(event, _, _) => event
      case other                            => fail(s"Expected AgentMessage.System, got $other")

  test("system/informational parses content, level, tool_use_id, prevent_continuation"):
    val raw = systemEnvelope("informational")
    raw.content = "Stop hook denied continuation"
    raw.level = "warning"
    raw.tool_use_id = "tool-9"
    raw.prevent_continuation = true

    parseSystemEvent(raw) match
      case SystemEvent.Informational(content, level, toolUseId, preventContinuation) =>
        assertEquals(content, "Stop hook denied continuation")
        assertEquals(level, InformationalLevel.Warning)
        assertEquals(toolUseId.map(_.value), Some("tool-9"))
        assertEquals(preventContinuation, true)
      case other => fail(s"Expected Informational, got $other")

  test("system/informational unknown level falls back to Custom"):
    val raw = systemEnvelope("informational")
    raw.content = "banner"
    raw.level = "loud"
    parseSystemEvent(raw) match
      case SystemEvent.Informational(_, InformationalLevel.Custom(v), _, _) => assertEquals(v, "loud")
      case other => fail(s"Expected Informational with Custom level, got $other")

  test("system/model_refusal_fallback parses refusal metadata and retractions"):
    val raw = systemEnvelope("model_refusal_fallback")
    raw.original_model = "claude-fable-5"
    raw.fallback_model = "claude-opus-4-8"
    raw.content = "Falling back"
    raw.request_id = "req-1"
    raw.api_refusal_category = "cyber"
    raw.api_refusal_explanation = "explanation"
    raw.retracted_message_uuids = js.Array("uuid-a", "uuid-b")
    raw.refused_user_message_uuid = "uuid-user"

    parseSystemEvent(raw) match
      case refusal: SystemEvent.ModelRefusalFallback =>
        assertEquals(refusal.originalModel, "claude-fable-5")
        assertEquals(refusal.fallbackModel, "claude-opus-4-8")
        assertEquals(refusal.requestId, Some("req-1"))
        assertEquals(refusal.apiRefusalCategory, Some("cyber"))
        assertEquals(refusal.retractedMessageUuids.map(_.value), List("uuid-a", "uuid-b"))
        assertEquals(refusal.refusedUserMessageUuid.map(_.value), Some("uuid-user"))
      case other => fail(s"Expected ModelRefusalFallback, got $other")

  test("system/model_refusal_no_fallback parses with null request_id"):
    val raw = systemEnvelope("model_refusal_no_fallback")
    raw.original_model = "claude-fable-5"
    raw.content = "Refused"
    raw.request_id = null

    parseSystemEvent(raw) match
      case refusal: SystemEvent.ModelRefusalNoFallback =>
        assertEquals(refusal.originalModel, "claude-fable-5")
        assertEquals(refusal.content, "Refused")
        assertEquals(refusal.requestId, None)
        assertEquals(refusal.apiRefusalCategory, None)
      case other => fail(s"Expected ModelRefusalNoFallback, got $other")

  test("system/worker_shutting_down parses reason and guards missing reason to Unknown"):
    val raw = systemEnvelope("worker_shutting_down")
    raw.reason = "host_exit"
    parseSystemEvent(raw) match
      case SystemEvent.WorkerShuttingDown(reason) => assertEquals(reason, "host_exit")
      case other                                  => fail(s"Expected WorkerShuttingDown, got $other")

    val missing = systemEnvelope("worker_shutting_down")
    parseSystemEvent(missing) match
      case SystemEvent.Unknown(envelope) => assertEquals(envelope.rawSubtype, Some("worker_shutting_down"))
      case other                         => fail(s"Expected Unknown fallback, got $other")

  // ============================================
  // SDK 0.3.201 QueryStream control methods (TJC-1466)
  // ============================================

  private def queryStreamWith(raw: js.Dynamic): QueryStream =
    QueryStream(raw.asInstanceOf[RawQuery])

  test("setMaxThinkingTokens forwards Keep/Clear/Set display modes"):
    val calls = scala.collection.mutable.ListBuffer.empty[List[js.Any]]
    val raw = js.Dynamic.literal(
      setMaxThinkingTokens = js.Any.fromFunction2 { (tokens: js.Any, display: js.Any) =>
        calls += List(tokens, display)
        js.Promise.resolve[Unit](())
      }
    )
    val stream = queryStreamWith(raw)

    val effects =
      stream.setMaxThinkingTokens(Some(1000)) *>
        stream.setMaxThinkingTokens(Some(2000), ThinkingDisplayUpdate.Clear) *>
        stream.setMaxThinkingTokens(None, ThinkingDisplayUpdate.Set(ThinkingDisplay.Summarized))

    runFuture(effects).map { _ =>
      assertEquals(calls(0)(0).asInstanceOf[Int], 1000)
      assert(js.isUndefined(calls(0)(1)))
      assertEquals(calls(1)(1).asInstanceOf[Null], null)
      assertEquals(calls(2)(0).asInstanceOf[Null], null)
      assertEquals(calls(2)(1).asInstanceOf[String], "summarized")
    }

  test("setMcpPermissionModeOverride forwards mode and surfaces the warning"):
    var captured: Option[(String, js.Any)] = None
    val raw = js.Dynamic.literal(
      setMcpPermissionModeOverride = js.Any.fromFunction2 { (server: String, mode: js.Any) =>
        captured = Some((server, mode))
        js.Promise.resolve[js.Dynamic](js.Dynamic.literal(warning = "unknown server 'linear'"))
      }
    )
    val stream = queryStreamWith(raw)
    runFuture(stream.setMcpPermissionModeOverride("linear", Some(McpPermissionModeOverride.Default))).map {
      warning =>
        assertEquals(warning, Some("unknown server 'linear'"))
        assertEquals(captured.map(_._1), Some("linear"))
        assertEquals(captured.map(_._2.asInstanceOf[String]), Some("default"))
    }

  test("setMcpPermissionModeOverride clears with null and maps absent warning to None"):
    var capturedMode: js.Any = "sentinel"
    val raw = js.Dynamic.literal(
      setMcpPermissionModeOverride = js.Any.fromFunction2 { (_: String, mode: js.Any) =>
        capturedMode = mode
        js.Promise.resolve[js.Dynamic](js.Dynamic.literal())
      }
    )
    runFuture(queryStreamWith(raw).setMcpPermissionModeOverride("linear", None)).map { warning =>
      assertEquals(warning, None)
      assertEquals(capturedMode.asInstanceOf[Null], null)
    }

  test("reinitialize sends a fresh initialize request and parses the result"):
    var callCount = 0
    val payload = js.Dynamic.literal(
      commands = js.Array(js.Dynamic.literal(name = "review", description = "Review code")),
      output_style = "default",
      available_output_styles = js.Array("default"),
      models = js.Array(
        js.Dynamic.literal(value = "sonnet", displayName = "Sonnet", resolvedModel = "claude-sonnet-5")
      ),
      account = js.Dynamic.literal(email = "dev@example.com"),
    )
    val raw = js.Dynamic.literal(
      reinitialize = js.Any.fromFunction0 { () =>
        callCount += 1
        js.Promise.resolve[js.Dynamic](payload)
      }
    )
    runFuture(queryStreamWith(raw).reinitialize).map { result =>
      assertEquals(callCount, 1)
      assertEquals(result.commands.map(_.name), List("review"))
      assertEquals(result.models.headOption.flatMap(_.resolvedModel), Some("claude-sonnet-5"))
      assertEquals(result.account.email, Some("dev@example.com"))
    }

  // ============================================
  // SDK 0.3.201 CanUseTool requestId (TJC-1466)
  // ============================================

  test("CanUseTool bridge surfaces requestId from options"):
    var seen: Option[PermissionContext] = None
    val handler: CanUseTool = (_, _, ctx) =>
      ZIO.succeed {
        seen = Some(ctx)
        PermissionResult.allow
      }
    val rawFn = CanUseTool.toRawJs(handler, Runtime.default)
    val options = js.Dynamic.literal(
      toolUseID = "tool-1",
      requestId = "req-42",
    )
    rawFn("Bash", js.Dynamic.literal(command = "ls"), options).toFuture.map { _ =>
      val ctx = seen.getOrElse(fail("Expected handler to run"))
      assertEquals(ctx.requestId, Some("req-42"))
      assertEquals(ctx.toolUseId.value, "tool-1")
    }

end SdkParitySpec
