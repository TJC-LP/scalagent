package com.tjclp.scalagent.experimental

import language.experimental.captureChecking
import scala.compiletime.testing.typeChecks

/** Tests that capture checking enforces capability scoping.
  *
  * These tests verify both positive cases (capabilities used within scope)
  * and negative cases (capabilities leaked — rejected at compile time).
  */
class CaptureCheckSpec extends munit.FunSuite:

  // --- FileSandbox ---

  test("FileSandbox can be used within scope"):
    val result = SandboxedRun.withSandbox("/tmp") { fs =>
      fs.root // use capability
    }
    assertEquals(result, "/tmp")

  test("FileSandbox.resolveSafe rejects path traversal"):
    intercept[SecurityException] {
      SandboxedRun.withSandbox("/tmp/safe") { fs =>
        fs.read("../../etc/passwd")
      }
    }

  // --- BudgetSlice ---

  test("BudgetSlice tracks spending"):
    SandboxedRun.withBudget(10.0) { budget =>
      budget.spend(3.0)
      assertEquals(budget.remaining, 7.0)
    }

  test("BudgetSlice.childSlice deducts from parent"):
    SandboxedRun.withBudget(10.0) { budget =>
      val child = budget.childSlice(0.5)
      assertEquals(child.remaining, 5.0)
      assertEquals(budget.remaining, 5.0)
    }

  test("BudgetSlice rejects overspend"):
    intercept[IllegalArgumentException] {
      SandboxedRun.withBudget(1.0) { budget =>
        budget.spend(2.0)
      }
    }

  // --- SpawnPermit ---

  test("SpawnPermit decrements depth"):
    SandboxedRun.withPermit(2) { permit =>
      assert(permit.canSpawn)
      val child = permit.childPermit.get
      assertEquals(child.maxDepth, 1)
      assert(child.canSpawn)
      val grandchild = child.childPermit.get
      assertEquals(grandchild.maxDepth, 0)
      assert(!grandchild.canSpawn)
      assertEquals(grandchild.childPermit, None)
    }

  // --- Combined ---

  test("withAll provides all three capabilities"):
    SandboxedRun.withAll("/tmp", 5.0, 1) { (fs, budget, permit) =>
      assertEquals(fs.root, "/tmp")
      assertEquals(budget.remaining, 5.0)
      assertEquals(permit.maxDepth, 1)
    }

  // --- Capture checking enforcement (compile-time) ---
  // These verify that capability leakage is a compile error.

  test("sandbox CANNOT be returned in a closure (capture set violation)"):
    assert(!typeChecks("""
      import language.experimental.captureChecking
      import com.tjclp.scalagent.experimental.*
      val leaked: () => String = SandboxedRun.withSandbox("/tmp") { fs =>
        () => fs.root
      }
    """))

  test("budget CANNOT be assigned to an outer var (capture set violation)"):
    assert(!typeChecks("""
      import language.experimental.captureChecking
      import com.tjclp.scalagent.experimental.*
      var stolen: BudgetSlice = null
      SandboxedRun.withBudget(10.0) { budget =>
        stolen = budget
      }
    """))
