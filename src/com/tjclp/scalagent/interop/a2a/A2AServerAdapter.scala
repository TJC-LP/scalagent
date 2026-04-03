package com.tjclp.scalagent.interop.a2a

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.stream.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.core.a2a.A2AEndpoint
import com.tjclp.scalagent.a2a.*
import com.tjclp.scalagent.a2a.facade.*
import com.tjclp.scalagent.config.AgentOptions
import com.tjclp.scalagent.errors.AgentError

/** Exposes any `Agent[Any, String, O]` as an A2A endpoint.
  *
  * The adapter bridges incoming A2A messages to `Agent.run()` and maps
  * the resulting `AgentEvent` stream back to A2A protocol events.
  */
object A2AServerAdapter:

  /** Configuration for exposing an agent as A2A. */
  final case class Config(
      name: String,
      description: String,
      host: String = "localhost",
      port: Int = 3000,
      skills: List[AgentSkill] = Nil
  ):
    def toAgentCard: AgentCard =
      AgentCard(
        name = name,
        description = description,
        url = s"http://$host:$port",
        capabilities = AgentCapabilities(streaming = true),
        skills = skills
      )

  /** Create an A2A endpoint from any string-input agent.
    *
    * `agentOptions` is interpreted as a compatibility shim for default budget/turn policy
    * so existing call sites can keep passing it while the DSL matures.
    */
  def expose[O](
      agent: Agent[Any, String, O],
      config: Config,
      agentOptions: AgentOptions = AgentOptions.default
  ): ZIO[Scope, Throwable, A2AEndpoint] =
    for
      runtime <- ZIO.runtime[Any]
      endpoint <- ZIO.acquireRelease(
        start(agent, config, policyFromAgentOptions(agentOptions), runtime)
      )(_.stop.ignore)
    yield endpoint

  private def start[O](
      agent: Agent[Any, String, O],
      config: Config,
      policy: ExecutionPolicy,
      runtime: Runtime[Any]
  ): Task[A2AEndpoint] =
    ZIO.attempt(DslA2AEndpointLive(agent, config, policy, runtime)).tap(_.start)

  private def policyFromAgentOptions(options: AgentOptions): ExecutionPolicy =
    ExecutionPolicy(
      budget = options.maxBudgetUsd.map(Budget.usd).getOrElse(Budget.Unlimited),
      maxTurns = options.maxTurns
    )

  /** Map an agent's event stream to A2A text messages.
    *
    * Useful for custom A2A server implementations that want to publish agent progress as A2A task messages.
    */
  def eventsToA2AMessages(
      events: ZStream[Any, AgentError, AgentEvent]
  ): ZStream[Any, AgentError, A2AMessage] =
    events.flatMap { event =>
      A2AEventMapper.toA2AMessage(event) match
        case Some(msg) => ZStream.succeed(msg)
        case None      => ZStream.empty
    }

private final class DslA2AEndpointLive[O](
    agent: Agent[Any, String, O],
    config: A2AServerAdapter.Config,
    policy: ExecutionPolicy,
    runtime: Runtime[Any]
) extends A2AEndpoint:

  private var bunServer: js.Dynamic = null

  override def url: String = config.toAgentCard.url

  override def card: AgentCard = config.toAgentCard

  override def start: Task[Unit] =
    ZIO.attempt {
      val jsCard = A2AConverters.toJs(config.toAgentCard)
      val executor = createExecutor()
      val taskStore = new JsInMemoryTaskStore()
      val requestHandler = new JsDefaultRequestHandler(jsCard, taskStore, executor)
      val transportHandler = new JsJsonRpcTransportHandler(requestHandler.asInstanceOf[js.Dynamic])

      bunServer = BunServer.serve(
        js.Dynamic.literal(
          hostname = config.host,
          port = config.port,
          fetch = createFetchHandler(transportHandler, jsCard)
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
    (TaskId(ctx.taskId), ContextId(ctx.contextId))

  private def publishTaskSnapshot(ctx: JsRequestContext, bus: JsExecutionEventBus): Unit =
    val (taskId, contextId) = taskIds(ctx)
    bus.publish(
      A2AConverters.toJs(
        A2ATask(
          id = taskId,
          contextId = contextId,
          status = TaskStatus.working()
        )
      )
    )

  private def publishStatusUpdate(
      ctx: JsRequestContext,
      bus: JsExecutionEventBus,
      status: TaskStatus,
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

  private def publishMessage(
      ctx: JsRequestContext,
      bus: JsExecutionEventBus,
      text: String
  ): Unit =
    val (taskId, contextId) = taskIds(ctx)
    val response = A2AMessage.agentText(text, Some(contextId)).copy(taskId = Some(taskId))
    bus.publish(A2AConverters.toJs(response))

  private def createExecutor(): JsAgentExecutor =
    JsExecutorBuilder.create(
      handler = (ctx, bus) => {
        val prompt = A2AConverters.toScala(ctx.userMessage).text
        val (taskId, contextId) = taskIds(ctx)
        publishTaskSnapshot(ctx, bus)

        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.runToFuture {
            ZIO
              .scoped {
                val run = agent.run((), prompt, policy)

                run.events.runForeach {
                  case AgentEvent.Status(value) =>
                    ZIO.succeed {
                      val statusMessage = A2AMessage.agentText(value, Some(contextId)).copy(taskId = Some(taskId))
                      publishStatusUpdate(ctx, bus, TaskStatus.working(Some(statusMessage)))
                    }
                  case AgentEvent.TextDelta(text) =>
                    ZIO.succeed(publishMessage(ctx, bus, text))
                  case _: AgentEvent.Completed =>
                    ZIO.unit
                  case _ =>
                    ZIO.unit
                } *>
                  run.result.flatMap { output =>
                    ZIO.succeed {
                      val responseText = String.valueOf(output)
                      val response = A2AMessage.agentText(responseText, Some(contextId)).copy(taskId = Some(taskId))
                      publishStatusUpdate(ctx, bus, TaskStatus.completed(response), finalUpdate = true)
                      bus.publish(A2AConverters.toJs(response))
                      bus.finished()
                    }
                  }
              }
              .catchAll { error =>
                ZIO.succeed {
                  val errorText = s"Error: ${error.getMessage}"
                  val errorMessage = A2AMessage.agentText(errorText, Some(contextId)).copy(taskId = Some(taskId))
                  publishStatusUpdate(ctx, bus, TaskStatus.failed(errorMessage), finalUpdate = true)
                  bus.publish(A2AConverters.toJs(errorMessage))
                  bus.finished()
                }
              }
          }.toJSPromise.`then`[Unit](_ => ())
        }
      },
      cancelHandler = (_, bus) => {
        bus.finished()
        js.Promise.resolve(())
      }
    )

  private def createFetchHandler(
      transportHandler: JsJsonRpcTransportHandler,
      card: JsAgentCard
  ): js.Function1[js.Dynamic, js.Promise[js.Dynamic]] =
    (req: js.Dynamic) => {
      val url = req.url.asInstanceOf[String]
      val method = req.method.asInstanceOf[String]
      val pathname = js.Dynamic.newInstance(js.Dynamic.global.URL)(url).pathname.asInstanceOf[String]
      val Response = js.Dynamic.global.Response

      if pathname == "/.well-known/agent-card.json" && method == "GET" then
        val headers = js.Dynamic.literal(`Content-Type` = "application/json")
        js.Promise.resolve(
          js.Dynamic.newInstance(Response)(
            JsJSON.stringify(card),
            js.Dynamic.literal(status = 200, headers = headers)
          )
        )
      else if pathname == "/" && method == "POST" then
        req
          .text()
          .asInstanceOf[js.Promise[String]]
          .`then`[js.Dynamic](body => handleJsonRpc(body, transportHandler))
      else
        js.Promise.resolve(
          js.Dynamic.newInstance(Response)(
            "Not Found",
            js.Dynamic.literal(status = 404)
          )
        )
    }

  private def handleJsonRpc(
      body: String,
      transportHandler: JsJsonRpcTransportHandler
  ): js.Promise[js.Dynamic] =
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

@js.native
@JSGlobal("Bun")
private object BunServer extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
