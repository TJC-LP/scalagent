package com.tjclp.scalagent.examples

import language.experimental.captureChecking
import com.tjclp.scalagent.experimental.*

/**
 * Capture-checked operations for the CaptureCheckingExample.
 *
 * This file enables `language.experimental.captureChecking` so the
 * compiler tracks capability references through types. Each demo
 * function shows capabilities being scoped — they cannot escape
 * the `SandboxedRun.with*` callbacks.
 *
 * == To see compile-time enforcement, uncomment the REJECTED blocks ==
 */
object CaptureCheckedOps:

  private val nodePath = scala.scalajs.js.Dynamic.global.require("node:path")

  def sandboxDemo(): Unit =
    SandboxedRun.withSandbox("/tmp/scalagent-sandbox") { fs =>
      println(s"  Sandbox root: ${fs.root}")
      println(s"  Sandbox is a capability — compiler tracks it through types")

      // SAFE: use sandbox within scope
      def readConfig(sandbox: FileSandbox^): String =
        s"config from ${sandbox.root}"
      println(s"  Config: ${readConfig(fs)}")

      val attackerPath  = "../scalagent-sandbox-evil/secret.txt"
      val naiveResolved = nodePath.resolve(fs.root, attackerPath).asInstanceOf[String]
      println(s"  Without a real sandbox, prompt injection could redirect reads to: $naiveResolved")
    }

    // REJECTED by capture checker — uncomment to see the compile error:
    // val leaked = SandboxedRun.withSandbox("/tmp") { fs =>
    //   () => fs.read("secret.txt")  // ERROR: fs captured beyond scope
    // }
    // leaked()  // would crash — sandbox is gone

  def budgetDemo(): Unit =
    SandboxedRun.withBudget(10.0) { budget =>
      println(s"  Initial budget: $$${budget.remaining}")

      budget.spend(3.0)
      println(s"  After spending $$3: $$${budget.remaining}")

      // Create a child slice — deducts from parent
      val child = budget.childSlice(0.5)
      println(s"  Child slice (50%%): $$${child.remaining}")
      println(s"  Parent remaining: $$${budget.remaining}")

      child.spend(1.0)
      println(s"  Child after $$1 spend: $$${child.remaining}")
    }

    // REJECTED — uncomment to see:
    // var stolenBudget: BudgetSlice = null
    // SandboxedRun.withBudget(100.0) { budget =>
    //   stolenBudget = budget  // ERROR: capability escapes scope
    // }

  def permitDemo(): Unit =
    SandboxedRun.withPermit(3) { permit =>
      println(s"  Max depth: ${permit.maxDepth}, can spawn: ${permit.canSpawn}")

      val child1 = permit.childPermit.get
      println(s"  Child permit depth: ${child1.maxDepth}")

      val child2 = child1.childPermit.get
      println(s"  Grandchild depth: ${child2.maxDepth}")

      val child3 = child2.childPermit.get
      println(s"  Great-grandchild depth: ${child3.maxDepth}, can spawn: ${child3.canSpawn}")

      val child4 = child3.childPermit
      println(s"  Beyond max depth: ${child4}") // None — depth exhausted
    }

  def combinedDemo(): Unit =
    SandboxedRun.withAll("/tmp/scalagent-sandbox", 5.0, 2) {
      (fs,
        budget,
        permit,
      ) =>
        println(s"  Sandbox: ${fs.root}")
        println(s"  Budget: $$${budget.remaining}")
        println(s"  Spawn depth: ${permit.maxDepth}")

        // All three capabilities are tracked — none can escape this block
        budget.spend(1.0)
        println(s"  After $$1 spend: $$${budget.remaining}")

        // SAFE: child budget from parent
        val childBudget = budget.childSlice(0.5)
        println(s"  Child budget: $$${childBudget.remaining}")
    }
end CaptureCheckedOps
