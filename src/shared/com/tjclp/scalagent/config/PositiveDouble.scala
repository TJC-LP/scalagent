package com.tjclp.scalagent.config

import scala.language.implicitConversions
import scala.compiletime.error

/**
 * A positive double (> 0.0).
 *
 * Use this for configuration fields that must be positive, like maxBudgetUsd.
 *
 * Example:
 * {{{
 * // Safe construction
 * PositiveDouble(1.50) // Right(PositiveDouble(1.50))
 * PositiveDouble(-1.0) // Left("Must be positive: -1.0")
 *
 * // Unsafe construction (for trusted values)
 * PositiveDouble.unsafe(5.0)
 *
 * // In options builder
 * options.withMaxBudgetUsd(PositiveDouble.unsafe(10.0))
 * }}}
 */
opaque type PositiveDouble = Double

object PositiveDouble:

  /** Create a PositiveDouble, validating the value is > 0 */
  def apply(n: Double): Either[String, PositiveDouble] =
    if n > 0.0 then Right(n)
    else Left(s"Must be positive: $n")

  /** Create a PositiveDouble without validation (for trusted/internal values) */
  def unsafe(n: Double): PositiveDouble = n

  /**
   * Create a PositiveDouble from a literal at compile time.
   *
   * This fails compilation when the literal is not strictly positive.
   */
  inline def literal(inline n: Double): PositiveDouble =
    inline if n > 0.0 then n
    else error("PositiveDouble literal must be > 0.0")

  /** Create from Option, returning None if validation fails */
  def fromOption(n: Option[Double]): Option[PositiveDouble] =
    n.flatMap(v => apply(v).toOption)

  extension (p: PositiveDouble)
    /** Get the underlying Double value */
    def value: Double = p

    /** Convert to Option[Double] */
    def toOption: Option[Double] = Some(p)

  /** Implicit conversion to Double for convenience */
  given Conversion[PositiveDouble, Double] = _.value
end PositiveDouble
