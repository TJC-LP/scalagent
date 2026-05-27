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
import com.tjclp.scalagent.streaming.*
import com.tjclp.scalagent.tools.ToolName

/**
 * Surface tests for the Claude Agent SDK 0.3.x additions wrapped under
 * TJC-1130. Each test pins one new wrapper so future SDK churn surfaces
 * here first.
 */
class SdkParitySpec extends FunSuite:

  private def run[A](effect: IO[AgentError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private def runUIO[A](effect: UIO[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
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

  test("resolveSettings options can serialize explicit empty settingSources"):
    val opts = Claude
      .resolveSettingsOptions(None, Some(Nil), None)
      .getOrElse(fail("Expected resolveSettings options"))
    val sources = opts.settingSources.asInstanceOf[js.Array[String]]
    assertEquals(sources.length, 0)

  test("ResolvedSettings exposes structured JSON with raw escape hatch"):
    val raw = js.Dynamic.literal(
      effective = js.Dynamic.literal(model = "claude-sonnet-4-20250514"),
      provenance = js.Dynamic.literal(model = js.Dynamic.literal(source = "managed")),
      sources = js.Array(js.Dynamic.literal(kind = "managed"))
    )
    val resolved = ResolvedSettings.fromRaw(raw)
    assertEquals(resolved.effectiveModel, Some("claude-sonnet-4-20250514"))
    assertEquals(resolved.provenanceFor("model"), Some(Json.Obj("source" -> Json.Str("managed"))))
    assertEquals(resolved.sources, List(Json.Obj("kind" -> Json.Str("managed"))))

  test("AgentEvent TextDelta can carry subagent context"):
    val event = AgentEvent.TextDelta("child text", Some(SubagentContext("reviewer", Some("review code"))))
    event match
      case text: AgentEvent.TextDelta =>
        assertEquals(text.subagentContext.map(_.subagentType), Some("reviewer"))
      case other => fail(s"Expected TextDelta, got $other")

end SdkParitySpec
