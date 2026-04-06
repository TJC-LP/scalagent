package com.tjclp.scalagent.interop.claude

import zio.json.ast.Json
import com.tjclp.scalagent.TestFixtures
import com.tjclp.scalagent.core.{AgentEvent, RunSummary}
import com.tjclp.scalagent.messages.*

class EventMapperSpec extends munit.FunSuite:

  // --- Assistant messages ---

  test("Assistant with text normalizes to TextDelta"):
    val events = EventMapper.mapMessage(TestFixtures.assistantMessage)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.TextDelta(text) =>
        assertEquals(text, "Hello, I'm Claude!")
      case other => fail(s"Expected TextDelta, got $other")

  test("Assistant with text + tool use normalizes to TextDelta + ToolCall"):
    val events = EventMapper.mapMessage(TestFixtures.assistantMessageWithToolUse)
    assertEquals(events.size, 2)
    events(0) match
      case AgentEvent.TextDelta(text) =>
        assertEquals(text, "Hello, I'm Claude!")
      case other => fail(s"Expected TextDelta, got $other")
    events(1) match
      case AgentEvent.ToolCall(name, args) =>
        assertEquals(name, "Read")
        assert(args.toString.contains("file_path"))
      case other => fail(s"Expected ToolCall, got $other")

  // --- Synthetic user messages (tool results) ---

  test("Synthetic user message normalizes to ToolResult"):
    val events = EventMapper.mapMessage(TestFixtures.syntheticUserMessage)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolResult(name, value, isError) =>
        assertEquals(name, TestFixtures.testToolUseId.value)
        assertEquals(isError, false)
      case other => fail(s"Expected ToolResult, got $other")

  // --- Non-synthetic user messages ---

  test("Non-synthetic user message becomes Native"):
    val events = EventMapper.mapMessage(TestFixtures.userMessage)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native(tag, _) =>
        assertEquals(tag, "user")
      case other => fail(s"Expected Native('user'), got $other")

  // --- Result messages ---

  test("Result Success normalizes to Completed with isSuccess=true"):
    val events = EventMapper.mapMessage(TestFixtures.resultSuccess)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Completed(summary) =>
        assert(summary.isSuccess)
        assertEquals(summary.numTurns, 3)
        assertEquals(summary.costUsd, 0.005)
        assertEquals(summary.durationMs, 1500L)
        assertEquals(summary.resultText, Some("Task completed successfully!"))
      case other => fail(s"Expected Completed, got $other")

  test("Result Error normalizes to Completed with isSuccess=false"):
    val events = EventMapper.mapMessage(TestFixtures.resultError)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Completed(summary) =>
        assert(!summary.isSuccess)
        assertEquals(summary.numTurns, 1)
      case other => fail(s"Expected Completed, got $other")

  // --- Stream events ---

  test("StreamEvent with TextDelta normalizes to TextDelta"):
    val streamEvent = AgentMessage.StreamEvent(
      event = RawStreamEvent("content_block_delta", Some(0), None, Some(StreamDelta.TextDelta("hello"))),
      parentToolUseId = None,
      uuid = TestFixtures.testMessageUuid,
      sessionId = TestFixtures.testSessionId
    )
    val events = EventMapper.mapMessage(streamEvent)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.TextDelta(text) =>
        assertEquals(text, "hello")
      case other => fail(s"Expected TextDelta, got $other")

  test("StreamEvent without TextDelta becomes Native"):
    val streamEvent = AgentMessage.StreamEvent(
      event = RawStreamEvent("content_block_start", Some(0), None, None),
      parentToolUseId = None,
      uuid = TestFixtures.testMessageUuid,
      sessionId = TestFixtures.testSessionId
    )
    val events = EventMapper.mapMessage(streamEvent)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native(tag, _) =>
        assertEquals(tag, "stream_event")
      case other => fail(s"Expected Native('stream_event'), got $other")

  // --- Task/delegation events ---

  test("TaskStarted normalizes to DelegationStarted"):
    val msg = AgentMessage.TaskStarted(
      taskId = "task-123",
      description = "Analyzing code",
      uuid = TestFixtures.testMessageUuid,
      sessionId = TestFixtures.testSessionId,
      toolUseId = None,
      taskType = None,
      prompt = None,
      workflowName = None
    )
    val events = EventMapper.mapMessage(msg)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.DelegationStarted(label, childId) =>
        assertEquals(label, "Analyzing code")
        assertEquals(childId, "task-123")
      case other => fail(s"Expected DelegationStarted, got $other")

  test("TaskNotification normalizes to DelegationFinished"):
    val msg = AgentMessage.TaskNotification(
      taskId = "task-123",
      status = TaskStatus.Completed,
      outputFile = "",
      summary = "Done",
      toolUseId = None,
      usage = None,
      uuid = TestFixtures.testMessageUuid,
      sessionId = TestFixtures.testSessionId
    )
    val events = EventMapper.mapMessage(msg)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.DelegationFinished(childId, status) =>
        assertEquals(childId, "task-123")
        assertEquals(status, "completed")
      case other => fail(s"Expected DelegationFinished, got $other")

  // --- Tool progress ---

  test("ToolProgress normalizes to Status"):
    val msg = AgentMessage.ToolProgress(
      toolUseId = TestFixtures.testToolUseId,
      toolName = com.tjclp.scalagent.tools.ToolName.Read,
      parentToolUseId = None,
      elapsedTimeSeconds = 2.5,
      taskId = None,
      uuid = TestFixtures.testMessageUuid,
      sessionId = TestFixtures.testSessionId
    )
    val events = EventMapper.mapMessage(msg)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Status(value) =>
        assert(value.contains("Read"))
        assert(value.contains("2.5"))
      case other => fail(s"Expected Status, got $other")

  // --- Native passthrough ---

  test("Unknown message becomes Native"):
    val msg = AgentMessage.Unknown(UnknownEnvelope(raw = Json.Obj(), rawType = "weird_type"))
    val events = EventMapper.mapMessage(msg)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native(tag, _) =>
        assertEquals(tag, "unknown")
      case other => fail(s"Expected Native('unknown'), got $other")
