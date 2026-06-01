package com.tjclp.scalagent.interop.a2a

import zio.json.ast.Json
import com.tjclp.scalagent.core.AgentEvent
import com.tjclp.scalagent.a2a.*

class A2AEventMapperSpec extends munit.FunSuite:

  private val testTaskId = TaskId("task-123")
  private val testContextId = ContextId("ctx-456")

  // --- TaskStatusUpdate mapping ---

  test("Working status with message maps to Status"):
    val msg = A2AMessage.agentText("Analyzing...", Some(testContextId))
    val event = A2AResponse.StreamEvent.TaskStatusUpdate(
      testTaskId, testContextId,
      TaskStatus.working(Some(msg)),
      `final` = false
    )
    val events = A2AEventMapper.mapStreamEvent(event)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Status(text, _) => assert(text.contains("Analyzing"))
      case other => fail(s"Expected Status, got $other")

  test("Completed status maps to Completed with isSuccess=true"):
    val msg = A2AMessage.agentText("Done!", Some(testContextId))
    val event = A2AResponse.StreamEvent.TaskStatusUpdate(
      testTaskId, testContextId,
      TaskStatus.completed(msg),
      `final` = true
    )
    val events = A2AEventMapper.mapStreamEvent(event)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Completed(summary) =>
        assert(summary.isSuccess)
        assertEquals(summary.resultText, Some("Done!"))
        assertEquals(summary.stopReason, Some("Completed"))
      case other => fail(s"Expected Completed, got $other")

  test("Failed status maps to Completed with isSuccess=false"):
    val msg = A2AMessage.agentText("Error occurred", Some(testContextId))
    val event = A2AResponse.StreamEvent.TaskStatusUpdate(
      testTaskId, testContextId,
      TaskStatus.failed(msg),
      `final` = true
    )
    val events = A2AEventMapper.mapStreamEvent(event)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Completed(summary) =>
        assert(!summary.isSuccess)
        assertEquals(summary.stopReason, Some("Failed"))
      case other => fail(s"Expected Completed, got $other")

  // --- TaskMessage mapping ---

  test("TaskMessage with text maps to TextDelta"):
    val msg = A2AMessage.agentText("Hello from remote agent", Some(testContextId))
    val event = A2AResponse.StreamEvent.TaskMessage(
      testTaskId, testContextId, msg
    )
    val events = A2AEventMapper.mapStreamEvent(event)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.TextDelta(text, _) =>
        assertEquals(text, "Hello from remote agent")
      case other => fail(s"Expected TextDelta, got $other")

  test("direct message stream event with text maps to TextDelta"):
    val msg = A2AMessage.agentText("Direct response", Some(testContextId))
    val event = A2AResponse.StreamEvent.Message(msg)
    val events = A2AEventMapper.mapStreamEvent(event)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.TextDelta(text, _) =>
        assertEquals(text, "Direct response")
      case other => fail(s"Expected TextDelta, got $other")

  // --- Artifact mapping ---

  test("TaskArtifactUpdate maps to Native"):
    val artifact = Artifact(
      artifactId = "art-1",
      parts = List(Part.Text("some content"))
    )
    val event = A2AResponse.StreamEvent.TaskArtifactUpdate(
      testTaskId, testContextId, artifact
    )
    val events = A2AEventMapper.mapStreamEvent(event)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native(tag, _) =>
        assertEquals(tag, "a2a.artifact")
      case other => fail(s"Expected Native('a2a.artifact'), got $other")

  // --- Snapshot mapping ---

  test("TaskSnapshot maps to Native"):
    val task = A2ATask(
      id = testTaskId,
      contextId = testContextId,
      status = TaskStatus.submitted
    )
    val event = A2AResponse.StreamEvent.TaskSnapshot(task)
    val events = A2AEventMapper.mapStreamEvent(event)
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native(tag, _) =>
        assertEquals(tag, "a2a.snapshot")
      case other => fail(s"Expected Native('a2a.snapshot'), got $other")

  // --- Reverse mapping ---

  test("TextDelta converts to A2A agent message"):
    val msg = A2AEventMapper.toA2AMessage(AgentEvent.TextDelta("hello"))
    assert(msg.isDefined)
    assertEquals(msg.get.text, "hello")

  test("Completed with result converts to A2A agent message"):
    val summary = com.tjclp.scalagent.core.RunSummary(0, 1, 0.0, true, Some("result text"))
    val msg = A2AEventMapper.toA2AMessage(AgentEvent.Completed(summary))
    assert(msg.isDefined)
    assertEquals(msg.get.text, "result text")

  test("Native events produce no A2A message"):
    val msg = A2AEventMapper.toA2AMessage(AgentEvent.Native("test", Json.Null))
    assert(msg.isEmpty)
