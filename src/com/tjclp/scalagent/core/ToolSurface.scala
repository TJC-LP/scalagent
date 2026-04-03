package com.tjclp.scalagent.core

import com.tjclp.scalagent.tools.ToolDef

/** A collection of tools available to an agent.
  *
  * Value-level complement to the type-level `ToolSet` markers.
  * The `ToolSet` phantom type (`AllTools`, `ReadOnlyTools`) is tracked
  * on `TypedAgent` via the builder, not on `ToolSurface` itself.
  */
final case class ToolSurface(tools: List[ToolDef[?]]):
  def ++(other: ToolSurface): ToolSurface = ToolSurface(tools ++ other.tools)
  def filter(pred: ToolDef[?] => Boolean): ToolSurface = ToolSurface(tools.filter(pred))
  def names: List[String] = tools.map(_.name)
  def isEmpty: Boolean = tools.isEmpty
  def size: Int = tools.size

object ToolSurface:
  val empty: ToolSurface = ToolSurface(Nil)
  def apply(tools: ToolDef[?]*): ToolSurface = ToolSurface(tools.toList)
