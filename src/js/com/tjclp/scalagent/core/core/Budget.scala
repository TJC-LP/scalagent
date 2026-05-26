package com.tjclp.scalagent.core

import zio.json.*

/**
 * Type-safe budget representation for agent execution.
 *
 * Prevents raw Double mistakes and supports budget arithmetic
 * for delegation and slicing.
 */
enum Budget:
  case Unlimited
  case Usd(amount: Double)

  /** Slice this budget by a fraction (0.0 to 1.0). */
  def slice(fraction: Double): Budget =
    require(fraction >= 0 && fraction <= 1.0, s"Fraction must be in [0, 1]: $fraction")
    this match
      case Unlimited   => Unlimited
      case Usd(amount) => Usd(amount * fraction)

  /** Add two budgets. Unlimited absorbs anything. */
  def +(other: Budget): Budget = (this, other) match
    case (Unlimited, _) | (_, Unlimited) => Unlimited
    case (Usd(a), Usd(b))                => Usd(a + b)

  /** Subtract spent amount. Floors at zero. */
  def -(other: Budget): Budget = (this, other) match
    case (Unlimited, Unlimited) => Unlimited
    case (Unlimited, _)         => Unlimited
    case (_, Unlimited)         => Usd(0.0)
    case (Usd(a), Usd(b))       => Usd(math.max(0, a - b))

  /** Remaining budget after spending. */
  def remaining(spent: Double): Budget = this match
    case Unlimited   => Unlimited
    case Usd(amount) => Usd(math.max(0, amount - spent))

  /** Whether this budget is exhausted (zero or negative). */
  def isExhausted: Boolean = this match
    case Unlimited   => false
    case Usd(amount) => amount <= 0

  /** Convert to optional USD amount. None for Unlimited. */
  def toUsd: Option[Double] = this match
    case Unlimited   => None
    case Usd(amount) => Some(amount)
end Budget

object Budget:
  /** Create a USD budget. */
  def usd(amount: Double): Budget =
    require(amount >= 0, s"Budget cannot be negative: $amount")
    Usd(amount)

  /** Zero budget. */
  val zero: Budget = Usd(0.0)

  given JsonEncoder[Budget] = JsonEncoder[Option[Double]].contramap(_.toUsd)
  given JsonDecoder[Budget] = JsonDecoder[Option[Double]].map {
    case Some(amount) => Usd(amount)
    case None         => Unlimited
  }
