package com.tjclp.scalagent.mcp

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.json.*
import com.tjclp.scalagent.tools.*
import com.tjclp.scalagent.config.McpServerConfig

/**
 * Create in-process MCP servers that can be used with the Claude Agent SDK.
 *
 * This allows defining custom tools in Scala that Claude can invoke during execution.
 *
 * Example usage:
 * {{{
 * case class WeatherInput(location: String) derives JsonDecoder
 * object WeatherInput:
 *   given ToolInput[WeatherInput] = ToolInput.derive[WeatherInput]
 *
 * val weatherTool = ToolDef.fromInput[WeatherInput](
 *   name = "get_weather",
 *   description = "Get current weather for a location"
 * ) { input =>
 *   ZIO.succeed(ToolResult.Success(s"Weather in ${input.location}: Sunny, 72°F"))
 * }
 *
 * val server = McpServer.create("weather-server", List(weatherTool))
 * val options = AgentOptions.default.withMcpServer("weather", server)
 * }}}
 */
object McpServer:

  /** Reference to the SDK's createSdkMcpServer function */
  @js.native
  @JSImport("@anthropic-ai/claude-agent-sdk", "createSdkMcpServer")
  private def createSdkMcpServer(options: js.Dynamic): js.Dynamic = js.native

  /**
   * Create an in-process MCP server configuration.
   *
   * @param name
   *   Name of the MCP server
   * @param tools
   *   List of tool definitions to expose
   * @param version
   *   Version string (default "1.0.0")
   * @param runtime
   *   ZIO runtime for executing tool handlers
   * @return
   *   MCP server configuration that can be added to AgentOptions
   */
  def create(
    name: String,
    tools: List[ToolDef[?]],
    version: String = "1.0.0",
    runtime: Runtime[Any],
  ): McpServerConfig.Sdk =
    // Convert Scala tools to SDK tool format using ToolDef.toSdkTool
    // The decoder is captured inside each ToolDef at construction time
    val sdkTools = tools.map(_.toSdkTool(runtime)).toJSArray

    // Create the SDK MCP server
    val serverConfig = createSdkMcpServer(
      js.Dynamic.literal(
        name = name,
        version = version,
        tools = sdkTools,
      )
    )

    // Return as our Sdk config type
    McpServerConfig.Sdk(
      name = name,
      version = version,
      rawServerConfig = serverConfig.asInstanceOf[js.Object],
    )
  end create

  /**
   * Create a lazy MCP server factory — safe for concurrent use.
   *
   * Unlike `create` which returns a single Protocol instance (not safe for concurrent sessions),
   * `createFactory` returns a factory that creates a fresh Protocol instance each time `toRaw` is called.
   * Use this when the MCP server will be used by multiple concurrent A2A sessions.
   *
   * @param name
   *   Name of the MCP server
   * @param tools
   *   List of tool definitions to expose
   * @param version
   *   Version string (default "1.0.0")
   * @param runtime
   *   ZIO runtime for executing tool handlers
   * @return
   *   MCP server factory configuration that creates a fresh instance per session
   */
  def createFactory(
    name: String,
    tools: List[ToolDef[?]],
    version: String = "1.0.0",
    runtime: Runtime[Any],
  ): McpServerConfig.SdkFactory =
    McpServerConfig.SdkFactory(
      name = name,
      version = version,
      factory = () =>
        val sdkTools = tools.map(_.toSdkTool(runtime)).toJSArray
        createSdkMcpServer(
          js.Dynamic.literal(name = name, version = version, tools = sdkTools)
        ).asInstanceOf[js.Object],
    )

  /** Create a server with a single tool */
  def withTool[A: JsonDecoder](
    serverName: String,
    tool: ToolDef[A],
    runtime: Runtime[Any],
  ): McpServerConfig.Sdk =
    create(serverName, List(tool), runtime = runtime)
end McpServer
