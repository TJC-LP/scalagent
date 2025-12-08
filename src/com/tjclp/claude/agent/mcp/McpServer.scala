package com.tjclp.claude.agent.mcp

import scala.scalajs.js
import scala.scalajs.js.annotation._
import scala.scalajs.js.JSConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import zio._
import zio.json._
import com.tjclp.claude.agent.tools._
import com.tjclp.claude.agent.config.McpServerConfig

/** Create in-process MCP servers that can be used with the Claude Agent SDK.
  *
  * This allows defining custom tools in Scala that Claude can invoke during execution.
  *
  * Example usage:
  * {{{
  * val weatherTool = ToolBuilder[WeatherInput]("get_weather")
  *   .description("Get current weather for a location")
  *   .schema(JsonSchema.obj("location" -> JsonSchema.string).required("location"))
  *   .handler { input =>
  *     ZIO.succeed(ToolResult.Success(s"Weather in ${input.location}: Sunny, 72°F"))
  *   }
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

  /** Create an in-process MCP server configuration.
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
      runtime: Runtime[Any]
  ): McpServerConfig.Sdk =
    // Convert Scala tools to SDK tool format using ToolDef.toSdkTool
    // The decoder is captured inside each ToolDef at construction time
    val sdkTools = tools.map(_.toSdkTool(runtime)).toJSArray

    // Create the SDK MCP server
    val serverConfig = createSdkMcpServer(
      js.Dynamic.literal(
        name = name,
        version = version,
        tools = sdkTools
      )
    )

    // Return as our Sdk config type
    McpServerConfig.Sdk(
      name = name,
      version = version,
      rawServerConfig = serverConfig.asInstanceOf[js.Object]
    )

  /** Create a server with a single tool */
  def withTool[A: JsonDecoder](
      serverName: String,
      tool: ToolDef[A],
      runtime: Runtime[Any]
  ): McpServerConfig.Sdk =
    create(serverName, List(tool), runtime = runtime)
