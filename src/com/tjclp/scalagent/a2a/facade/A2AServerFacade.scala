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

/** Event bus for publishing responses */
@js.native
trait JsExecutionEventBus extends js.Object:
  /** Publish an event (Message or Task) */
  def publish(event: JsMessage | JsTask): Unit = js.native

  /** Signal that execution is finished */
  def finished(): Unit = js.native

/** Default request handler */
@js.native
@JSImport("@a2a-js/sdk/server", "DefaultRequestHandler")
class JsDefaultRequestHandler(
    agentCard: JsAgentCard,
    taskStore: JsTaskStore,
    executor: JsAgentExecutor
) extends js.Object:
  def getAgentCard(): JsAgentCard = js.native

  /** Send a message and get a task/message response */
  def sendMessage(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = js.native

  /** Get task by ID */
  def getTask(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = js.native

  /** Cancel a running task */
  def cancelTask(params: js.Dynamic, context: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = js.native

/** Task store interface */
@js.native
trait JsTaskStore extends js.Object:
  def get(taskId: String): js.Promise[JsTask | Null] = js.native
  def save(task: JsTask): js.Promise[Unit] = js.native
  def delete(taskId: String): js.Promise[Unit] = js.native

/** In-memory task store */
@js.native
@JSImport("@a2a-js/sdk/server", "InMemoryTaskStore")
class JsInMemoryTaskStore extends JsTaskStore

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

/** User builder for authentication */
@js.native
@JSImport("@a2a-js/sdk/server/authentication", "UserBuilder")
object JsUserBuilder extends js.Object:
  def noAuthentication(): js.Dynamic = js.native

/** Alternative module paths */
object A2AServerModule:
  @js.native
  @JSImport("@a2a-js/sdk", "DefaultRequestHandler")
  class DefaultRequestHandler(
      agentCard: JsAgentCard,
      taskStore: JsTaskStore,
      executor: JsAgentExecutor
  ) extends js.Object

  @js.native
  @JSImport("@a2a-js/sdk", "InMemoryTaskStore")
  class InMemoryTaskStore extends JsTaskStore

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
