package com.tjclp.scalagent.streaming

import munit.FunSuite
import scala.scalajs.js
import com.tjclp.scalagent.messages.*

class MessageConverterSpec extends FunSuite:

  private def usage: js.Dynamic =
    js.Dynamic.literal(
      input_tokens = 100,
      output_tokens = 25
    )

  test("result stop_reason null is normalized to None"):
    val raw = js.Dynamic.literal(
      `type` = "result",
      subtype = "success",
      duration_ms = 1200,
      duration_api_ms = 900,
      num_turns = 2,
      result = "ok",
      total_cost_usd = 0.01,
      usage = usage,
      modelUsage = js.undefined,
      permission_denials = js.undefined,
      stop_reason = null,
      uuid = "msg-1",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.Result(outcome: ResultOutcome.Success, _, _, _) =>
        assertEquals(outcome.stopReason, None)
      case other =>
        fail(s"Expected result success message, got: $other")

  test("assistant stop_reason null is normalized to None"):
    val raw = js.Dynamic.literal(
      `type` = "assistant",
      message = js.Dynamic.literal(
        id = "msg_api_1",
        role = "assistant",
        content = js.Array(js.Dynamic.literal(`type` = "text", text = "hello")),
        model = "claude-sonnet-4-20250514",
        stop_reason = null,
        stop_sequence = null,
        usage = usage
      ),
      uuid = "msg-2",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        assertEquals(message.stopReason, None)
      case other =>
        fail(s"Expected assistant message, got: $other")

  test("parses prompt_suggestion message"):
    val raw = js.Dynamic.literal(
      `type` = "prompt_suggestion",
      suggestion = "Run tests for changed files",
      uuid = "msg-3",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.PromptSuggestion(suggestion, _, _) =>
        assertEquals(suggestion, "Run tests for changed files")
      case other =>
        fail(s"Expected PromptSuggestion, got: $other")

  test("parses rate_limit message"):
    val raw = js.Dynamic.literal(
      `type` = "rate_limit",
      retry_after_ms = 3500,
      model = "claude-sonnet-4-5-20250929",
      uuid = "msg-4",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.RateLimitEvent(retryAfterMs, model, _, _) =>
        assertEquals(retryAfterMs, 3500L)
        assertEquals(model, "claude-sonnet-4-5-20250929")
      case other =>
        fail(s"Expected RateLimitEvent, got: $other")

  test("parses local_command_output message"):
    val raw = js.Dynamic.literal(
      `type` = "local_command_output",
      output = "command output",
      uuid = "msg-5",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.LocalCommandOutput(output, _, _) =>
        assertEquals(output, "command output")
      case other =>
        fail(s"Expected LocalCommandOutput, got: $other")

  test("parses elicitation_complete message"):
    val raw = js.Dynamic.literal(
      `type` = "elicitation_complete",
      server_id = "mcp-server",
      accepted = true,
      uuid = "msg-6",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.ElicitationComplete(serverId, accepted, _, _) =>
        assertEquals(serverId, "mcp-server")
        assertEquals(accepted, true)
      case other =>
        fail(s"Expected ElicitationComplete, got: $other")

  test("parses task_started message"):
    val raw = js.Dynamic.literal(
      `type` = "task_started",
      task_id = "task-1",
      description = "Running code review",
      uuid = "msg-7",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.TaskStarted(taskId, description, _, _) =>
        assertEquals(taskId, "task-1")
        assertEquals(description, "Running code review")
      case other =>
        fail(s"Expected TaskStarted, got: $other")

  test("parses task_progress message"):
    val raw = js.Dynamic.literal(
      `type` = "task_progress",
      task_id = "task-1",
      progress = "50%",
      uuid = "msg-8",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.TaskProgress(taskId, progress, _, _) =>
        assertEquals(taskId, "task-1")
        assertEquals(progress, "50%")
      case other =>
        fail(s"Expected TaskProgress, got: $other")
