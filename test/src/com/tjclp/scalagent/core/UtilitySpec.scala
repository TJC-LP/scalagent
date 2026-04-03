package com.tjclp.scalagent.core

import zio.json.ast.Json

class UtilitySpec extends munit.FunSuite:

  private val successTrace = TraceSummary(
    durationMs = 1500,
    numTurns = 3,
    numToolCalls = 2,
    numToolResults = 2,
    numDelegations = 0,
    toolNames = Set("Read"),
    delegationIds = Set.empty,
    costUsd = 0.005,
    isSuccess = true,
    resultText = Some("Done"),
    stopReason = Some("end_turn"),
    totalEvents = 5,
    nativeEventCount = 0
  )

  private val failTrace = successTrace.copy(isSuccess = false, costUsd = 0.05, durationMs = 10000)

  // --- Built-in utilities ---

  test("costMinimizing: lower cost = higher score"):
    val u = Utility.costMinimizing[String, String]
    val cheapScore = u.score("user", "result", successTrace)
    val expensiveScore = u.score("user", "result", failTrace)
    assert(cheapScore > expensiveScore)

  test("costMinimizing: zero cost = score 1.0"):
    val u = Utility.costMinimizing[String, String]
    val score = u.score("user", "result", successTrace.copy(costUsd = 0.0))
    assertEquals(score, 1.0)

  test("reliability: success = 1.0, failure = 0.0"):
    val u = Utility.reliability[String, String]
    assertEquals(u.score("user", "result", successTrace), 1.0)
    assertEquals(u.score("user", "result", failTrace), 0.0)

  test("latencyMinimizing: lower duration = higher score"):
    val u = Utility.latencyMinimizing[String, String]
    val fastScore = u.score("", "", successTrace)
    val slowScore = u.score("", "", failTrace)
    assert(fastScore > slowScore)

  test("simplicityBiased: fewer events = higher score"):
    val u = Utility.simplicityBiased[String, String]
    val simpleScore = u.score("", "", successTrace.copy(totalEvents = 2))
    val complexScore = u.score("", "", successTrace.copy(totalEvents = 100))
    assert(simpleScore > complexScore)

  // --- Weighted composite ---

  test("weighted: combines multiple utilities"):
    val u = Utility.weighted[String, String](
      Utility.reliability -> 0.7,
      Utility.costMinimizing -> 0.3
    )
    val score = u.score("user", "result", successTrace)
    // reliability gives 1.0, costMinimizing gives ~0.995
    assert(score > 0.9)
    assert(score <= 1.0)

  test("weighted: failure drops score"):
    val u = Utility.weighted[String, String](
      Utility.reliability -> 0.7,
      Utility.costMinimizing -> 0.3
    )
    val score = u.score("user", "result", failTrace)
    // reliability gives 0.0, so score is much lower
    assert(score < 0.5)

  // --- Custom utility ---

  test("from: custom function"):
    val u = Utility.from[String, String] { (principal, output, trace) =>
      if principal == "admin" then 1.0
      else if trace.isSuccess then 0.5
      else 0.0
    }
    assertEquals(u.score("admin", "", successTrace), 1.0)
    assertEquals(u.score("user", "", successTrace), 0.5)
    assertEquals(u.score("user", "", failTrace), 0.0)

  // --- Evaluation ---

  test("Evaluation.evaluate computes score and complexity"):
    val events = List(
      AgentEvent.ToolCall("Read", Json.Null),
      AgentEvent.ToolResult("Read", Json.Str("data"), false),
      AgentEvent.Completed(RunSummary(1000, 2, 0.003, true, Some("OK")))
    )
    val eval = Evaluation.evaluate("user", "OK", events, Utility.reliability[String, String])
    assertEquals(eval.score, 1.0)
    assertEquals(eval.trace.numToolCalls, 1)
    assertEquals(eval.complexity.totalNodes, 3)
    assertEquals(eval.principal, "user")
    assertEquals(eval.output, "OK")

  test("Evaluation.fromTrace uses pre-computed trace"):
    val eval = Evaluation.fromTrace("admin", "result", successTrace, Utility.costMinimizing)
    assert(eval.score > 0.9)
    assertEquals(eval.trace.numTurns, 3)
