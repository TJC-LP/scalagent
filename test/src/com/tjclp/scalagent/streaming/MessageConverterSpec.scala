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

  test("unknown top-level message types are preserved"):
    val raw = js.Dynamic.literal(
      `type` = "future_message",
      subtype = "preview",
      uuid = "msg-unknown",
      session_id = "session-1",
      parent_tool_use_id = "toolu_123",
      payload = js.Dynamic.literal(enabled = true)
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.Unknown(envelope) =>
        assertEquals(envelope.rawType, "future_message")
        assertEquals(envelope.rawSubtype, Some("preview"))
        assertEquals(envelope.uuid.map(_.value), Some("msg-unknown"))
        assertEquals(envelope.sessionId.map(_.value), Some("session-1"))
        assertEquals(envelope.parentToolUseId.map(_.value), Some("toolu_123"))
      case other =>
        fail(s"Expected unknown message envelope, got: $other")

  test("unknown system subtypes are preserved"):
    val raw = js.Dynamic.literal(
      `type` = "system",
      subtype = "future_system_event",
      uuid = "msg-3",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.System(SystemEvent.Unknown(envelope), _, _) =>
        assertEquals(envelope.rawType, "system")
        assertEquals(envelope.rawSubtype, Some("future_system_event"))
      case other =>
        fail(s"Expected unknown system event, got: $other")

  test("unknown content block subtypes are preserved"):
    val raw = js.Dynamic.literal(
      `type` = "assistant",
      message = js.Dynamic.literal(
        id = "msg_api_2",
        role = "assistant",
        content = js.Array(js.Dynamic.literal(`type` = "chart", points = js.Array(1, 2, 3))),
        model = "claude-sonnet-4-20250514"
      ),
      uuid = "msg-4",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        message.content.headOption match
          case Some(ContentBlock.Unknown(envelope)) =>
            assertEquals(envelope.rawType, "chart")
          case other =>
            fail(s"Expected unknown content block, got: $other")
      case other =>
        fail(s"Expected assistant message, got: $other")

  test("unknown delta subtypes are preserved"):
    val raw = js.Dynamic.literal(
      `type` = "stream_event",
      event = js.Dynamic.literal(
        `type` = "content_block_delta",
        delta = js.Dynamic.literal(
          `type` = "rich_delta",
          fragment = "preview"
        )
      ),
      uuid = "msg-5",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.StreamEvent(event, _, _, _) =>
        event.delta match
          case Some(StreamDelta.Unknown(envelope)) =>
            assertEquals(envelope.rawType, "rich_delta")
          case other =>
            fail(s"Expected unknown delta, got: $other")
      case other =>
        fail(s"Expected stream event, got: $other")

  test("parses image url content blocks"):
    val raw = js.Dynamic.literal(
      `type` = "assistant",
      message = js.Dynamic.literal(
        id = "msg_api_3",
        role = "assistant",
        content = js.Array(
          js.Dynamic.literal(
            `type` = "image",
            source = js.Dynamic.literal(
              `type` = "url",
              url = "https://example.com/test.png",
              media_type = "image/png"
            )
          )
        ),
        model = "claude-sonnet-4-20250514"
      ),
      uuid = "msg-6",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        message.content.headOption match
          case Some(ContentBlock.Image(ImageSource.Url(url), mediaType)) =>
            assertEquals(url, "https://example.com/test.png")
            assertEquals(mediaType, "image/png")
          case other =>
            fail(s"Expected url image block, got: $other")
      case other =>
        fail(s"Expected assistant message, got: $other")

  test("parses image base64 content blocks"):
    val raw = js.Dynamic.literal(
      `type` = "assistant",
      message = js.Dynamic.literal(
        id = "msg_api_4",
        role = "assistant",
        content = js.Array(
          js.Dynamic.literal(
            `type` = "image",
            source = js.Dynamic.literal(
              `type` = "base64",
              data = "Zm9vYmFy",
              media_type = "image/png"
            )
          )
        ),
        model = "claude-sonnet-4-20250514"
      ),
      uuid = "msg-7",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        message.content.headOption match
          case Some(ContentBlock.Image(ImageSource.Base64(data, sourceMediaType), mediaType)) =>
            assertEquals(data, "Zm9vYmFy")
            assertEquals(sourceMediaType, "image/png")
            assertEquals(mediaType, "image/png")
          case other =>
            fail(s"Expected base64 image block, got: $other")
      case other =>
        fail(s"Expected assistant message, got: $other")

  test("missing top-level type becomes a terminal parse failure"):
    intercept[MessageConverter.MessageParseException] {
      MessageConverter.fromRaw(
        js.Dynamic.literal(
          uuid = "msg-bad",
          session_id = "session-1"
        )
      )
    }

  test("parses prompt_suggestion message"):
    val raw = js.Dynamic.literal(
      `type` = "prompt_suggestion",
      suggestion = "Run tests for changed files",
      uuid = "msg-8",
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
      uuid = "msg-9",
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
      uuid = "msg-10",
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
      uuid = "msg-11",
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
      uuid = "msg-12",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case ts: AgentMessage.TaskStarted =>
        assertEquals(ts.taskId, "task-1")
        assertEquals(ts.description, "Running code review")
        assertEquals(ts.toolUseId, None)
        assertEquals(ts.taskType, None)
        assertEquals(ts.prompt, None)
      case other =>
        fail(s"Expected TaskStarted, got: $other")

  test("parses task_started with optional fields"):
    val raw = js.Dynamic.literal(
      `type` = "system",
      subtype = "task_started",
      task_id = "task-2",
      description = "Running analysis",
      uuid = "msg-14",
      session_id = "session-1",
      tool_use_id = "tu-abc",
      task_type = "background",
      prompt = "Analyze this code"
    )

    MessageConverter.fromRaw(raw) match
      case ts: AgentMessage.TaskStarted =>
        assertEquals(ts.toolUseId, Some("tu-abc"))
        assertEquals(ts.taskType, Some("background"))
        assertEquals(ts.prompt, Some("Analyze this code"))
      case other =>
        fail(s"Expected TaskStarted, got: $other")

  test("parses task_progress message"):
    val raw = js.Dynamic.literal(
      `type` = "system",
      subtype = "task_progress",
      task_id = "task-1",
      description = "50% complete",
      uuid = "msg-13",
      session_id = "session-1"
    )

    MessageConverter.fromRaw(raw) match
      case AgentMessage.TaskProgress(taskId, progress, _, _) =>
        assertEquals(taskId, "task-1")
        assertEquals(progress, "50% complete")
      case other =>
        fail(s"Expected TaskProgress, got: $other")
