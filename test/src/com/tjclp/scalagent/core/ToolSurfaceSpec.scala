package com.tjclp.scalagent.core

import zio.*
import zio.json.*
import com.tjclp.scalagent.core.mcp.McpToolSurface
import com.tjclp.scalagent.mcp.McpToolName
import com.tjclp.scalagent.tools.*

class ToolSurfaceSpec extends munit.FunSuite:

  private case class DummyInput(value: String) derives JsonDecoder, ToolInput

  private val dummyTool = ToolDef.fromInput[DummyInput](
    name = "ask_weather",
    description = "Ask weather"
  )(_ => ZIO.succeed(ToolResult.text("sunny")))

  test("filter preserves implicit local MCP allowlist entries for direct ToolDefs"):
    val surface = ToolSurface(List(dummyTool))
    val filtered = surface.filter(_.name == dummyTool.name)

    assertEquals(
      filtered.allowedTools,
      List(McpToolName(ToolSurface.localToolServerName, dummyTool.name).toToolName)
    )

  test("filter preserves explicit MCP allowlist entries for McpToolSurface"):
    val surface = McpToolSurface("weather", List(dummyTool)).toToolSurface
    val filtered = surface.filter(_.name == dummyTool.name)

    assertEquals(
      filtered.allowedTools,
      List(McpToolName("weather", dummyTool.name).toToolName)
    )

  test("read-only compatibility recognizes known MCP tool names"):
    val surface = ToolSurface.withAllowlist(
      tools = Nil,
      allowedTools = List(
        McpToolName("context7", "resolve-library-id").toToolName,
        McpToolName("context7", "get-library-docs").toToolName
      )
    )

    assert(surface.isReadOnlyCompatible)
