package com.tjclp.scalagent.core

import zio.json.*

class BudgetSpec extends munit.FunSuite:

  test("Budget.usd creates a Usd budget"):
    val b = Budget.usd(1.50)
    assertEquals(b, Budget.Usd(1.50))

  test("Budget.usd rejects negative amounts"):
    intercept[IllegalArgumentException] {
      Budget.usd(-1.0)
    }

  test("Budget.zero is Usd(0.0)"):
    assertEquals(Budget.zero, Budget.Usd(0.0))
    assert(Budget.zero.isExhausted)

  test("Budget.Unlimited is never exhausted"):
    assert(!Budget.Unlimited.isExhausted)

  test("toUsd returns None for Unlimited"):
    assertEquals(Budget.Unlimited.toUsd, None)

  test("toUsd returns Some for Usd"):
    assertEquals(Budget.usd(5.0).toUsd, Some(5.0))

  // --- Arithmetic ---

  test("Usd + Usd adds amounts"):
    assertEquals(Budget.usd(1.0) + Budget.usd(2.0), Budget.Usd(3.0))

  test("Unlimited + anything is Unlimited"):
    assertEquals(Budget.Unlimited + Budget.usd(5.0), Budget.Unlimited)
    assertEquals(Budget.usd(5.0) + Budget.Unlimited, Budget.Unlimited)

  test("Usd - Usd subtracts, floored at zero"):
    assertEquals(Budget.usd(3.0) - Budget.usd(1.0), Budget.Usd(2.0))
    assertEquals(Budget.usd(1.0) - Budget.usd(5.0), Budget.Usd(0.0))

  test("Unlimited - anything is Unlimited"):
    assertEquals(Budget.Unlimited - Budget.usd(100.0), Budget.Unlimited)

  test("anything - Unlimited is zero"):
    assertEquals(Budget.usd(100.0) - Budget.Unlimited, Budget.Usd(0.0))

  test("Unlimited - Unlimited is Unlimited"):
    assertEquals(Budget.Unlimited - Budget.Unlimited, Budget.Unlimited)

  // --- Slice ---

  test("slice of Usd returns fraction"):
    assertEquals(Budget.usd(10.0).slice(0.3), Budget.Usd(3.0))

  test("slice of Unlimited is Unlimited"):
    assertEquals(Budget.Unlimited.slice(0.5), Budget.Unlimited)

  test("slice rejects out-of-range fractions"):
    intercept[IllegalArgumentException] { Budget.usd(1.0).slice(-0.1) }
    intercept[IllegalArgumentException] { Budget.usd(1.0).slice(1.1) }

  // --- Remaining ---

  test("remaining subtracts spent from Usd"):
    assertEquals(Budget.usd(10.0).remaining(3.0), Budget.Usd(7.0))
    assertEquals(Budget.usd(2.0).remaining(5.0), Budget.Usd(0.0))

  test("remaining of Unlimited is Unlimited"):
    assertEquals(Budget.Unlimited.remaining(999.0), Budget.Unlimited)

  // --- JSON round-trip ---

  test("Budget.Usd serializes as number"):
    assertEquals(Budget.usd(1.5).toJson, "1.5")

  test("Budget.Unlimited serializes as null"):
    assertEquals(Budget.Unlimited.toJson, "null")

  test("Budget round-trips through JSON"):
    val usd = Budget.usd(42.0)
    assertEquals(usd.toJson.fromJson[Budget], Right(usd))
    assertEquals(Budget.Unlimited.toJson.fromJson[Budget], Right(Budget.Unlimited))
