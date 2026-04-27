package com.tjclp.scalagent.core

/**
 * Controls how a parent agent delegates to a child.
 *
 * Budget slicing is enforced: the child receives a fraction of
 * the parent's remaining budget. Turn limits can be overridden.
 */
final case class DelegationPolicy(
  budgetFraction: Double,
  maxChildTurns: Option[Int] = None):
  require(
    budgetFraction > 0 && budgetFraction <= 1.0,
    s"Budget fraction must be in (0, 1]: $budgetFraction",
  )

object DelegationPolicy:
  /** Give child half the budget, inherit parent's turn limit. */
  val default: DelegationPolicy = DelegationPolicy(budgetFraction = 0.5)
