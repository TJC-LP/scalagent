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
    raw.teammate_id = "teammate-42"

    parseInput(raw).map {
      case input: HookInput.TeammateIdle =>
        assertEquals(input.teammateId, "teammate-42")
        assertEquals(input.permissionMode, Some(PermissionMode.Delegate))
      case other => fail(s"Expected TeammateIdle, got: $other")
    }

  test("parseHookInput handles TaskCompleted"):
    val raw = baseInput("TaskCompleted")
    raw.task_id = "task-99"
    raw.success = true
    raw.summary = "Finished background task"

    parseInput(raw).map {
      case input: HookInput.TaskCompleted =>
        assertEquals(input.taskId, "task-99")
        assertEquals(input.success, true)
        assertEquals(input.summary, Some("Finished background task"))
      case other => fail(s"Expected TaskCompleted, got: $other")
    }

  test("parseHookInput handles Elicitation"):
    val raw = baseInput("Elicitation")
    raw.server_id = "mcp-server"
    raw.message = "Approve this request?"

    parseInput(raw).map {
      case input: HookInput.Elicitation =>
        assertEquals(input.serverId, "mcp-server")
        assertEquals(input.message, "Approve this request?")
      case other => fail(s"Expected Elicitation, got: $other")
    }

  test("parseHookInput handles ElicitationResult"):
    val raw = baseInput("ElicitationResult")
    raw.server_id = "mcp-server"
    raw.accepted = false

    parseInput(raw).map {
      case input: HookInput.ElicitationResult =>
        assertEquals(input.serverId, "mcp-server")
        assertEquals(input.accepted, false)
      case other => fail(s"Expected ElicitationResult, got: $other")
    }

  test("parseHookInput handles ConfigChange"):
    val raw = baseInput("ConfigChange")
    raw.key = "model"
    raw.value = "claude-sonnet-4-5"

    parseInput(raw).map {
      case input: HookInput.ConfigChange =>
        assertEquals(input.key, "model")
        assertEquals(input.value, Some("claude-sonnet-4-5"))
      case other => fail(s"Expected ConfigChange, got: $other")
    }

  test("parseHookInput handles WorktreeCreate"):
    val raw = baseInput("WorktreeCreate")
    raw.worktree_path = "/tmp/worktree-a"
    raw.branch = "feature/a"

    parseInput(raw).map {
      case input: HookInput.WorktreeCreate =>
        assertEquals(input.worktreePath, "/tmp/worktree-a")
        assertEquals(input.branch, "feature/a")
      case other => fail(s"Expected WorktreeCreate, got: $other")
    }

  test("parseHookInput handles WorktreeRemove"):
    val raw = baseInput("WorktreeRemove")
    raw.worktree_path = "/tmp/worktree-b"
    raw.branch = "feature/b"

    parseInput(raw).map {
      case input: HookInput.WorktreeRemove =>
        assertEquals(input.worktreePath, "/tmp/worktree-b")
        assertEquals(input.branch, "feature/b")
      case other => fail(s"Expected WorktreeRemove, got: $other")
    }
