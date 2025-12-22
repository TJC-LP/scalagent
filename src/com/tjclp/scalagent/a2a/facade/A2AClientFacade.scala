package com.tjclp.scalagent.a2a.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** JavaScript facade for @a2a-js/sdk Client */
@js.native
trait JsA2AClient extends js.Object:
  /** Send a message and wait for response */
  def sendMessage(params: JsSendMessageParams): js.Promise[JsTask | JsMessage] = js.native

  /** Send a message and stream responses */
  def sendMessageStream(params: JsSendMessageParams): js.Promise[js.Any] = js.native // AsyncGenerator

  /** Get task by ID */
  def getTask(taskId: String): js.Promise[JsTask] = js.native

  /** Cancel a task */
  def cancelTask(taskId: String): js.Promise[JsTask] = js.native

  /** Re-subscribe to task updates */
  def resubscribeTask(taskId: String): js.Promise[js.Any] = js.native // AsyncGenerator

  /** Get agent card (fetches extended card if supported) */
  def getAgentCard(): js.Promise[JsAgentCard] = js.native

  /** Set push notification config */
  def setTaskPushNotificationConfig(taskId: String, config: JsPushNotificationConfig): js.Promise[JsPushNotificationConfig] =
    js.native

  /** Get push notification config */
  def getTaskPushNotificationConfig(taskId: String): js.Promise[JsPushNotificationConfig] = js.native

/** JavaScript facade for @a2a-js/sdk ClientFactory */
@js.native
@JSImport("@a2a-js/sdk/client", "ClientFactory")
class JsClientFactory extends js.Object:
  /** Create client from agent card */
  def createFromAgentCard(agentCard: JsAgentCard): JsA2AClient = js.native

  /** Create client from URL (with auto-discovery)
    * @param baseUrl Base URL of the agent
    * @param path Path to agent card (default: /.well-known/agent-card.json)
    */
  def createFromUrl(baseUrl: String, path: js.UndefOr[String] = js.undefined): js.Promise[JsA2AClient] = js.native

/** JavaScript facade for @a2a-js/sdk AgentCardResolver */
@js.native
@JSImport("@a2a-js/sdk/client", "AgentCardResolver")
class JsAgentCardResolver extends js.Object:
  /** Resolve agent card from URL */
  def resolve(url: String): js.Promise[JsAgentCard] = js.native

/** Alternative import paths - the SDK may export from different locations */
object A2AClientModule:
  @js.native
  @JSImport("@a2a-js/sdk", "ClientFactory")
  object ClientFactory extends js.Object:
    def apply(): JsClientFactory = js.native

  @js.native
  @JSImport("@a2a-js/sdk", "AgentCardResolver")
  object AgentCardResolver extends js.Object:
    def apply(): JsAgentCardResolver = js.native

/** Client configuration options */
@js.native
trait JsClientConfig extends js.Object:
  val fetchImpl: js.UndefOr[js.Function2[String, js.Dynamic, js.Promise[js.Dynamic]]] = js.native
  val preferredTransports: js.UndefOr[js.Array[String]] = js.native

object JsClientConfig:
  def apply(
      headers: Map[String, String] = Map.empty
  ): JsClientConfig =
    val obj = js.Dynamic.literal()
    if headers.nonEmpty then
      obj.defaultRequestOptions = js.Dynamic.literal(
        headers = js.Dictionary(headers.toSeq*)
      )
    obj.asInstanceOf[JsClientConfig]
