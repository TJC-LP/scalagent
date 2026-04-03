package com.tjclp.scalagent.core

import scala.annotation.implicitNotFound

/** Peano-encoded delegation depth.
  *
  * Tracks how many levels of agent spawning remain at the type level.
  * A `TypedAgent` with `CanSpawn[S[S[Z]]]` can spawn children at depth 1,
  * which can in turn spawn leaves at depth 0.
  */
sealed trait Depth

/** Zero depth — leaf agent, cannot spawn further. */
sealed trait Z extends Depth

/** Successor depth — can spawn children at depth N. */
sealed trait S[N <: Depth] extends Depth

/** Convenience aliases for common depths. */
type Depth0 = Z
type Depth1 = S[Z]
type Depth2 = S[S[Z]]
type Depth3 = S[S[S[Z]]]

/** Type-level evidence that depth A <= depth B.
  *
  * Used to verify that a child agent's delegation depth does not
  * exceed the parent's remaining depth budget.
  *
  * Resolved inductively:
  *   - Z <= Z
  *   - Z <= S[N] for any N
  *   - S[A] <= S[B] if A <= B
  */
@implicitNotFound("Delegation depth ${A} exceeds maximum allowed depth ${B}")
trait DepthLTE[A <: Depth, B <: Depth]

object DepthLTE:
  given zeroLTEzero: DepthLTE[Z, Z]()
  given zeroLTEsucc[N <: Depth]: DepthLTE[Z, S[N]]()
  given succLTEsucc[A <: Depth, B <: Depth](using DepthLTE[A, B]): DepthLTE[S[A], S[B]]()

/** Reifies a Peano depth type to a runtime Int value.
  *
  * Used by `AgentBuilder.withSpawnDepth[D]` to mirror the type-level
  * depth as a runtime counter for assertions.
  */
trait DepthValue[D <: Depth]:
  def value: Int

object DepthValue:
  given DepthValue[Z] with
    def value: Int = 0
  given [N <: Depth](using prev: DepthValue[N]): DepthValue[S[N]] with
    def value: Int = prev.value + 1
