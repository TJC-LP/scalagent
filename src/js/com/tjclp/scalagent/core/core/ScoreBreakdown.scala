package com.tjclp.scalagent.core

/** Named contribution to a score. */
final case class ScoreComponent(
  name: String,
  raw: Double,
  weight: Double,
  contribution: Double)

/** Structured score result with named component contributions. */
final case class ScoreBreakdown(
  total: Double,
  components: List[ScoreComponent]):
  def byName(name: String): Option[ScoreComponent] =
    components.find(_.name == name)

object ScoreBreakdown:
  val empty: ScoreBreakdown = ScoreBreakdown(0.0, Nil)
