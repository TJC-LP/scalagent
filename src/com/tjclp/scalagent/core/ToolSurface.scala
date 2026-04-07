package com.tjclp.scalagent.core

import com.tjclp.scalagent.mcp.McpToolName
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
      tools = (tools ++ other.tools).distinctBy(_.name),
      allowedTools = (allowedTools ++ other.allowedTools).distinct
    )

  /** Filter tool definitions and synchronize the allowlist to match.
    * Names in allowedTools that don't correspond to a remaining ToolDef
    * are dropped, preventing filtered-out tools from remaining authorized.
    */
  def filter(pred: ToolDef[?] => Boolean): ToolSurface =
    val kept = tools.filter(pred)
    val keptNames = kept.map(_.name).toSet
    ToolSurface(
      tools = kept,
      allowedTools = allowedTools.filter(tn => ToolSurface.matchesDefName(tn, keptNames))
    )

  def names: List[String] = tools.map(_.name)
  def isEmpty: Boolean = tools.isEmpty && allowedTools.isEmpty
  def size: Int = tools.size
  def distinctAllowedTools: List[ToolName] = allowedTools.distinct

  /** Checks whether all names in the allowlist are known read-only tools.
    * This validates the allowlist, not the ToolDef handlers themselves,
    * because handlers are opaque JS functions whose behavior cannot be introspected.
    */
  def isReadOnlyCompatible: Boolean = allowedTools.forall(ToolName.isReadOnly)

object ToolSurface:
  private[scalagent] val localToolServerName = "scalagent_dsl_local_tools"

  val empty: ToolSurface = ToolSurface(Nil, Nil)

  /** Create a surface from ToolDefs, deriving provider allowlist names from
    * the implicit local MCP server used by interpreter builders.
    */
  def apply(tools: ToolDef[?]*): ToolSurface =
    fromDefs(tools.toList)

  /** Create a surface from ToolDefs, deriving provider allowlist names from
    * the implicit local MCP server used by interpreter builders.
    */
  def apply(tools: List[ToolDef[?]]): ToolSurface =
    fromDefs(tools)

  def fromDefs(tools: List[ToolDef[?]]): ToolSurface =
    ToolSurface(
      tools = tools,
      allowedTools = tools.map(tool => McpToolName(localToolServerName, tool.name).toToolName)
    )

  def withAllowlist(
      tools: List[ToolDef[?]],
      allowedTools: List[ToolName]
  ): ToolSurface =
    ToolSurface(tools, allowedTools)

  /** No tools. Alias for `empty` with explicit intent. */
  val none: ToolSurface = empty

  /** Read-only file tools: Read, Grep, Glob. */
  val readOnlyBuiltins: ToolSurface =
    ToolSurface(
      tools = Nil,
      allowedTools = List(ToolName.Read, ToolName.Grep, ToolName.Glob)
    )

  /** All read-only built-in tools (file, web, IDE). */
  val readOnlyAll: ToolSurface =
    ToolSurface(
      tools = Nil,
      allowedTools = List(
        ToolName.Read, ToolName.Grep, ToolName.Glob,
        ToolName.WebFetch, ToolName.WebSearch,
        ToolName.TaskOutput, ToolName.LSP, ToolName.GetDiagnostics,
        ToolName.McpResolveLibraryId, ToolName.McpGetLibraryDocs
      )
    )

  /** Standard dev tools: read-only + Write + Edit (no Bash). */
  val standard: ToolSurface =
    ToolSurface(
      tools = Nil,
      allowedTools = readOnlyBuiltins.allowedTools ++ List(
        ToolName.Write, ToolName.Edit, ToolName.NotebookEdit
      )
    )

  /** All built-in tools including Bash. Explicit opt-in to full access. */
  val allBuiltins: ToolSurface =
    ToolSurface(
      tools = Nil,
      allowedTools = List(
        ToolName.Read, ToolName.Write, ToolName.Edit, ToolName.Glob, ToolName.Grep,
        ToolName.NotebookEdit, ToolName.Bash, ToolName.Task,
        ToolName.WebFetch, ToolName.WebSearch,
        ToolName.TodoWrite, ToolName.AskUserQuestion,
        ToolName.EnterPlanMode, ToolName.ExitPlanMode,
        ToolName.KillShell, ToolName.TaskOutput, ToolName.SlashCommand, ToolName.Skill,
        ToolName.LSP, ToolName.GetDiagnostics
      )
    )

  private[core] def matchesDefName(toolName: ToolName, keptNames: Set[String]): Boolean =
    keptNames.contains(toolName.raw) ||
      McpToolName.fromString(toolName.raw).exists(name => keptNames.contains(name.toolName))
