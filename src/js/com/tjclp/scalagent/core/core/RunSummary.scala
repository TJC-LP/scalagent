package com.tjclp.scalagent.core

/**
 * Summary of how an agent run terminated.
 *
 * Produced from the terminal event of an agent execution.
 * Contains observable metrics without provider-specific details.
 */
final case class RunSummary(
  durationMs: Long,
  numTurns: Int,
  costUsd: Double,
  isSuccess: Boolean,
  resultText: Option[String] = None,
  stopReason: Option[String] = None)
