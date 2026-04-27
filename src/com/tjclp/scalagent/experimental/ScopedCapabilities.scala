package com.tjclp.scalagent.experimental

import zio.blocks.scope.Resource

/**
 * The `experimental` package provides two capability-safety mechanisms:
 *
 *   - '''Capture checking''' (`Capabilities.scala`, `SandboxedRun.scala`): Uses
 *     Scala 3's `-language:experimental.captureChecking` to prevent capability escape
 *     at compile time. Requires the experimental compiler flag.
 *
 *   - '''Scope-based''' (`ScopedCapabilities.scala`): Uses `zio-blocks/scope` for
 *     compile-time resource safety without experimental flags. This is the stable
 *     alternative for production use.
 *
 * If capture checking stabilizes in a future Scala release, the two approaches
 * may converge. Until then, prefer `ScopedCapabilities` for new code.
 */

/**
 * Scope-based capability resources using zio-blocks/scope.
 *
 * This provides `Resource[A]` factories for agent capabilities.
 * Used with `Scope.global.scoped` for compile-time safety:
 *
 * {{{
 * import zio.blocks.scope.Scope
 * import com.tjclp.scalagent.experimental.ScopedCapabilities.*
 *
 * val config: String = Scope.global.scoped { scope =>
 *   import scope.*
 *   val fs = allocate(sandboxResource("/safe/dir"))
 *   val budget = allocate(budgetResource(10.0))
 *
 *   // $ operator ensures capabilities are used safely (macro-validated):
 *   (scope $ budget)(_.spend(1.0))
 *   (scope $ fs)(_.read("config.json"))
 *   // Returns String — Unscoped, so it can escape the scope
 * }
 *
 * // COMPILE ERROR if you try to return $[FileSandbox] — no Unscoped instance
 * // COMPILE ERROR if you try to capture fs in a closure via $ operator
 * }}}
 *
 * == Why not capture checking? ==
 *
 * Scala 3 capture checking (`SharedCapability`, `^`) is experimental and
 * conflicts with parts of the current toolchain. This approach instead uses
 * `zio-blocks/scope` opaque scoped values and macro validation for compile-time
 * escape prevention on the stable control-plane path.
 */
object ScopedCapabilities:

  /** Scope-managed FileSandbox. Path traversal validated at runtime. */
  def sandboxResource(root: String): Resource[FileSandbox] =
    Resource(FileSandbox(root))

  /** Scope-managed BudgetSlice. Spending tracked; child slices deduct from parent. */
  def budgetResource(amountUsd: Double): Resource[BudgetSlice] =
    Resource(BudgetSlice(amountUsd))

  /** Scope-managed SpawnPermit. Delegation authority with depth limit. */
  def permitResource(maxDepth: Int): Resource[SpawnPermit] =
    Resource(SpawnPermit(maxDepth))

  /** Scope-managed ReviewPermit. Semantic-review authority with bounded uses. */
  def reviewPermitResource(
    label: String = "semantic-review",
    maxReviews: Int = 1,
  ): Resource[ReviewPermit] =
    Resource(ReviewPermit(label, maxReviews))
end ScopedCapabilities
