package com.tjclp.scalagent

import munit.FunSuite
import scala.scalajs.js
import zio.json.*
import com.tjclp.scalagent.config.*
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

  test("managedSettings is serialized as managedSettings on toRaw"):
    val opts =
      AgentOptions.default.withManagedSettings(SettingsConfig.Path("/etc/managed/settings.json"))
    val raw = opts.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.managedSettings.asInstanceOf[String], "/etc/managed/settings.json")

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

end SdkParitySpec
