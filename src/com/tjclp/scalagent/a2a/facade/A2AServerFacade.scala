package com.tjclp.scalagent.a2a.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** JavaScript facade for @a2a-js/sdk server components */

/** AgentExecutor interface - the business logic handler */
@js.native
trait JsAgentExecutor extends js.Object:
  /** Execute a request and publish events */
  def execute(requestContext: JsRequestContext, eventBus: JsExecutionEventBus): js.Promise[Unit] = js.native

  /** Cancel a running task */
  def cancelTask(taskId: String, eventBus: JsExecutionEventBus): js.Promise[Unit] = js.native

/** Request context passed to executor */
@js.native
trait JsRequestContext extends js.Object:
  val userMessage: JsMessage = js.native
  val taskId: String = js.native
  val contextId: String = js.native
  val task: js.UndefOr[JsTask] = js.native
  val referenceTasks: js.UndefOr[js.Array[JsTask]] = js.native
  val context: js.UndefOr[js.Dynamic] = js.native // ServerCallContext

/** Event bus for publishing responses */
@js.native
trait JsExecutionEventBus extends js.Object:
  /** Publish an event (Message, Task, TaskStatusUpdateEvent, or TaskArtifactUpdateEvent) */
  def publish(event: js.Any): Unit = js.native

  /** Signal that execution is finished */
  def finished(): Unit = js.native

/** Default request handler */
@js.native
@JSImport("@a2a-js/sdk/server", "DefaultRequestHandler")
class JsDefaultRequestHandler(
    agentCard: JsAgentCard,
    taskStore: JsTaskStore,
    executor: JsAgentExecutor,
    eventBusManager: js.UndefOr[js.Dynamic] = js.undefined,
    pushNotificationStore: js.UndefOr[js.Dynamic] = js.undefined,
    pushNotificationSender: js.UndefOr[js.Dynamic] = js.undefined,
    extendedAgentCardProvider: js.UndefOr[js.Dynamic] = js.undefined
) extends js.Object:
  def getAgentCard(): js.Promise[JsAgentCard] = js.native
  def getAuthenticatedExtendedAgentCard(context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsAgentCard] = js.native
  def sendMessage(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = js.native
  def sendMessageStream(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Any = js.native // AsyncGenerator
  def getTask(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = js.native
  def cancelTask(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = js.native
  def setTaskPushNotificationConfig(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] =
    js.native
  def getTaskPushNotificationConfig(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] =
    js.native
  def listTaskPushNotificationConfigs(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] =
    js.native
  def deleteTaskPushNotificationConfig(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] =
    js.native
  def resubscribe(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Any = js.native // AsyncGenerator

/** Task store interface */
@js.native
trait JsTaskStore extends js.Object:
  def load(taskId: String, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsTask | Null] = js.native
  def save(task: JsTask, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[Unit] = js.native

/** In-memory task store */
@js.native
@JSImport("@a2a-js/sdk/server", "InMemoryTaskStore")
class JsInMemoryTaskStore extends JsTaskStore

/** Push notification store interface */
@js.native
trait JsPushNotificationStore extends js.Object:
  def save(taskId: String, pushNotificationConfig: js.Dynamic): js.Promise[Unit] = js.native
  def load(taskId: String): js.Promise[js.Array[js.Dynamic]] = js.native
  def delete(taskId: String, configId: js.UndefOr[String] = js.undefined): js.Promise[Unit] = js.native

/** In-memory push notification store */
@js.native
@JSImport("@a2a-js/sdk/server", "InMemoryPushNotificationStore")
class JsInMemoryPushNotificationStore extends JsPushNotificationStore

/** JSON-RPC transport handler - routes requests to A2ARequestHandler */
@js.native
@JSImport("@a2a-js/sdk/server", "JsonRpcTransportHandler")
class JsJsonRpcTransportHandler(requestHandler: js.Dynamic) extends js.Object:
  /** Handle an incoming JSON-RPC request. Returns Promise or AsyncGenerator. */
  def handle(requestBody: js.Any, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = js.native

/** Express handler for agent card */
@js.native
@JSImport("@a2a-js/sdk/server/express", "agentCardHandler")
object JsAgentCardHandler extends js.Object:
  def apply(options: js.Dynamic): js.Function3[js.Dynamic, js.Dynamic, js.Function0[Unit], Unit] = js.native

/** Express handler for JSON-RPC */
@js.native
@JSImport("@a2a-js/sdk/server/express", "jsonRpcHandler")
object JsJsonRpcHandler extends js.Object:
  def apply(options: js.Dynamic): js.Function3[js.Dynamic, js.Dynamic, js.Function0[Unit], Unit] = js.native

/** Express handler for REST */
@js.native
@JSImport("@a2a-js/sdk/server/express", "restHandler")
object JsRestHandler extends js.Object:
  def apply(options: js.Dynamic): js.Function3[js.Dynamic, js.Dynamic, js.Function0[Unit], Unit] = js.native

/** Helper to create an AgentExecutor from a Scala function */
object JsExecutorBuilder:
  /** Create a JS executor that delegates to a Scala function */
  def create(
      handler: (JsRequestContext, JsExecutionEventBus) => js.Promise[Unit],
      cancelHandler: (String, JsExecutionEventBus) => js.Promise[Unit] = (_, bus) => {
        bus.finished()
        js.Promise.resolve(())
      }
  ): JsAgentExecutor =
    js.Dynamic
      .literal(
        execute = handler: js.Function2[JsRequestContext, JsExecutionEventBus, js.Promise[Unit]],
        cancelTask = cancelHandler: js.Function2[String, JsExecutionEventBus, js.Promise[Unit]]
      )
      .asInstanceOf[JsAgentExecutor]
