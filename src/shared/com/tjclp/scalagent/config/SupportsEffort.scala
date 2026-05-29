package com.tjclp.scalagent.config

/**
 * Compile-time evidence that an effort level is supported by a built-in model.
 *
 * The dynamic `AgentOptions.withEffort` path remains available for custom models
 * and runtime-loaded values. Use this evidence through APIs such as
 * `AgentOptions.withModelAndEffort` when both values are statically known.
 */
sealed trait SupportsEffort[M <: Model, E <: Effort]

object SupportsEffort:
  given low[M <: Model]: SupportsEffort[M, Effort.Low.type] with       {}
  given medium[M <: Model]: SupportsEffort[M, Effort.Medium.type] with {}
  given high[M <: Model]: SupportsEffort[M, Effort.High.type] with     {}

  given xhighOpus4_8: SupportsEffort[Model.Opus4_8.type, Effort.XHigh.type] with {}
  given xhighOpus4_7: SupportsEffort[Model.Opus4_7.type, Effort.XHigh.type] with {}

  given maxOpus4_8: SupportsEffort[Model.Opus4_8.type, Effort.Max.type] with     {}
  given maxOpus4_7: SupportsEffort[Model.Opus4_7.type, Effort.Max.type] with     {}
  given maxOpus4_6: SupportsEffort[Model.Opus4_6.type, Effort.Max.type] with     {}
  given maxSonnet4_6: SupportsEffort[Model.Sonnet4_6.type, Effort.Max.type] with {}
end SupportsEffort
