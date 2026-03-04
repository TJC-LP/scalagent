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

  test("parses rate_limit_event message"):
    val raw = js.Dynamic.literal(
      `type` = "rate_limit_event",
      rate_limit_info = js.Dynamic.literal(
        status = "allowed_warning",
        resetsAt = 12345,
        rateLimitType = "five_hour",
        utilization = 0.8
      ),
      uuid = "msg-4",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.RateLimitEvent(retryAfterMs, model, status, resetsAt, rateLimitType, utilization, _, _) =>
        assertEquals(retryAfterMs, None)
        assertEquals(model, None)
        assertEquals(status, Some("allowed_warning"))
        assertEquals(resetsAt, Some(12345L))
        assertEquals(rateLimitType, Some("five_hour"))
        assertEquals(utilization, Some(0.8))
      case other =>
        fail(s"Expected RateLimitEvent, got: $other")

  test("parses local_command_output message"):
    val raw = js.Dynamic.literal(
      `type` = "system",
      subtype = "local_command_output",
      content = "command output",
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
      `type` = "system",
      subtype = "elicitation_complete",
      mcp_server_name = "mcp-server",
      elicitation_id = "elic-789",
      uuid = "msg-6",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.ElicitationComplete(mcpServerName, elicitationId, _, _) =>
        assertEquals(mcpServerName, "mcp-server")
        assertEquals(elicitationId, "elic-789")
      case other =>
        fail(s"Expected ElicitationComplete, got: $other")

  test("parses task_started message"):
    val raw = js.Dynamic.literal(
      `type` = "system",
      subtype = "task_started",
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
      `type` = "system",
      subtype = "task_progress",
      task_id = "task-1",
      description = "50% complete",
      uuid = "msg-8",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.TaskProgress(taskId, progress, _, _) =>
        assertEquals(taskId, "task-1")
        assertEquals(progress, "50% complete")
      case other =>
        fail(s"Expected TaskProgress, got: $other")
