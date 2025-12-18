package com.tjclp.scalagent.tools

import zio.json._

/** Type-safe tool names for the Claude Agent SDK.
  *
  * This enum provides compile-time safety for tool names, enabling IDE autocomplete
  * and preventing typos in tool name references.
  *
  * Built-in tools have predefined cases, while custom tools can be created with
  * `ToolName.Custom("my-tool")` or `ToolName("my-tool")`.
  *
  * Example usage:
  * {{{
  * // Using built-in tool names
  * CanUseTool.allowOnly(ToolName.Read, ToolName.Glob, ToolName.Grep)
  *
  * // Using custom tool names
  * CanUseTool.denyTools(ToolName("dangerous-tool"))
  *
  * // Pattern matching on tool names
  * toolName match
  *   case ToolName.Bash => // handle bash
  *   case ToolName.Custom(name) => // handle custom tool
  *   case _ => // handle other built-in tools
  * }}}
  */
enum ToolName(val raw: String):
  // File operations
  case Read extends ToolName("Read")
  case Write extends ToolName("Write")
  case Edit extends ToolName("Edit")
  case Glob extends ToolName("Glob")
  case Grep extends ToolName("Grep")
  case NotebookEdit extends ToolName("NotebookEdit")

  // Execution
  case Bash extends ToolName("Bash")
  case Task extends ToolName("Task")

  // Web
  case WebFetch extends ToolName("WebFetch")
  case WebSearch extends ToolName("WebSearch")

  // Planning and interaction
  case TodoWrite extends ToolName("TodoWrite")
  case AskUserQuestion extends ToolName("AskUserQuestion")
  case EnterPlanMode extends ToolName("EnterPlanMode")
  case ExitPlanMode extends ToolName("ExitPlanMode")

  // Session control
  case KillShell extends ToolName("KillShell")
  case TaskOutput extends ToolName("TaskOutput")
  case SlashCommand extends ToolName("SlashCommand")
  case Skill extends ToolName("Skill")

  // MCP tools (context7)
  case McpResolveLibraryId extends ToolName("mcp__context7__resolve-library-id")
  case McpGetLibraryDocs extends ToolName("mcp__context7__get-library-docs")

  // IDE integration
  case GetDiagnostics extends ToolName("mcp__ide__getDiagnostics")

  // Custom tools
  case Custom(override val raw: String) extends ToolName(raw)

object ToolName:
  /** Create a ToolName from a raw string.
    *
    * If the string matches a known built-in tool, returns that case.
    * Otherwise, returns Custom(raw).
    */
  def apply(raw: String): ToolName = fromString(raw)

  /** Parse a tool name from string, returning the appropriate enum case. */
  def fromString(raw: String): ToolName = raw match
    // File operations
    case "Read"         => Read
    case "Write"        => Write
    case "Edit"         => Edit
    case "Glob"         => Glob
    case "Grep"         => Grep
    case "NotebookEdit" => NotebookEdit

    // Execution
    case "Bash" => Bash
    case "Task" => Task

    // Web
    case "WebFetch"  => WebFetch
    case "WebSearch" => WebSearch

    // Planning and interaction
    case "TodoWrite"       => TodoWrite
    case "AskUserQuestion" => AskUserQuestion
    case "EnterPlanMode"   => EnterPlanMode
    case "ExitPlanMode"    => ExitPlanMode

    // Session control
    case "KillShell"    => KillShell
    case "TaskOutput"   => TaskOutput
    case "SlashCommand" => SlashCommand
    case "Skill"        => Skill

    // MCP tools
    case "mcp__context7__resolve-library-id" => McpResolveLibraryId
    case "mcp__context7__get-library-docs"   => McpGetLibraryDocs

    // IDE integration
    case "mcp__ide__getDiagnostics" => GetDiagnostics

    // Unknown tools become Custom
    case other => Custom(other)

  /** Check if a tool name represents a file operation */
  def isFileOperation(tool: ToolName): Boolean = tool match
    case Read | Write | Edit | Glob | Grep | NotebookEdit => true
    case _                                                 => false

  /** Check if a tool name represents a potentially dangerous operation */
  def isDangerous(tool: ToolName): Boolean = tool match
    case Bash | Write | Edit | NotebookEdit => true
    case _                                  => false

  /** Check if a tool name is read-only (no side effects) */
  def isReadOnly(tool: ToolName): Boolean = tool match
    case Read | Glob | Grep | WebFetch | WebSearch | TaskOutput | GetDiagnostics => true
    case _                                                                        => false

  // JSON codecs
  given JsonEncoder[ToolName] = JsonEncoder[String].contramap(_.raw)
  given JsonDecoder[ToolName] = JsonDecoder[String].map(fromString)

  // Extension methods
  extension (tool: ToolName)
    /** Check if this is a built-in tool (not Custom) */
    def isBuiltIn: Boolean = tool match
      case Custom(_) => false
      case _         => true

    /** Check if this is a custom tool */
    def isCustom: Boolean = tool match
      case Custom(_) => true
      case _         => false

    /** Check if this is an MCP tool */
    def isMcp: Boolean = tool.raw.startsWith("mcp__")
