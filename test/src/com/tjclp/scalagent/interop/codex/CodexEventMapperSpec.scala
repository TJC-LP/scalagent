package com.tjclp.scalagent.interop.codex

import zio.json.ast.Json
import com.tjclp.scalagent.core.{AgentEvent, RunSummary}
import com.tjclp.scalagent.codex.*

class CodexEventMapperSpec extends munit.FunSuite:

  private def state = CodexEventMapper.createState()

  // --- Item Started → AgentEvent ---

  test("ItemStarted(AgentMessage) → TextDelta"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "Hello world")),
      state
    )
    assertEquals(events, List(AgentEvent.TextDelta("Hello world")))

  test("ItemStarted(CommandExecution) → ToolCall"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemStarted(CodexItem.CommandExecution("1", "ls -la", "", None, "in_progress")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolCall(name, args) =>
        assertEquals(name, "command")
        assert(args.toString.contains("ls -la"))
      case other => fail(s"Expected ToolCall, got $other")

  test("ItemStarted(McpToolCall) → ToolCall with prefixed name"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemStarted(CodexItem.McpToolCall("1", "myserver", "read_file", Json.Obj("path" -> Json.Str("/tmp")), None, None, "in_progress")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolCall(name, _) =>
        assertEquals(name, "mcp:myserver:read_file")
      case other => fail(s"Expected ToolCall, got $other")

  test("ItemStarted(WebSearch) → ToolCall"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemStarted(CodexItem.WebSearch("1", "scala 3 capture checking")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolCall(name, _) => assertEquals(name, "web_search")
      case other => fail(s"Expected ToolCall, got $other")

  test("ItemStarted(Reasoning) → Native"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemStarted(CodexItem.Reasoning("1", "thinking about it...")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native("reasoning", _) => () // ok
      case other => fail(s"Expected Native(reasoning), got $other")

  test("ItemStarted(FileChange) → ToolCall"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemStarted(CodexItem.FileChange("1", List(FileUpdate("src/main.scala", "update")), "completed")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolCall("file_change", _) => () // ok
      case other => fail(s"Expected ToolCall(file_change), got $other")

  // --- Item Completed → AgentEvent ---

  test("ItemCompleted(CommandExecution, success) → ToolResult(isError=false)"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemCompleted(CodexItem.CommandExecution("1", "ls", "file1\nfile2", Some(0), "completed")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolResult("command", _, isError) =>
        assertEquals(isError, false)
      case other => fail(s"Expected ToolResult, got $other")

  test("ItemCompleted(CommandExecution, failed) → ToolResult(isError=true)"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemCompleted(CodexItem.CommandExecution("1", "bad-cmd", "not found", Some(127), "failed")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolResult("command", _, isError) =>
        assertEquals(isError, true)
      case other => fail(s"Expected ToolResult, got $other")

  test("ItemCompleted(McpToolCall, success) → ToolResult"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemCompleted(CodexItem.McpToolCall("1", "srv", "tool", Json.Null, Some(Json.Str("result")), None, "completed")),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.ToolResult(name, value, isError) =>
        assertEquals(name, "mcp:srv:tool")
        assertEquals(isError, false)
      case other => fail(s"Expected ToolResult, got $other")

  test("ItemCompleted(McpToolCall, failed) → ToolResult(isError=true)"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemCompleted(CodexItem.McpToolCall("1", "srv", "tool", Json.Null, None, Some("timeout"), "failed")),
      state
    )
    events.head match
      case AgentEvent.ToolResult(_, _, isError) => assertEquals(isError, true)
      case other => fail(s"Expected ToolResult, got $other")

  test("ItemCompleted(AgentMessage) → empty (already streamed)"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ItemCompleted(CodexItem.AgentMessage("1", "final answer")),
      state
    )
    assertEquals(events, Nil)

  // --- Turn events ---

  test("TurnCompleted → Completed(RunSummary) + Native(usage)"):
    val s = state
    s.startTimeMs = System.currentTimeMillis() - 1000 // simulate 1s elapsed
    val events = CodexEventMapper.mapEvent(
      CodexEvent.TurnCompleted(CodexUsage(100, 20, 50)),
      s
    )
    assertEquals(events.size, 2)
    events(0) match
      case AgentEvent.Native("codex.usage", payload) =>
        assert(payload.toString.contains("100"))
      case other => fail(s"Expected Native(codex.usage), got $other")
    events(1) match
      case AgentEvent.Completed(summary) =>
        assert(summary.isSuccess)
        assertEquals(summary.stopReason, Some("turn.completed"))
        assert(summary.durationMs >= 900) // at least ~1s
      case other => fail(s"Expected Completed, got $other")

  test("TurnFailed → Completed(isSuccess=false)"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.TurnFailed("model error"),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Completed(summary) =>
        assertEquals(summary.isSuccess, false)
        assertEquals(summary.resultText, Some("model error"))
      case other => fail(s"Expected Completed, got $other")

  test("ThreadStarted → Native"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.ThreadStarted("thread-123"),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native("thread.started", _) => () // ok
      case other => fail(s"Expected Native, got $other")

  test("Error → Native"):
    val events = CodexEventMapper.mapEvent(
      CodexEvent.Error("something broke"),
      state
    )
    assertEquals(events.size, 1)
    events.head match
      case AgentEvent.Native("error", _) => () // ok
      case other => fail(s"Expected Native, got $other")

  // --- State tracking ---

  test("state tracks lastAgentMessage across events"):
    val s = state
    CodexEventMapper.mapEvent(CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "first")), s)
    assertEquals(s.lastAgentMessage, Some("first"))
    CodexEventMapper.mapEvent(CodexEvent.ItemUpdated(CodexItem.AgentMessage("1", "first updated")), s)
    assertEquals(s.lastAgentMessage, Some("first updated"))
