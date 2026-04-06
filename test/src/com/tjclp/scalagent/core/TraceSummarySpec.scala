package com.tjclp.scalagent.core

import zio.json.ast.Json

class TraceSummarySpec extends munit.FunSuite:

  private def sampleEvents: List[AgentEvent] = List(
    AgentEvent.TextDelta("Hello"),
    AgentEvent.ToolCall("Read", Json.Obj("path" -> Json.Str("/tmp/x"))),
    AgentEvent.ToolResult("Read", Json.Str("contents"), false),
    AgentEvent.ToolCall("Grep", Json.Obj("pattern" -> Json.Str("foo"))),
    AgentEvent.ToolResult("Grep", Json.Str("line 42"), false),
    AgentEvent.DelegationStarted("analyzer", "child-1"),
    AgentEvent.DelegationFinished("child-1", "completed"),
    AgentEvent.Status("processing"),
    AgentEvent.Native("system", Json.Null),
    AgentEvent.Completed(RunSummary(1500, 3, 0.005, true, Some("Done"), Some("end_turn")))
  )

  test("fromEvents counts tool calls"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    assertEquals(trace.numToolCalls, 2)

  test("fromEvents counts tool results"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    assertEquals(trace.numToolResults, 2)

  test("fromEvents collects tool names"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    assertEquals(trace.toolNames, Set("Read", "Grep"))

  test("fromEvents counts delegations"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    assertEquals(trace.numDelegations, 1)
    assertEquals(trace.delegationIds, Set("child-1"))

  test("fromEvents counts native events"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    assertEquals(trace.nativeEventCount, 1)

  test("fromEvents extracts RunSummary fields"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    assertEquals(trace.durationMs, 1500L)
    assertEquals(trace.numTurns, 3)
    assertEquals(trace.costUsd, 0.005)
    assert(trace.isSuccess)
    assertEquals(trace.resultText, Some("Done"))
    assertEquals(trace.stopReason, Some("end_turn"))

  test("fromEvents counts total events"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    assertEquals(trace.totalEvents, 10)

  test("fromEvents with empty list produces zero trace"):
    val trace = TraceSummary.fromEvents(Nil)
    assertEquals(trace.numToolCalls, 0)
    assertEquals(trace.totalEvents, 0)
    assert(!trace.isSuccess)

  test("fromRunSummary creates minimal trace"):
    val summary = RunSummary(2000, 5, 0.01, true, Some("result"))
    val trace = TraceSummary.fromRunSummary(summary)
    assertEquals(trace.durationMs, 2000L)
    assertEquals(trace.numTurns, 5)
    assertEquals(trace.numToolCalls, 0)  // no event detail
    assertEquals(trace.totalEvents, 0)

  // --- Complexity ---

  test("Complexity.fromTrace computes graph metrics"):
    val trace = TraceSummary.fromEvents(sampleEvents)
    val complexity = Complexity.fromTrace(trace)
    assertEquals(complexity.totalNodes, 10)
    assertEquals(complexity.toolCallNodes, 2)
    assertEquals(complexity.delegationNodes, 1)
    assert(complexity.graphDensity > 0.0)
    assert(complexity.graphDensity < 1.0)

  test("Complexity.zero is all zeros"):
    assertEquals(Complexity.zero.totalNodes, 0)
    assertEquals(Complexity.zero.graphDensity, 0.0)
