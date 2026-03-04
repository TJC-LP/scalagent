package com.tjclp.scalagent.hooks

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import com.tjclp.scalagent.config.PermissionMode

class HookCallbackSpec extends FunSuite:
  private val runtime = Runtime.default

  private def baseInput(eventName: String): js.Dynamic =
    js.Dynamic.literal(
      session_id = "session-123",
      cwd = "/tmp/project",
      transcript_path = "/tmp/project/transcript.json",
      hook_event_name = eventName,
      permission_mode = "delegate"
    )

  private def parseInput(raw: js.Dynamic): Future[HookInput] =
    var parsed: Option[HookInput] = None
    val callback: HookCallback = input =>
      ZIO.succeed {
        parsed = Some(input)
        HookOutput.continue
      }
    HookCallback
      .toRawJs(callback, runtime)
      .apply(raw)
      .toFuture
      .map(_ => parsed.getOrElse(fail("Expected hook input to be parsed")))

  test("parseHookInput handles TeammateIdle"):
    val raw = baseInput("TeammateIdle")
    raw.teammate_name = "researcher"
    raw.team_name = "my-team"

    parseInput(raw).map {
      case input: HookInput.TeammateIdle =>
        assertEquals(input.teammateName, "researcher")
        assertEquals(input.teamName, "my-team")
        assertEquals(input.permissionMode, Some(PermissionMode.Delegate))
      case other => fail(s"Expected TeammateIdle, got: $other")
    }

  test("parseHookInput handles TaskCompleted"):
    val raw = baseInput("TaskCompleted")
    raw.task_id = "task-99"
    raw.task_subject = "Implement feature X"
    raw.task_description = "Detailed description"
    raw.teammate_name = "coder"
    raw.team_name = "dev-team"

    parseInput(raw).map {
      case input: HookInput.TaskCompleted =>
        assertEquals(input.taskId, "task-99")
        assertEquals(input.taskSubject, "Implement feature X")
        assertEquals(input.taskDescription, Some("Detailed description"))
        assertEquals(input.teammateName, Some("coder"))
        assertEquals(input.teamName, Some("dev-team"))
      case other => fail(s"Expected TaskCompleted, got: $other")
    }

  test("parseHookInput handles Elicitation"):
    val raw = baseInput("Elicitation")
    raw.mcp_server_name = "mcp-server"
    raw.message = "Approve this request?"
    raw.mode = "form"
    raw.elicitation_id = "elic-123"

    parseInput(raw).map {
      case input: HookInput.Elicitation =>
        assertEquals(input.mcpServerName, "mcp-server")
        assertEquals(input.message, "Approve this request?")
        assertEquals(input.mode, Some(ElicitationMode.Form))
        assertEquals(input.elicitationId, Some("elic-123"))
      case other => fail(s"Expected Elicitation, got: $other")
    }

  test("parseHookInput handles ElicitationResult"):
    val raw = baseInput("ElicitationResult")
    raw.mcp_server_name = "mcp-server"
    raw.action = "decline"
    raw.elicitation_id = "elic-456"

    parseInput(raw).map {
      case input: HookInput.ElicitationResult =>
        assertEquals(input.mcpServerName, "mcp-server")
        assertEquals(input.action, ElicitationAction.Decline)
        assertEquals(input.elicitationId, Some("elic-456"))
      case other => fail(s"Expected ElicitationResult, got: $other")
    }

  test("parseHookInput handles ConfigChange"):
    val raw = baseInput("ConfigChange")
    raw.source = "project_settings"
    raw.file_path = "/path/to/settings.json"

    parseInput(raw).map {
      case input: HookInput.ConfigChange =>
        assertEquals(input.source, ConfigChangeSource.ProjectSettings)
        assertEquals(input.filePath, Some("/path/to/settings.json"))
      case other => fail(s"Expected ConfigChange, got: $other")
    }

  test("parseHookInput handles WorktreeCreate"):
    val raw = baseInput("WorktreeCreate")
    raw.name = "feature-branch"

    parseInput(raw).map {
      case input: HookInput.WorktreeCreate =>
        assertEquals(input.name, "feature-branch")
      case other => fail(s"Expected WorktreeCreate, got: $other")
    }

  test("parseHookInput handles WorktreeRemove"):
    val raw = baseInput("WorktreeRemove")
    raw.worktree_path = "/tmp/worktree-b"

    parseInput(raw).map {
      case input: HookInput.WorktreeRemove =>
        assertEquals(input.worktreePath, "/tmp/worktree-b")
      case other => fail(s"Expected WorktreeRemove, got: $other")
    }

  test("parseHookInput handles Setup"):
    val raw = baseInput("Setup")
    raw.trigger = "init"

    parseInput(raw).map {
      case input: HookInput.Setup =>
        assertEquals(input.trigger, SetupTrigger.Init)
        assertEquals(input.permissionMode, Some(PermissionMode.Delegate))
      case other => fail(s"Expected Setup, got: $other")
    }
