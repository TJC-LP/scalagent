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

/** Per-task A2A session logger. Writes JSONL to a configurable directory.
  *
  * This supplements the SDK's native session transcripts (which capture full agent messages)
  * with A2A-level events: task IDs, prompts, completion status, and timing.
  */
object SessionLogger:
  @js.native
  @JSImport("node:fs", JSImport.Namespace)
  private object Fs extends js.Object:
    def appendFileSync(path: String, data: String): Unit = js.native
    def mkdirSync(path: String, options: js.Dynamic): Unit = js.native
    def existsSync(path: String): Boolean = js.native

  private var logDir: Option[String] = None
  private var dirEnsured = false

  /** Configure the log directory. Call before using sink/logEvent. */
  def configure(dir: Option[String]): Unit =
    logDir = dir
    dirEnsured = false

  private def ensureDir(): Boolean =
    logDir match
      case None => false
      case Some(dir) =>
        if !dirEnsured then
          try
            if !Fs.existsSync(dir) then
              Fs.mkdirSync(dir, js.Dynamic.literal(recursive = true))
            dirEnsured = true
          catch case _: Throwable => ()
        dirEnsured

  /** Log an A2A event (prompt, completion, failure, etc.) */
  def logEvent(taskId: String, event: String, data: String): Unit =
    if !ensureDir() then return
    val dir = logDir.get
    val ts = new js.Date().toISOString().asInstanceOf[String]
    val escaped = data.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    val line = s"""{"ts":"$ts","taskId":"$taskId","event":"$event","data":"$escaped"}"""
    try Fs.appendFileSync(s"$dir/$taskId.jsonl", line + "\n")
    catch case _: Throwable => ()

object A2AServer:

  /** Server configuration */
  final case class Config(
      name: String,
      description: String,
      host: String = "localhost",
      port: Int = 3000,
      agentOptions: AgentOptions = AgentOptions.default,
      skills: List[AgentSkill] = Nil,
      sessionLogDir: Option[String] = Some("/tmp/agent-sessions"),
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
      // Configure session logger with the server's log directory
      SessionLogger.configure(config.sessionLogDir)

      val card = A2AConverters.toJs(config.toAgentCard)

      // Create executor that bridges to ClaudeAgent
      val executor = createExecutor()

      // Create task store
      val taskStore = new JsInMemoryTaskStore()

      // Create request handler
      val requestHandler = new JsDefaultRequestHandler(card, taskStore, executor)

      // Create transport handler for automatic JSON-RPC routing
      val transportHandler = new JsJsonRpcTransportHandler(requestHandler.asInstanceOf[js.Dynamic])

      // Start Bun server with routes
      bunServer = BunServer.serve(
        js.Dynamic.literal(
          hostname = config.host,
          port = config.port,
          fetch = createFetchHandler(transportHandler, card)
        )
      )

      ()
    }

  override def stop: Task[Unit] =
    ZIO.attempt {
      if bunServer != null then bunServer.stop()
      ()
    }

  private def taskIds(ctx: JsRequestContext): (TaskId, ContextId) =
    (
      TaskId(ctx.taskId),
      ContextId(ctx.contextId)
    )

  private def publishTaskSnapshot(ctx: JsRequestContext, bus: JsExecutionEventBus): Unit =
    val (taskId, contextId) = taskIds(ctx)
    bus.publish(
      A2AConverters.toJs(
        A2ATask(
          id = taskId,
          contextId = contextId,
          status = com.tjclp.scalagent.a2a.TaskStatus.working()
        )
      )
    )

  private def publishStatusUpdate(
      ctx: JsRequestContext,
      bus: JsExecutionEventBus,
      status: com.tjclp.scalagent.a2a.TaskStatus,
      finalUpdate: Boolean = false
  ): Unit =
    val (taskId, contextId) = taskIds(ctx)
    bus.publish(
      js.Dynamic.literal(
        kind = "status-update",
        taskId = taskId.value,
        contextId = contextId.value,
        status = A2AConverters.toJs(status),
        `final` = finalUpdate
      )
    )

  private def progressText(message: AgentMessage): Option[String] =
    def nonEmpty(text: String): Option[String] =
      Option.when(text.trim.nonEmpty)(text.trim)

    message match
      case AgentMessage.TaskStarted(_, description, _, _, _, _, _) =>
        nonEmpty(description)
      case AgentMessage.TaskProgress(_, progress, _, _, summary) =>
        summary.flatMap(nonEmpty).orElse(nonEmpty(progress))
      case AgentMessage.ToolProgress(_, toolName, _, elapsedTimeSeconds, _, _, _) =>
        val elapsed = math.max(1L, math.round(elapsedTimeSeconds))
        Some(s"Running ${toolName.raw} (${elapsed}s)")
      case AgentMessage.ToolUseSummary(summary, _, _, _) =>
        nonEmpty(summary)
      case AgentMessage.Assistant(assistant, _, _, _, _) =>
        val toolNames = assistant.content.collect { case ContentBlock.ToolUse(_, name, _) => name.raw }.distinct
        if toolNames.nonEmpty then Some(s"Calling ${toolNames.mkString(", ")}")
        else None
      case AgentMessage.System(SystemEvent.Status(status, _), _, _) =>
        status.map {
          case SdkStatus.Compacting   => "Compacting context"
          case SdkStatus.Custom(value) => value
        }.flatMap(nonEmpty)
      case _ =>
        None

  private def progressSink(ctx: JsRequestContext, bus: JsExecutionEventBus): QueryCollector.MessageSink =
    var lastPublished: Option[String] = None

    message =>
      progressText(message) match
        case Some(text) if lastPublished.forall(_ != text) =>
          ZIO.succeed {
            lastPublished = Some(text)
            val (_, contextId) = taskIds(ctx)
            val statusMessage = A2AMessage
              .agentText(text, Some(contextId))
              .copy(taskId = Some(TaskId(ctx.taskId)))
            publishStatusUpdate(
              ctx,
              bus,
              com.tjclp.scalagent.a2a.TaskStatus.working(Some(statusMessage))
            )
          }
        case _ =>
          ZIO.unit

  /** Create an AgentExecutor that bridges to ClaudeAgent */
  private def createExecutor(): JsAgentExecutor =
    JsExecutorBuilder.create(
      handler = (ctx, bus) => {
        // Convert JS message to prompt
        val message = A2AConverters.toScala(ctx.userMessage)
        val prompt = message.text
        val (taskId, contextId) = taskIds(ctx)

        // Log the initial prompt at the A2A level
        SessionLogger.logEvent(taskId.value, "prompt", prompt)

        // Run ClaudeAgent query (SDK writes native transcripts; progressSink publishes A2A status-updates)
        val effect = for
          result <- ClaudeAgent
            .queryComplete(
              prompt,
              config.agentOptions,
              collectionPolicy = CollectionPolicy.ResultOnly,
              sink = progressSink(ctx, bus)
            )
            .provideLayer(ClaudeAgent.live)
        yield result

        // Publish task in "working" state immediately so non-blocking sends
        // get a task ID back without waiting for the full agent response.
        publishTaskSnapshot(ctx, bus)

        // Execute and publish results
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.runToFuture {
            effect
              .map { queryResult =>
                // Convert result to A2A message
                val responseText =
                  queryResult.outcome.resultText
                    .orElse(queryResult.semanticText.toOption)
                    .getOrElse("Error: " + queryResult.outcome.toString)
                val responseMsg = A2AMessage
                  .agentText(responseText, Some(contextId))
                  .copy(taskId = Some(taskId))
                val jsMsg = A2AConverters.toJs(responseMsg)

                // Log completion
                SessionLogger.logEvent(taskId.value, "completed", responseText.take(500))

                // Publish response
                bus.publish(jsMsg)
                publishStatusUpdate(
                  ctx,
                  bus,
                  com.tjclp.scalagent.a2a.TaskStatus.completed(responseMsg),
                  finalUpdate = true
                )
                bus.finished()
              }
              .catchAll { error =>
                ZIO.succeed {
                  val errorText = s"Error: ${error.getMessage}"
                  val errorMsg = A2AMessage
                    .agentText(errorText, Some(contextId))
                    .copy(taskId = Some(taskId))

                  // Log failure
                  SessionLogger.logEvent(taskId.value, "failed", errorText)

                  bus.publish(A2AConverters.toJs(errorMsg))
                  publishStatusUpdate(
                    ctx,
                    bus,
                    com.tjclp.scalagent.a2a.TaskStatus.failed(errorMsg),
                    finalUpdate = true
                  )
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
      transportHandler: JsJsonRpcTransportHandler,
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
        // JSON-RPC endpoint - delegate to transport handler
        req
          .text()
          .asInstanceOf[js.Promise[String]]
          .`then`[js.Dynamic] { body =>
            handleJsonRpc(body, transportHandler)
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

  /** Handle JSON-RPC request via transport handler */
  private def handleJsonRpc(body: String, transportHandler: JsJsonRpcTransportHandler): js.Promise[js.Dynamic] =
    val requestId = BunJsonRpcResponses.requestIdOf(body)

    transportHandler
      .handle(body)
      .`then`[js.Dynamic](result => BunJsonRpcResponses.fromResult(result, requestId))
      .`catch`[js.Dynamic] { error =>
        val errorMsg =
          if error == null || js.isUndefined(error) then "Internal error"
          else
            val dyn = error.asInstanceOf[js.Dynamic]
            dyn.selectDynamic("message").asInstanceOf[js.UndefOr[String]].toOption.getOrElse(error.toString)
        BunJsonRpcResponses.jsonRpcError(A2AErrorCode.InternalError, errorMsg, requestId)
      }

/** Bun.serve binding */
@js.native
@JSGlobal("Bun")
private object BunServer extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
