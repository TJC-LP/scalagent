package com.tjclp.claude.agent.mcp

import scala.scalajs.js
import zio._
import com.tjclp.claude.agent.tools._

/** Bridge between Scala ToolDef and SDK MCP tool format.
  *
  * The main conversion logic is now in `ToolDef.toSdkTool`, which captures the JsonDecoder at construction time. This
  * object provides convenience methods and documentation.
  *
  * @see
  *   ToolDef.toSdkTool for the actual implementation
  */
object McpTool:

  /** Convert a ToolDef to SDK tool format.
    *
    * Delegates to ToolDef.toSdkTool which captures the JsonDecoder at construction.
    *
    * @param tool
    *   The Scala tool definition
    * @param runtime
    *   ZIO runtime for executing the handler
    * @return
    *   JavaScript object in SDK tool format
    */
  def toSdkTool(tool: ToolDef[?], runtime: Runtime[Any]): js.Object =
    tool.toSdkTool(runtime)

  /** Convert multiple tools to SDK format.
    *
    * @param tools
    *   List of tool definitions
    * @param runtime
    *   ZIO runtime for executing handlers
    * @return
    *   Array of JavaScript tool objects
    */
  def toSdkTools(tools: List[ToolDef[?]], runtime: Runtime[Any]): js.Array[js.Object] =
    js.Array(tools.map(_.toSdkTool(runtime))*)
