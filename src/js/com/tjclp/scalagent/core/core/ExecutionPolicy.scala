package com.tjclp.scalagent.core

import zio.Duration

/**
 * Semantic execution constraints for an agent run.
 *
 * Provider interpreters translate these into native options.
 * Fields with no provider equivalent are enforced by the
 * interpreter at the stream level.
 */
final case class ExecutionPolicy(
  budget: Budget = Budget.Unlimited,
  maxTurns: Option[Int] = None,
  deadline: Option[Duration] = None,
  stopStrategy: StopStrategy = StopStrategy.Natural,
  fallback: FallbackPolicy = FallbackPolicy.Fail)

object ExecutionPolicy:
  /** No constraints — run until natural completion. */
  val unbounded: ExecutionPolicy = ExecutionPolicy()

  /** Quick policy: budget + max turns only. */
  def simple(budgetUsd: Double, maxTurns: Int): ExecutionPolicy =
    ExecutionPolicy(
      budget = Budget.usd(budgetUsd),
      maxTurns = Some(maxTurns),
    )

/** What happens when an agent reaches a natural stopping point. */
enum StopStrategy:
  /** Let the provider decide when to stop. */
  case Natural

  /** Stop after the first complete assistant response. */
  case FirstResponse

  /** Custom strategy label for interpreter-specific behavior. */
  case Custom(name: String)

/** What happens when a run hits a policy limit or error. */
enum FallbackPolicy:
  /** Surface the error to the caller. */
  case Fail

  /** Retry up to N times (for transient errors). */
  case Retry(maxAttempts: Int)

  /** Custom handler label (future: escalation, rerouting). */
  case Custom(name: String)
