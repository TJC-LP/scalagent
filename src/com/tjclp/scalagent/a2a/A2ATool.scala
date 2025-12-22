package com.tjclp.scalagent.a2a

import scala.scalajs.js
import zio.*
import zio.json.*
import com.tjclp.scalagent.tools.*
import com.tjclp.scalagent.config.{AgentOptions, McpServerConfig}
import com.tjclp.scalagent.mcp.McpServer

/** Create MCP tools that delegate to A2A agents.
  *
  * This allows A2A agents to be used as tools within Claude sessions, enabling multi-agent
  * collaboration.
  */
object A2ATool:

  /** Input type for A2A tool calls */
  final case class Input(message: String)

  object Input:
    given JsonDecoder[Input] = DeriveJsonDecoder.gen[Input]
    given JsonEncoder[Input] = DeriveJsonEncoder.gen[Input]

    given ToolInput[Input] = new ToolInput[Input]:
      val jsonSchema: JsonSchema = JsonSchema
        .obj(
          "message" -> JsonSchema.string.describe("The message to send to the agent")
        )
        .required("message")
        .build

  /** Create a tool that calls a remote A2A agent.
    *
    * @param toolName
    *   Name for the tool (used by Claude to call it)
    * @param description
    *   Tool description shown to Claude
    * @param client
    *   A2A client for the remote agent
    * @return
    *   ToolDef that can be registered with MCP server
    */
  def fromClient(
      toolName: String,
      description: String,
      client: A2AClient
  ): ToolDef[Input] =
    ToolDef.fromInput[Input](
      name = toolName,
      description = description
    ) { input =>
      client
        .sendText(input.message)
        .map { task =>
          val response = task.status.message.map(_.text).getOrElse("No response")
          ToolResult.text(response)
        }
        .catchAll { error =>
          ZIO.succeed(ToolResult.error(s"A2A call failed: ${error.getMessage}"))
        }
    }

  /** Create a tool by discovering an A2A agent.
    *
    * @param agentUrl
    *   URL of the A2A agent
    * @param toolName
    *   Optional tool name (defaults to sanitized agent name)
    * @return
    *   Effect producing a ToolDef
    */
  def discover(agentUrl: String, toolName: Option[String] = None): Task[ToolDef[Input]] =
    for
      client <- A2AClient.discover(agentUrl)
      card   <- client.agentCard
      name = toolName.getOrElse(sanitizeName(card.name))
      tool = fromClient(name, card.description, client)
    yield tool

  /** Create multiple tools from multiple agent URLs */
  def discoverAll(agents: List[(String, String)]): Task[List[ToolDef[Input]]] =
    ZIO.foreach(agents) { case (name, url) =>
      discover(url, Some(name))
    }

  /** Create an MCP server with A2A agent tools.
    *
    * @param serverName
    *   Name for the MCP server
    * @param agents
    *   List of (toolName, agentUrl) pairs
    * @param runtime
    *   ZIO runtime
    * @return
    *   Effect producing MCP server config
    */
  def createServer(
      serverName: String,
      agents: List[(String, String)],
      runtime: Runtime[Any]
  ): Task[McpServerConfig.Sdk] =
    for tools <- discoverAll(agents)
    yield McpServer.create(serverName, tools, runtime = runtime)

  /** Create an MCP server from pre-configured clients */
  def createServerFromClients(
      serverName: String,
      clients: List[(String, String, A2AClient)],
      runtime: Runtime[Any]
  ): McpServerConfig.Sdk =
    val tools = clients.map { case (name, description, client) =>
      fromClient(name, description, client)
    }
    McpServer.create(serverName, tools, runtime = runtime)

  /** Sanitize agent name for use as tool name */
  private[a2a] def sanitizeName(name: String): String =
    name.toLowerCase
      .replaceAll("[^a-z0-9]+", "_")
      .replaceAll("^_|_$", "")
      .take(64)

/** Extension to add A2A tools to AgentOptions */
extension (options: AgentOptions)
  /** Add an A2A agent as a callable tool.
    *
    * This discovers the agent and creates a tool that allows Claude to call it.
    *
    * Note: This is an effectful operation that must be run before creating options.
    *
    * @param toolName
    *   Name for the tool
    * @param agentUrl
    *   URL of the A2A agent
    * @param runtime
    *   ZIO runtime for executing tool handlers
    * @return
    *   Effect producing updated options
    */
  def withA2AAgentEffect(toolName: String, agentUrl: String, runtime: Runtime[Any]): Task[AgentOptions] =
    for
      tool <- A2ATool.discover(agentUrl, Some(toolName))
      server = McpServer.create(s"a2a-$toolName", List(tool), runtime = runtime)
    yield options.withMcpServer(s"a2a-$toolName", server)

  /** Add multiple A2A agents as callable tools */
  def withA2AAgentsEffect(agents: List[(String, String)], runtime: Runtime[Any]): Task[AgentOptions] =
    for server <- A2ATool.createServer("a2a-agents", agents, runtime)
    yield options.withMcpServer("a2a-agents", server)

/** Convenience methods for common A2A tool patterns */
object A2AToolPatterns:

  /** Create a "researcher" tool pattern.
    *
    * A researcher agent answers factual questions.
    */
  def researcher(agentUrl: String): Task[ToolDef[A2ATool.Input]] =
    A2ATool.discover(
      agentUrl,
      Some("ask_researcher")
    )

  /** Create a "specialist" tool pattern.
    *
    * A specialist agent handles domain-specific tasks.
    */
  def specialist(domain: String, agentUrl: String): Task[ToolDef[A2ATool.Input]] =
    A2ATool.discover(
      agentUrl,
      Some(s"ask_${A2ATool.sanitizeName(domain)}_specialist")
    )

  /** Create a "coordinator" pattern.
    *
    * Returns tools for multiple agents that work together.
    */
  def coordinatedAgents(agents: Map[String, String]): Task[List[ToolDef[A2ATool.Input]]] =
    A2ATool.discoverAll(agents.toList)
