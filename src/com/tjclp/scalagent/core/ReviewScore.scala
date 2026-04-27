package com.tjclp.scalagent.core

/** Structured semantic review result. */
final case class ReviewScore(
  score: Double,
  rationale: String,
  strengths: List[String] = Nil,
  issues: List[String] = Nil,
  confidence: Option[Double] = None,
  passed: Option[Boolean] = None)
