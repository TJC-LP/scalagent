package com.tjclp.scalagent.core

import com.tjclp.scalagent.tools.ToolDef
import com.tjclp.scalagent.tools.ToolName

/** A collection of tools available to an agent.
  *
  * Value-level complement to the type-level `ToolSet` markers.
  * The `ToolSet` phantom type (`AllTools`, `ReadOnlyTools`) is tracked
  * on `TypedAgent` via the builder, not on `ToolSurface` itself.
  */
final case class ToolSurface(
    tools: List[ToolDef[?]],
    allowedTools: List[ToolName]
):
  def ++(other: ToolSurface): ToolSurface =
    ToolSurface(
      tools = tools ++ other.tools,
      allowedTools = allowedTools ++ other.allowedTools
    )

  def filter(pred: ToolDef[?] => Boolean): ToolSurface =
    ToolSurface(
      tools = tools.filter(pred),
      allowedTools = allowedTools
    )

  def names: List[String] = tools.map(_.name)
  def isEmpty: Boolean = tools.isEmpty && allowedTools.isEmpty
  def size: Int = tools.size
  def distinctAllowedTools: List[ToolName] = allowedTools.distinct
  def isReadOnlyCompatible: Boolean = allowedTools.forall(ToolName.isReadOnly)

object ToolSurface:
  val empty: ToolSurface = ToolSurface(Nil, Nil)

  /** Create a surface from ToolDefs, deriving provider allowlist names from the tool names. */
  def apply(tools: ToolDef[?]*): ToolSurface =
    fromDefs(tools.toList)

  /** Create a surface from ToolDefs, deriving provider allowlist names from the tool names. */
  def apply(tools: List[ToolDef[?]]): ToolSurface =
    fromDefs(tools)

  def fromDefs(tools: List[ToolDef[?]]): ToolSurface =
    ToolSurface(
      tools = tools,
      allowedTools = tools.map(tool => ToolName(tool.name))
    )

  def withAllowlist(
      tools: List[ToolDef[?]],
      allowedTools: List[ToolName]
  ): ToolSurface =
    ToolSurface(tools, allowedTools)

  /** Built-in read-only Claude tools. */
  val readOnlyBuiltins: ToolSurface =
    ToolSurface(
      tools = Nil,
      allowedTools = List(ToolName.Read, ToolName.Grep, ToolName.Glob)
    )
