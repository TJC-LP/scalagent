package com.tjclp.scalagent.experimental

import language.experimental.captureChecking
import zio.*

/** Scoped agent execution with capture-checked capabilities.
  *
  * Each `run*` method provides capabilities to a callback and ensures
  * they cannot escape the callback's scope. The compiler enforces this
  * via capture checking — any attempt to leak a capability into a
  * closure, field, or return value that outlives the scope is a
  * compile-time error.
  */
object SandboxedRun:

  /** Run an operation with a filesystem sandbox.
    *
    * The sandbox is scoped — it cannot be captured by the result type T.
    */
  def withSandbox[T](root: String)(op: FileSandbox^ => T): T =
    val sandbox = FileSandbox(root)
    op(sandbox)

  /** Run an operation with a budget slice.
    *
    * The budget is scoped — spending authority cannot escape.
    */
  def withBudget[T](amountUsd: Double)(op: BudgetSlice^ => T): T =
    val budget = BudgetSlice(amountUsd)
    op(budget)

  /** Run an operation with a spawn permit.
    *
    * The permit is scoped — delegation authority cannot escape.
    */
  def withPermit[T](maxDepth: Int)(op: SpawnPermit^ => T): T =
    val permit = SpawnPermit(maxDepth)
    op(permit)

  /** Run an operation with semantic-review authority.
    *
    * The permit is scoped — agentic review cannot be performed unless the
    * caller explicitly opts into this impurity boundary.
    */
  def withReviewPermit[T](
      label: String = "semantic-review",
      maxReviews: Int = 1
  )(op: ReviewPermit^ => T): T =
    val permit = ReviewPermit(label, maxReviews)
    op(permit)

  /** Run an operation with all three capabilities.
    *
    * None of the capabilities can escape the callback scope.
    */
  def withAll[T](
      root: String,
      budgetUsd: Double,
      maxDepth: Int
  )(op: (FileSandbox^, BudgetSlice^, SpawnPermit^) => T): T =
    val sandbox = FileSandbox(root)
    val budget = BudgetSlice(budgetUsd)
    val permit = SpawnPermit(maxDepth)
    op(sandbox, budget, permit)
