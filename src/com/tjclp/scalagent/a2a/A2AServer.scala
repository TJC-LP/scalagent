package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.a2a.facade.*

/** A2A Server that exposes a Claude agent via the A2A protocol.
  *
  * This bridges A2A requests to ClaudeAgent queries, allowing Claude to be called by other A2A
  * clients.
  */
trait A2AServer:
  /** Start the server */
  def start: Task[Unit]

  /** Stop the server */
  def stop: Task[Unit]

  /** Get the agent card for this server */
  def agentCard: AgentCard

  /** Get the server URL */
  def url: String

object A2AServer:

  /** Server configuration */
  final case class Config(
      name: String,
      description: String,
      host: String = "localhost",
      port: Int = 3000,
      agentOptions: AgentOptions = AgentOptions.default,
      skills: List[AgentSkill] = Nil
  ):
    def url: String = s"http://$host:$port"

    def toAgentCard: AgentCard =
      AgentCard(
        name = name,
        description = description,
        url = url,
        capabilities = AgentCapabilities(streaming = true),
        skills = skills
      )

  /** Create and start an A2A server */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    for
      runtime <- ZIO.runtime[Any]
      server <- ZIO.acquireRelease(
        start(config, runtime)
      )(srv => srv.stop.ignore)
    yield server

  /** Start server without scope management */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    ZIO.attempt {
      A2AServerLive(config, runtime)
    }.tap(_.start)

  /** Create a server layer */
  def live(config: Config): ZLayer[Scope, Throwable, A2AServer] =
    ZLayer.fromZIO(create(config))

/** Live implementation of A2A Server */
private final class A2AServerLive(config: A2AServer.Config, runtime: Runtime[Any]) extends A2AServer:

  private var bunServer: js.Dynamic = null

  override def agentCard: AgentCard = config.toAgentCard

  override def url: String = config.url

  override def start: Task[Unit] =
    ZIO.attempt {
      val card = A2AConverters.toJs(config.toAgentCard)

      // Create executor that bridges to ClaudeAgent
      val executor = createExecutor()

      // Create task store
      val taskStore = new JsInMemoryTaskStore()

      // Create request handler
      val requestHandler = new JsDefaultRequestHandler(card, taskStore, executor)

      // Start Bun server with routes
      bunServer = BunServer.serve(
        js.Dynamic.literal(
          hostname = config.host,
          port = config.port,
          fetch = createFetchHandler(requestHandler, card)
        )
      )

      ()
    }

  override def stop: Task[Unit] =
    ZIO.attempt {
      if bunServer != null then bunServer.stop()
      ()
    }

  /** Create an AgentExecutor that bridges to ClaudeAgent */
  private def createExecutor(): JsAgentExecutor =
    JsExecutorBuilder.create(
      handler = (ctx, bus) => {
        // Convert JS message to prompt
        val message = A2AConverters.toScala(ctx.userMessage)
        val prompt = message.text

        // Run ClaudeAgent query
        val effect = for
          result <- ClaudeAgent
            .queryComplete(prompt, config.agentOptions)
            .provideLayer(ClaudeAgent.live)
        yield result

        // Execute and publish results
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.runToFuture {
            effect
              .map { queryResult =>
                // Convert result to A2A message
                val responseText = queryResult.text.getOrElse("Error: " + queryResult.outcome.toString)
                val responseMsg = A2AMessage.agentText(responseText, message.contextId)
                val jsMsg = A2AConverters.toJs(responseMsg)

                // Publish response
                bus.publish(jsMsg)
                bus.finished()
              }
              .catchAll { error =>
                ZIO.succeed {
                  val errorMsg = A2AMessage.agentText(s"Error: ${error.getMessage}", message.contextId)
                  bus.publish(A2AConverters.toJs(errorMsg))
                  bus.finished()
                }
              }
          }.toJSPromise.`then`[Unit](_ => ())
        }
      },
      cancelHandler = (taskId, bus) => {
        bus.finished()
        js.Promise.resolve(())
      }
    )

  /** Create the fetch handler for Bun.serve */
  private def createFetchHandler(
      requestHandler: JsDefaultRequestHandler,
      card: JsAgentCard
  ): js.Function1[js.Dynamic, js.Promise[js.Dynamic]] =
    (req: js.Dynamic) => {
      val url = req.url.asInstanceOf[String]
      val method = req.method.asInstanceOf[String]
      val pathname = js.Dynamic.newInstance(js.Dynamic.global.URL)(url).pathname.asInstanceOf[String]
      val Response = js.Dynamic.global.Response

      // Route requests
      if pathname == "/.well-known/agent-card.json" && method == "GET" then
        // Agent card discovery
        val headers = js.Dynamic.literal(`Content-Type` = "application/json")
        js.Promise.resolve(
          js.Dynamic.newInstance(Response)(
            JsJSON.stringify(card),
            js.Dynamic.literal(status = 200, headers = headers)
          )
        )
      else if pathname == "/" && method == "POST" then
        // JSON-RPC endpoint
        req
          .text()
          .asInstanceOf[js.Promise[String]]
          .`then`[js.Dynamic] { body =>
            handleJsonRpc(body, requestHandler)
          }
      else
        // 404
        js.Promise.resolve(
          js.Dynamic.newInstance(Response)(
            "Not Found",
            js.Dynamic.literal(status = 404)
          )
        )
    }

  /** Handle JSON-RPC request */
  private def handleJsonRpc(body: String, requestHandler: JsDefaultRequestHandler): js.Promise[js.Dynamic] =
    val Response = js.Dynamic.global.Response
    val headers = js.Dynamic.literal(`Content-Type` = "application/json")

    // Parse the JSON-RPC request
    val request = JsJSON.parse(body)
    val method = request.method.asInstanceOf[String]
    val params = request.params
    val requestId = request.id

    // Route to the appropriate handler method
    val resultPromise: js.Promise[js.Dynamic] = method match
      case "message/send" => requestHandler.sendMessage(params, js.undefined)
      case "tasks/get"    => requestHandler.getTask(params, js.undefined)
      case "tasks/cancel" => requestHandler.cancelTask(params, js.undefined)
      case _ =>
        js.Promise.resolve(
          js.Dynamic.literal(
            error = js.Dynamic.literal(
              code = -32601,
              message = s"Method not found: $method"
            )
          )
        )

    // Wrap result in JSON-RPC response format
    resultPromise
      .`then`[js.Dynamic] { result =>
        val response = js.Dynamic.literal(
          jsonrpc = "2.0",
          result = result,
          id = requestId
        )
        js.Dynamic.newInstance(Response)(
          JsJSON.stringify(response),
          js.Dynamic.literal(status = 200, headers = headers)
        )
      }
      .`catch`[js.Dynamic] { error =>
        val errorMsg = if error != null then error.toString else "Unknown error"
        val response = js.Dynamic.literal(
          jsonrpc = "2.0",
          error = js.Dynamic.literal(code = -32603, message = errorMsg),
          id = requestId
        )
        js.Dynamic.newInstance(Response)(
          JsJSON.stringify(response),
          js.Dynamic.literal(status = 200, headers = headers)
        )
      }

/** Bun.serve binding */
@js.native
@JSGlobal("Bun")
private object BunServer extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
