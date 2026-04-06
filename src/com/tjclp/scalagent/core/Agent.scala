package com.tjclp.scalagent.core

/** A provider-independent agent: takes a principal, input, and policy,
  * produces a stream of events and a typed result.
  *
  * @tparam P the principal type (who is this run for)
  * @tparam I the input type (typically String for prompts)
  * @tparam O the output type (String or a structured type)
  */
trait Agent[-P, -I, +O]:
  /** Execute this agent. */
  def run(
      principal: P,
      input: I,
      policy: ExecutionPolicy = ExecutionPolicy.unbounded
  ): AgentRun[Any, O]

object Agent:
  extension [P, I, O](agent: Agent[P, I, O])
    /** Contramap the input type. */
    def contramapInput[I2](f: I2 => I): Agent[P, I2, O] =
      new Agent[P, I2, O]:
        def run(principal: P, input: I2, policy: ExecutionPolicy): AgentRun[Any, O] =
          agent.run(principal, f(input), policy)

    /** Map the output type. */
    def mapOutput[O2](f: O => O2): Agent[P, I, O2] =
      new Agent[P, I, O2]:
        def run(principal: P, input: I, policy: ExecutionPolicy): AgentRun[Any, O2] =
          agent.run(principal, input, policy).map(f)
