package com.tjclp.scalagent.experimental

import zio.blocks.scope.{Scope, Unscoped}
import scala.compiletime.testing.typeChecks

/** Tests for zio-blocks/scope based capability safety.
  *
  * Verifies that capabilities allocated within a scope cannot escape,
  * and that the $ operator enforces safe usage patterns.
  */
class ScopedCapabilitiesSpec extends munit.FunSuite:

  // --- Basic scope usage ---

  test("FileSandbox can be allocated and used within scope"):
    val root = Scope.global.scoped { scope =>
      import scope.*
      val fs = allocate(ScopedCapabilities.sandboxResource("/tmp/test"))
      (scope $ fs)(_.root)
    }
    assertEquals(root, "/tmp/test")

  test("BudgetSlice tracks spending within scope"):
    val remaining = Scope.global.scoped { scope =>
      import scope.*
      val budget = allocate(ScopedCapabilities.budgetResource(10.0))
      (scope $ budget)(_.spend(3.0))
      (scope $ budget)(_.remaining)
    }
    assertEquals(remaining, 7.0)

  test("BudgetSlice childSlice deducts from parent within scope"):
    val parentRemaining = Scope.global.scoped { scope =>
      import scope.*
      val budget = allocate(ScopedCapabilities.budgetResource(10.0))
      // childSlice returns $[BudgetSlice] (not Unscoped), so use $ to access it
      val child = (scope $ budget)(_.childSlice(0.5))
      val childRem = (scope $ child)(_.remaining)
      assertEquals(childRem, 5.0)
      (scope $ budget)(_.remaining)
    }
    assertEquals(parentRemaining, 5.0)

  test("SpawnPermit tracks depth within scope"):
    val canSpawn = Scope.global.scoped { scope =>
      import scope.*
      val permit = allocate(ScopedCapabilities.permitResource(2))
      val cs = (scope $ permit)(_.canSpawn)
      val depth = (scope $ permit)(_.maxDepth)
      assertEquals(depth, 2)
      cs
    }
    assert(canSpawn)

  // --- Scope safety: compile-time enforcement ---

  test("$[FileSandbox] cannot escape scope — no Unscoped instance"):
    // FileSandbox is not Unscoped, so returning $[FileSandbox] from
    // scoped{} is a compile error. We verify via typeChecks.
    assert(!typeChecks("""
      import zio.blocks.scope.Scope
      import com.tjclp.scalagent.experimental.ScopedCapabilities

      val leaked: com.tjclp.scalagent.experimental.FileSandbox =
        Scope.global.scoped { scope =>
          import scope.*
          val fs = allocate(ScopedCapabilities.sandboxResource("/tmp"))
          scope.leak(fs)
        }
    """))

  test("$[BudgetSlice] cannot escape scope — no Unscoped instance"):
    assert(!typeChecks("""
      import zio.blocks.scope.Scope
      import com.tjclp.scalagent.experimental.ScopedCapabilities

      val leaked: com.tjclp.scalagent.experimental.BudgetSlice =
        Scope.global.scoped { scope =>
          import scope.*
          val b = allocate(ScopedCapabilities.budgetResource(10.0))
          scope.leak(b)
        }
    """))

  // --- Combined usage ---

  test("multiple capabilities compose within a single scope"):
    val root = Scope.global.scoped { scope =>
      import scope.*
      val fs = allocate(ScopedCapabilities.sandboxResource("/tmp/safe"))
      val budget = allocate(ScopedCapabilities.budgetResource(5.0))
      val permit = allocate(ScopedCapabilities.permitResource(1))

      (scope $ budget)(_.spend(1.0))
      val remaining = (scope $ budget)(_.remaining)
      assertEquals(remaining, 4.0)
      val depth = (scope $ permit)(_.maxDepth)
      assertEquals(depth, 1)
      (scope $ fs)(_.root)
    }
    assertEquals(root, "/tmp/safe")
