package com.tjclp.scalagent.interop.mcp

import zio.*
import com.tjclp.scalagent.core.mcp.McpToolSurface
import com.tjclp.scalagent.config.McpServerConfig
import com.tjclp.scalagent.mcp.McpServer
import com.tjclp.scalagent.tools.ToolDef

/** Loads tools from MCP server configurations into `McpToolSurface`.
  *
  * Bridges the existing MCP infrastructure to the DSL's tool capability system.
  */
object McpToolLoader:

  /** Create an `McpToolSurface` from a list of `ToolDef`s and a server name.
    *
    * This is the simplest path: you already have the tools, just wrap them.
    */
  def fromTools(serverName: String, tools: List[ToolDef[?]]): McpToolSurface =
    McpToolSurface(serverName, tools)

  /** Create an `McpToolSurface` from a list of `ToolDef`s (varargs). */
  def fromTools(serverName: String, tools: ToolDef[?]*): McpToolSurface =
    McpToolSurface(serverName, tools.toList)

  /** Create an in-process MCP server config from an `McpToolSurface`.
    *
    * Returns an `McpServerConfig.Sdk` that can be added to `AgentOptions`
    * via `.withMcpServer()`.
    */
  def toServerConfig(
      surface: McpToolSurface,
      runtime: Runtime[Any]
  ): McpServerConfig.Sdk =
    McpServer.create(surface.serverName, surface.tools, runtime = runtime)

  /** Create an in-process MCP server factory config from an `McpToolSurface`.
    *
    * Returns an `McpServerConfig.SdkFactory` for concurrent use.
    */
  def toServerFactory(
      surface: McpToolSurface,
      runtime: Runtime[Any]
  ): McpServerConfig.SdkFactory =
    McpServer.createFactory(surface.serverName, surface.tools, runtime = runtime)
