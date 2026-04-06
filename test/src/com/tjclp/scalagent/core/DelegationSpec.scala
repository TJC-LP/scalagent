package com.tjclp.scalagent.core

class DelegationSpec extends munit.FunSuite:

  // --- DelegationPolicy construction ---

  test("default gives 50% budget"):
    assertEquals(DelegationPolicy.default.budgetFraction, 0.5)
    assertEquals(DelegationPolicy.default.maxChildTurns, None)

  test("custom delegation policy"):
    val pol = DelegationPolicy(
      budgetFraction = 0.3,
      maxChildTurns = Some(5)
    )
    assertEquals(pol.budgetFraction, 0.3)
    assertEquals(pol.maxChildTurns, Some(5))

  // --- Validation ---

  test("rejects zero budget fraction"):
    intercept[IllegalArgumentException] {
      DelegationPolicy(budgetFraction = 0.0)
    }

  test("rejects negative budget fraction"):
    intercept[IllegalArgumentException] {
      DelegationPolicy(budgetFraction = -0.5)
    }

  test("rejects budget fraction > 1"):
    intercept[IllegalArgumentException] {
      DelegationPolicy(budgetFraction = 1.5)
    }

  test("accepts budget fraction = 1.0"):
    val pol = DelegationPolicy(budgetFraction = 1.0)
    assertEquals(pol.budgetFraction, 1.0)

  // --- ToolSurface composition ---

  test("ToolSurface.empty has no tools"):
    assert(ToolSurface.empty.isEmpty)
    assertEquals(ToolSurface.empty.size, 0)

  test("ToolSurface ++ composes"):
    val a = ToolSurface.empty
    val b = ToolSurface.empty
    assertEquals((a ++ b).size, 0)

  // --- Budget slicing integration ---

  test("Budget.slice works with delegation fractions"):
    val parent = Budget.usd(10.0)
    val child = parent.slice(0.3)
    assertEquals(child, Budget.Usd(3.0))

  test("Unlimited budget sliced stays Unlimited"):
    val child = Budget.Unlimited.slice(0.5)
    assertEquals(child, Budget.Unlimited)

  test("ToolSurface ++ deduplicates by name"):
    import com.tjclp.scalagent.tools.ToolName
    val surface = ToolSurface(Nil, List(ToolName.Read, ToolName.Grep))
    val combined = surface ++ surface
    assertEquals(combined.allowedTools, List(ToolName.Read, ToolName.Grep))
