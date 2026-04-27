package com.tjclp.scalagent.mcp

import com.tjclp.scalagent.json.OpaqueStringJsonCodec
import com.tjclp.scalagent.tools.ToolName
import scala.language.implicitConversions
import zio.json.{JsonDecoder, JsonEncoder}

/**
 * Type-safe MCP tool name with compile-time server/tool binding.
 *
 * MCP tool names follow the pattern: `mcp__{server}__{tool}`
 *
 * Example:
 * {{{
 * // Define tool names for a server
 * object MyTools extends McpToolNames("my-server"):
 *   val getWeather = tool("get_weather")
 *   val calculate = tool("calculate")
 *
 * // Use in agent definition (compile-time safe)
 * AgentDefinition(
 *   tools = Some(List(MyTools.getWeather.toToolName, MyTools.calculate.toToolName))
 * )
 * }}}
 */
opaque type McpToolName = String

object McpToolName:
  /** Create an MCP tool name from server and tool names */
  def apply(serverName: String, toolName: String): McpToolName =
    s"mcp__${serverName}__$toolName"

  /**
   * Create a wildcard pattern to allow all tools from a server.
   *
   * Use this in `allowedTools` to auto-approve all tools from an MCP server.
   * Wildcard matching is performed by the Claude Code TypeScript SDK layer,
   * which uses glob-style pattern matching on tool names.
   *
   * Example:
   * {{{
   * AgentOptions.default
   *   .withAllowedTools(McpToolName.wildcard("weather-api"))
   *   // Allows: mcp__weather-api__get_weather, mcp__weather-api__get_forecast, etc.
   * }}}
   *
   * @param serverName The MCP server name to allow all tools from
   * @return A wildcard pattern like `mcp__weather-api__*`
   */
  def wildcard(serverName: String): ToolName =
    ToolName.Custom(s"mcp__${serverName}__*")

  /**
   * Create a wildcard pattern to allow all MCP tools from all servers.
   *
   * Use with caution - this allows any MCP tool to execute without permission prompts.
   * Wildcard matching is performed by the Claude Code TypeScript SDK layer.
   *
   * Example:
   * {{{
   * AgentOptions.default.withAllowedTools(McpToolName.wildcardAll)
   * // Allows: all tools matching mcp__* (any MCP tool)
   * }}}
   */
  def wildcardAll: ToolName =
    ToolName.Custom("mcp__*")

  /** Parse an existing MCP tool name string */
  def fromString(s: String): Option[McpToolName] =
    if s.startsWith("mcp__") && s.count(_ == '_') >= 4 then Some(s)
    else None

  /** Unsafe creation from string (use when you know the format is correct) */
  def unsafeFromString(s: String): McpToolName = s

  extension (name: McpToolName)
    /** Get the raw string value */
    def value: String = name

    /**
     * Convert to ToolName for use in tool lists.
     *
     * Known MCP tool names are normalized to their concrete ToolName cases so
     * read-only classification continues to work for built-in MCP surfaces.
     */
    def toToolName: ToolName = ToolName(name)

    /** Extract the server name */
    def serverName: String =
      val stripped = name.stripPrefix("mcp__")
      val idx      = stripped.indexOf("__")
      if idx > 0 then stripped.substring(0, idx)
      else stripped.takeWhile(_ != '_')

    /** Extract the tool name */
    def toolName: String =
      val stripped = name.stripPrefix("mcp__")
      val idx      = stripped.indexOf("__")
      if idx > 0 && idx + 2 < stripped.length then stripped.substring(idx + 2)
      else ""
  end extension

  given Conversion[McpToolName, ToolName] = _.toToolName

  given JsonEncoder[McpToolName] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[McpToolName] = OpaqueStringJsonCodec.decoderOrFail { raw =>
    fromString(raw).toRight(s"Invalid MCP tool name format: $raw")
  }
end McpToolName

/**
 * Base class for defining type-safe MCP tool names for a server.
 *
 * Provides compile-time guarantees that tool names are correctly formatted. Extend this class to create a namespace of
 * tools for your MCP server.
 *
 * Example:
 * {{{
 * object WeatherTools extends McpToolNames("weather-api"):
 *   val getWeather = tool("get_weather")
 *   val getForecast = tool("get_forecast")
 *
 *   override def allTools = List(getWeather, getForecast)
 *
 * // Type-safe usage in agent definitions
 * val allowedTools: List[ToolName] = List(
 *   WeatherTools.getWeather.toToolName,
 *   WeatherTools.getForecast.toToolName
 * )
 *
 * // Or use implicit conversion
 * import WeatherTools.given
 * val tools: List[ToolName] = List(WeatherTools.getWeather, WeatherTools.getForecast)
 * }}}
 */
abstract class McpToolNames(val serverName: String):
  /** Create a tool name for this server */
  protected def tool(name: String): McpToolName =
    McpToolName(serverName, name)

  /** Get all tools defined in this namespace. Override to enable enumeration. */
  def allTools: List[McpToolName] = Nil

  /** Get all tools as ToolName for use in configurations */
  def allToolNames: List[ToolName] = allTools.map(_.toToolName)

  /**
   * Get a wildcard pattern to allow all tools from this server.
   *
   * Example:
   * {{{
   * object WeatherTools extends McpToolNames("weather-api"):
   *   val getWeather = tool("get_weather")
   *
   * // Allow all weather tools without permission prompts
   * AgentOptions.default.withAllowedTools(WeatherTools.wildcard)
   * }}}
   */
  def wildcard: ToolName = McpToolName.wildcard(serverName)
end McpToolNames
