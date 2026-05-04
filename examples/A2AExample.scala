package com.tjclp.scalagent.examples

import zio.*
import zio.stream.*
import zio.json.*
import com.tjclp.scalagent.*

/**
 * Example demonstrating A2A (Agent-to-Agent) protocol communication.
 *
 * This example shows how to:
 *   1. Create an A2A server that exposes a Claude agent
 *   2. Create an A2A client that connects to the server
 *   3. Use A2ATool to wrap the remote agent as a callable tool
 *   4. Have two Claude instances communicate via A2A
 *
 * Architecture:
 * ```
 * Claude Writer (local) --> A2ATool --> A2A Client --> A2A Server --> Claude Researcher
 * ```
 *
 * Run with: mill examples.runMain com.tjclp.scalagent.examples.A2AExample
 *
 * Requires:
 *   - ANTHROPIC_API_KEY environment variable
 *   - @a2a-js/sdk npm package installed
 */
object A2AExample extends ZIOAppDefault:

  val run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for
        _ <- Console.printLine("=== A2A Agent-to-Agent Communication Example ===")
        _ <- Console.printLine("")

        runtime <- ZIO.runtime[Any]

        // Start the Researcher agent as an A2A server
        _          <- Console.printLine("Starting Researcher agent on port 3001...")
        researcher <- startResearcherAgent

        // Give server time to start
        _ <- ZIO.sleep(1.second)
        _ <- Console.printLine(s"Researcher agent ready at ${researcher.url}")
        _ <- Console.printLine("")

        // Run the Writer agent with access to Researcher via A2A
        _ <- Console.printLine("Starting Writer agent with A2A tool to call Researcher...")
        _ <- Console.printLine("---")
        _ <- runWriterAgent(runtime, researcher.url)

        _ <- Console.printLine("")
        _ <- Console.printLine("=== Example Complete ===")
      yield ()
    }

  /** Start the Researcher agent as an A2A server */
  private def startResearcherAgent: ZIO[Scope, Throwable, A2AServer] =
    val config = A2AServer.Config(
      name = "Researcher",
      description = "A research specialist that answers factual questions accurately and concisely.",
      host = "localhost",
      port = 3001,
      agentOptions = AgentOptions.default
        .withModel(Model.sonnet)
        .withMaxTurns(10)
        .withSystemPrompt(
          """You are a research assistant specializing in providing accurate, factual information.
            |Keep your responses concise and focused on the facts.
            |If you're unsure about something, say so.""".stripMargin
        )
        .withPermissionMode(PermissionMode.BypassPermissions),
      skills = List(
        AgentSkill(
          id = "research",
          name = "Research",
          description = "Answer factual questions with detailed explanations",
        )
      ),
    )

    A2AServer.create(config)
  end startResearcherAgent

  /** Run the Writer agent that uses Researcher via A2A */
  private def runWriterAgent(runtime: Runtime[Any], researcherUrl: String): ZIO[Any, Throwable, Unit] =
    for
      // Discover the Researcher agent and create a tool for it
      researchTool <- A2ATool.discover(researcherUrl, Some("ask_researcher"))
      _            <- Console.printLine(s"Discovered Researcher agent, created ask_researcher tool")

      // Create MCP server with the A2A tool
      mcpServer = McpServer.create(
        name = "a2a-research",
        tools = List(researchTool),
        runtime = runtime,
      )

      // Configure Writer agent with access to Researcher
      writerOptions = AgentOptions.default
        .withModel(Model.sonnet)
        .withPermissionMode(PermissionMode.BypassPermissions)
        .withMcpServer("research", mcpServer)
        .withMaxTurns(10)
        .withSystemPrompt(
          """You are a technical writer who creates well-informed content.
            |When writing about topics, use the ask_researcher tool to get accurate facts.
            |Then synthesize the information into clear, engaging prose.""".stripMargin
        )

      // Have Writer compose an article using Researcher
      _ <- Console.printLine("")
      _ <- Console.printLine("Writer agent querying Researcher and composing content...")
      _ <- Console.printLine("---")

      _ <- ClaudeAgent
        .query(
          """Write a short (2-3 paragraphs) technical overview of the Scala programming language.
            |
            |First, use the ask_researcher tool to gather key facts about:
            |1. When and why Scala was created
            |2. Its main features and paradigms
            |3. Common use cases
            |
            |Then synthesize these facts into a well-written overview.""".stripMargin,
          writerOptions,
        )
        .tap(handleMessage)
        .runDrain
        .provide(ClaudeAgent.live)
    yield ()

  /** Alternative: Direct A2A client usage (without tool wrapper) */
  private def directClientExample: ZIO[Any, Throwable, Unit] =
    for
      // Create A2A client directly
      client <- A2AClient.discover("http://localhost:3001")
      card   <- client.agentCard
      _      <- Console.printLine(s"Connected to: ${card.name} - ${card.description}")

      // Send a message directly via A2A
      task <- client.sendText("What is the capital of France?")
      response = task.status.message.map(_.text).getOrElse("No response")
      _ <- Console.printLine(s"Response: $response")
    yield ()

  /** Alternative: Streaming A2A communication */
  private def streamingExample: ZIO[Any, Throwable, Unit] =
    for
      client <- A2AClient.discover("http://localhost:3001")

      // Stream responses from the agent
      _ <- client
        .streamText("Explain quantum computing in simple terms.")
        .tap { event =>
          event match
            case A2AResponse.StreamEvent.TaskSnapshot(task) =>
              Console.printLine(s"Task: ${task.id.value} (${task.status.state})")
            case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, _, _) =>
              Console.printLine(s"Status: ${status.state}")
            case A2AResponse.StreamEvent.TaskMessage(_, _, msg) =>
              Console.printLine(s"Message: ${msg.text}")
            case A2AResponse.StreamEvent.TaskArtifactUpdate(_, _, artifact, _, _, _) =>
              Console.printLine(s"Artifact: ${artifact.name.getOrElse("unnamed")}")
        }
        .runDrain
    yield ()

  private def handleMessage(msg: AgentMessage): Task[Unit] =
    msg match
      case AgentMessage.Assistant(message, _, _, _, _) =>
        val text      = message.content.collect { case ContentBlock.Text(t) => t }.mkString
        val toolCalls = message.content.collect {
          case ContentBlock.ToolUse(id, name, _) =>
            s"[Calling $name...]"
        }
        for
          _ <- ZIO.foreach(toolCalls)(call => Console.printLine(call))
          _ <- if text.nonEmpty then Console.printLine(s"\nWriter: $text") else ZIO.unit
        yield ()

      case AgentMessage.User(message, _, _, toolResult, _, _, _) =>
        toolResult match
          case Some(_) =>
            val results = message.content.collect {
              case ContentBlock.ToolResult(_, content, isError) =>
                val preview = if content.length > 200 then content.take(200) + "..." else content
                if isError then s"[Tool Error] $preview" else s"[Researcher Response] $preview"
            }
            ZIO.foreach(results)(r => Console.printLine(r)).unit
          case None => ZIO.unit

      case AgentMessage.Result(success: ResultOutcome.Success, _, _, _) =>
        Console.printLine(s"\n--- Completed in ${success.numTurns} turns, cost: $$${success.totalCostUsd} ---")

      case AgentMessage.Result(error: ResultOutcome.Error, _, _, _) =>
        Console.printLine(s"\n--- Error: ${error.reason} ---") *>
          Console.printLine(s"Errors: ${error.errors.mkString(", ")}")

      case _ =>
        ZIO.unit
end A2AExample
