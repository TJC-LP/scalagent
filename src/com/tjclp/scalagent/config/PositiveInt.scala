package com.tjclp.scalagent.config

/** A positive integer (> 0).
  *
  * Use this for configuration fields that must be positive, like maxTurns.
  *
  * Example:
  * {{{
  * // Safe construction
  * PositiveInt(5) // Right(PositiveInt(5))
  * PositiveInt(0) // Left("Must be positive: 0")
  *
  * // Unsafe construction (for trusted values)
  * PositiveInt.unsafe(5)
  *
  * // In options builder
  * options.withMaxTurns(PositiveInt.unsafe(10))
  * }}}
  */
opaque type PositiveInt = Int

object PositiveInt:

  /** Create a PositiveInt, validating the value is > 0 */
  def apply(n: Int): Either[String, PositiveInt] =
    if n > 0 then Right(n)
    else Left(s"Must be positive: $n")

  /** Create a PositiveInt without validation (for trusted/internal values) */
  def unsafe(n: Int): PositiveInt = n

  /** Create from Option, returning None if validation fails */
  def fromOption(n: Option[Int]): Option[PositiveInt] =
    n.flatMap(v => apply(v).toOption)

  extension (p: PositiveInt)
    /** Get the underlying Int value */
    def value: Int = p

    /** Convert to Option[Int] */
    def toOption: Option[Int] = Some(p)

  /** Implicit conversion to Int for convenience */
  given Conversion[PositiveInt, Int] = _.value
