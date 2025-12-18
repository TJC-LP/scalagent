package com.tjclp.scalagent.mcp

import munit.FunSuite
import zio.json._
import com.tjclp.scalagent.tools.ToolName

class McpToolNameSpec extends FunSuite:

  // ============================================
  // Construction
  // ============================================

  test("McpToolName creates correct format"):
    val name = McpToolName("myserver", "mytool")
    assertEquals(name.value, "mcp__myserver__mytool")

  test("McpToolName handles multi-word names"):
    val name = McpToolName("weather-api", "get_current_weather")
    assertEquals(name.value, "mcp__weather-api__get_current_weather")

  test("McpToolName handles simple names"):
    val name = McpToolName("test", "run")
    assertEquals(name.value, "mcp__test__run")

  // ============================================
  // Parsing
  // ============================================

  test("fromString parses valid MCP tool names"):
    val result = McpToolName.fromString("mcp__server__tool")
    result match
      case Some(name) => assertEquals(name.value, "mcp__server__tool")
      case None       => fail("Expected Some")

  test("fromString returns None for invalid format"):
    assertEquals(McpToolName.fromString("not-mcp-format"), None)
    assertEquals(McpToolName.fromString("mcp_single_underscore"), None)
    assertEquals(McpToolName.fromString("Read"), None)

  test("unsafeFromString creates from any string"):
    val name = McpToolName.unsafeFromString("mcp__test__value")
    assertEquals(name.value, "mcp__test__value")

  // ============================================
  // Extension Methods
  // ============================================

  test("serverName extracts server portion"):
    val name = McpToolName("myserver", "mytool")
    assertEquals(name.serverName, "myserver")

  test("serverName handles complex server names"):
    val name = McpToolName("my-complex-server", "tool")
    assertEquals(name.serverName, "my-complex-server")

  test("toolName extracts tool portion"):
    val name = McpToolName("server", "get_data")
    assertEquals(name.toolName, "get_data")

  test("toolName handles complex tool names"):
    val name = McpToolName("server", "get_weather_forecast")
    assertEquals(name.toolName, "get_weather_forecast")

  test("toToolName converts to ToolName.Custom"):
    val name = McpToolName("server", "tool")
    val toolName = name.toToolName
    toolName match
      case ToolName.Custom(raw) => assertEquals(raw, "mcp__server__tool")
      case other                => fail(s"Expected Custom, got $other")

  // ============================================
  // McpToolNames Base Class
  // ============================================

  object TestTools extends McpToolNames("test-server"):
    val getThing = tool("get_thing")
    val setThing = tool("set_thing")
    override def allTools = List(getThing, setThing)

  test("McpToolNames creates tools with correct server name"):
    assertEquals(TestTools.getThing.serverName, "test-server")
    assertEquals(TestTools.setThing.serverName, "test-server")

  test("McpToolNames creates tools with correct tool names"):
    assertEquals(TestTools.getThing.toolName, "get_thing")
    assertEquals(TestTools.setThing.toolName, "set_thing")

  test("McpToolNames allTools returns defined tools"):
    val tools = TestTools.allTools
    assertEquals(tools.size, 2)
    assert(tools.contains(TestTools.getThing))
    assert(tools.contains(TestTools.setThing))

  test("McpToolNames allToolNames converts to ToolName list"):
    val toolNames = TestTools.allToolNames
    assertEquals(toolNames.size, 2)
    toolNames.foreach { tn =>
      assert(tn.isCustom)
      assert(tn.isMcp)
    }

  // ============================================
  // Implicit Conversion
  // ============================================

  test("McpToolName implicitly converts to ToolName"):
    val mcpTool: McpToolName = McpToolName("server", "tool")
    val toolName: ToolName = mcpTool // Uses given Conversion
    toolName match
      case ToolName.Custom(raw) => assertEquals(raw, "mcp__server__tool")
      case other                => fail(s"Expected Custom, got $other")

  // ============================================
  // JSON Serialization
  // ============================================

  test("McpToolName encodes to JSON string"):
    val name = McpToolName("server", "tool")
    assertEquals(name.toJson, """"mcp__server__tool"""")

  test("McpToolName decodes from JSON string"):
    val json = """"mcp__test__mytool""""
    val result = json.fromJson[McpToolName]
    result match
      case Right(name) => assertEquals(name.value, "mcp__test__mytool")
      case Left(err)   => fail(s"Parse failed: $err")

  test("JSON round-trip preserves McpToolName"):
    val original = McpToolName("weather-api", "get_forecast")
    val json = original.toJson
    val parsed = json.fromJson[McpToolName]
    parsed match
      case Right(name) => assertEquals(name.value, original.value)
      case Left(err)   => fail(s"Parse failed: $err")

  // ============================================
  // Real-world Examples
  // ============================================

  object WeatherTools extends McpToolNames("weather-api"):
    val getWeather = tool("get_weather")
    val getForecast = tool("get_forecast")
    val getAlerts = tool("get_alerts")
    override def allTools = List(getWeather, getForecast, getAlerts)

  test("Weather tools example works correctly"):
    assertEquals(WeatherTools.getWeather.value, "mcp__weather-api__get_weather")
    assertEquals(WeatherTools.getForecast.serverName, "weather-api")
    assertEquals(WeatherTools.getAlerts.toolName, "get_alerts")
    assertEquals(WeatherTools.allTools.size, 3)

  object Context7Tools extends McpToolNames("context7"):
    val resolveLibraryId = tool("resolve-library-id")
    val getLibraryDocs = tool("get-library-docs")

  test("Context7 tools match expected format"):
    assertEquals(Context7Tools.resolveLibraryId.value, "mcp__context7__resolve-library-id")
    assertEquals(Context7Tools.getLibraryDocs.value, "mcp__context7__get-library-docs")
