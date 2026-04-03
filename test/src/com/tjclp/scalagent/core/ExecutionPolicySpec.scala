package com.tjclp.scalagent.core

import zio.Duration

class ExecutionPolicySpec extends munit.FunSuite:

  test("unbounded has no constraints"):
    val p = ExecutionPolicy.unbounded
    assertEquals(p.budget, Budget.Unlimited)
    assertEquals(p.maxTurns, None)
    assertEquals(p.deadline, None)
    assertEquals(p.stopStrategy, StopStrategy.Natural)
    assertEquals(p.fallback, FallbackPolicy.Fail)

  test("simple sets budget and maxTurns"):
    val p = ExecutionPolicy.simple(budgetUsd = 0.50, maxTurns = 10)
    assertEquals(p.budget, Budget.Usd(0.50))
    assertEquals(p.maxTurns, Some(10))

  test("default constructor has sensible defaults"):
    val p = ExecutionPolicy()
    assertEquals(p.budget, Budget.Unlimited)
    assertEquals(p.maxTurns, None)
    assertEquals(p.stopStrategy, StopStrategy.Natural)
    assertEquals(p.fallback, FallbackPolicy.Fail)

  test("childPolicy slices the budget"):
    val parent = ExecutionPolicy.simple(budgetUsd = 10.0, maxTurns = 20)
    val child = parent.childPolicy(0.3)
    assertEquals(child.budget, Budget.Usd(3.0))
    // maxTurns is inherited
    assertEquals(child.maxTurns, Some(20))

  test("childPolicy with Unlimited budget stays Unlimited"):
    val parent = ExecutionPolicy.unbounded
    val child = parent.childPolicy(0.5)
    assertEquals(child.budget, Budget.Unlimited)

  test("deadline can be set"):
    val p = ExecutionPolicy(deadline = Some(Duration.fromSeconds(30)))
    assertEquals(p.deadline, Some(Duration.fromSeconds(30)))

  test("StopStrategy enum cases"):
    assertEquals(StopStrategy.Natural.toString, "Natural")
    assertEquals(StopStrategy.FirstResponse.toString, "FirstResponse")
    assert(StopStrategy.Custom("myStrategy").isInstanceOf[StopStrategy])

  test("FallbackPolicy enum cases"):
    assertEquals(FallbackPolicy.Fail.toString, "Fail")
    assertEquals(FallbackPolicy.Retry(3).toString, "Retry(3)")
    assert(FallbackPolicy.Custom("escalate").isInstanceOf[FallbackPolicy])
