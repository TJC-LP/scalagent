package com.tjclp.scalagent.core

import com.tjclp.scalagent.errors.AgentError

/** An agent with compile-time capability evidence.
  *
  * `C` is a phantom intersection type accumulating capabilities:
  * {{{
  * TypedAgent[Any, String, String, CanUseTools[AllTools] & CanSpawn[S[Z]] & HasBudget]
  * }}}
  *
  * Operations that require specific capabilities use type class evidence
  * (`HasSpawn[C]`, `HasToolsCap[C]`) so the compiler rejects unauthorized calls.
  *
  * @tparam P principal type
  * @tparam I input type
  * @tparam O output type
  * @tparam C phantom capability intersection
  */
final class TypedAgent[-P, -I, +O, C] private[core] (
    val agent: Agent[P, I, O],
    val toolSurface: ToolSurface,
    val maxRuntimeDepth: Int
) extends Agent[P, I, O]:

  override def run(principal: P, input: I, policy: ExecutionPolicy): AgentRun[Any, O] =
    agent.run(principal, input, policy)

  /** Delegate to a child agent with budget slicing.
    *
    * Only compiles if this agent has `CanSpawn` in its capability set.
    * The child's budget is sliced from the parent's policy.
    *
    * Generic over the child's principal and input types — the caller
    * provides the appropriate values.
    */
  def delegate[CP, CI, CO](
      child: Agent[CP, CI, CO],
      principal: CP,
      input: CI,
      policy: ExecutionPolicy = ExecutionPolicy.unbounded,
      delegation: DelegationPolicy = DelegationPolicy.default
  )(using ev: HasSpawn[C]): AgentRun[Any, CO] =
    val childBudget = policy.budget.slice(delegation.budgetFraction)
    val childPolicy = policy.copy(
      budget = childBudget,
      maxTurns = delegation.maxChildTurns.orElse(policy.maxTurns)
    )
    child.run(principal, input, childPolicy)

  /** Delegate to a typed child with compile-time depth enforcement.
    *
    * Requires:
    *   - Parent has `CanSpawn[S[PD]]` (can spawn at depth > 0)
    *   - Child has `CanSpawn[CD]` where `CD <= PD` (child depth fits within parent's)
    *   - Both checked at compile time via `HasSpawn` and `DepthLTE`
    *
    * Also validates at runtime as a defense-in-depth measure.
    */
  def delegateTyped[CP, CI, CO, CC, PD <: Depth, CD <: Depth](
      child: TypedAgent[CP, CI, CO, CC],
      principal: CP,
      input: CI,
      policy: ExecutionPolicy = ExecutionPolicy.unbounded,
      delegation: DelegationPolicy = DelegationPolicy.default
  )(using
      parentSpawn: HasSpawn[C] { type MaxDepth = S[PD] },
      childSpawn: HasSpawn[CC] { type MaxDepth = CD },
      depthOk: DepthLTE[CD, PD]
  ): AgentRun[Any, CO] =
    // Runtime defense-in-depth (type-level already verified)
    require(
      child.maxRuntimeDepth < maxRuntimeDepth,
      s"Child depth (${child.maxRuntimeDepth}) must be < parent depth ($maxRuntimeDepth)"
    )
    delegate(child.agent, principal, input, policy, delegation)

object TypedAgent:
  extension [P, I, O, C](ta: TypedAgent[P, I, O, C])
    /** Unwrap to the underlying untyped Agent. */
    def unwrap: Agent[P, I, O] = ta.agent
