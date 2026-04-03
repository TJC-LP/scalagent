package com.tjclp.scalagent.core.mcp

import zio.Task
import com.tjclp.scalagent.core.ToolSurface
import com.tjclp.scalagent.mcp.McpToolName
import com.tjclp.scalagent.tools.ToolDef

// ============================================================================
// MCP Tool Surface
// ============================================================================

/** Tools loaded from an MCP server.
  *
  * Wraps a `ToolSurface` with MCP server provenance. Implicitly
  * converts to `ToolSurface` so MCP tools compose seamlessly with
  * `CanUseTools`, `AgentBuilder.withTools`, etc.
  */
final case class McpToolSurface(
    serverName: String,
    tools: List[ToolDef[?]]
):
  /** Convert to a plain ToolSurface for composition. */
  def toToolSurface: ToolSurface =
    ToolSurface.withAllowlist(
      tools = tools,
      allowedTools = tools.map(tool => McpToolName(serverName, tool.name).toToolName)
    )

  def names: List[String] = tools.map(_.name)
  def isEmpty: Boolean = tools.isEmpty
  def size: Int = tools.size

object McpToolSurface:
  def empty(serverName: String): McpToolSurface = McpToolSurface(serverName, Nil)

  /** Implicit conversion to ToolSurface for seamless composition. */
  given Conversion[McpToolSurface, ToolSurface] = _.toToolSurface

// ============================================================================
// MCP Resources
// ============================================================================

/** A named, URI-addressed data source exposed by an MCP server. */
final case class McpResource(
    uri: String,
    name: String,
    description: Option[String] = None,
    mimeType: Option[String] = None
)

/** Content returned from reading an MCP resource. */
enum McpResourceContent:
  case Text(text: String, mimeType: Option[String] = None)
  case Blob(data: Array[Byte], mimeType: Option[String] = None)

/** Surface for accessing MCP resources from a server.
  *
  * Core trait — interop packages provide concrete implementations.
  * Currently forward-looking: the Claude Agent SDK does not yet
  * expose MCP resources. Implementations will be added when supported.
  */
trait McpResourceSurface:
  def serverName: String
  def resources: Task[List[McpResource]]
  def read(uri: String): Task[McpResourceContent]

// ============================================================================
// MCP Prompts
// ============================================================================

/** A parameterized prompt template exposed by an MCP server. */
final case class McpPrompt(
    name: String,
    description: Option[String] = None,
    arguments: List[McpPromptArgument] = Nil
)

/** An argument to an MCP prompt template. */
final case class McpPromptArgument(
    name: String,
    description: Option[String] = None,
    required: Boolean = false
)

/** Surface for accessing MCP prompts from a server.
  *
  * Core trait — interop packages provide concrete implementations.
  * Currently forward-looking: the Claude Agent SDK does not yet
  * expose MCP prompts. Implementations will be added when supported.
  */
trait McpPromptSurface:
  def serverName: String
  def prompts: Task[List[McpPrompt]]
  def resolve(name: String, args: Map[String, String]): Task[String]
