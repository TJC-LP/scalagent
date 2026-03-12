package com.tjclp.scalagent.a2a.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** JavaScript facade for @a2a-js/sdk Client */
@js.native
trait JsA2AClient extends js.Object:
  /** Send a message and wait for response */
  def sendMessage(params: JsSendMessageParams, options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsTask | JsMessage] =
    js.native

  /** Send a message and stream responses */
  def sendMessageStream(params: JsSendMessageParams, options: js.UndefOr[js.Dynamic] = js.undefined): js.Any = js.native // AsyncGenerator

  /** Get task by query params */
  def getTask(params: js.Dynamic, options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsTask] = js.native

  /** Cancel a task */
  def cancelTask(params: js.Dynamic, options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsTask] = js.native

  /** Re-subscribe to task updates */
  def resubscribeTask(params: js.Dynamic, options: js.UndefOr[js.Dynamic] = js.undefined): js.Any = js.native // AsyncGenerator

  /** Get agent card (fetches extended card if supported) */
  def getAgentCard(options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsAgentCard] = js.native

  /** Set push notification config */
  def setTaskPushNotificationConfig(
      params: js.Dynamic,
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[js.Dynamic] = js.native

  /** Get push notification config */
  def getTaskPushNotificationConfig(
      params: js.Dynamic,
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[js.Dynamic] = js.native

  /** List push notification configs */
  def listTaskPushNotificationConfig(
      params: js.Dynamic,
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[js.Dynamic] = js.native

  /** Delete push notification config */
  def deleteTaskPushNotificationConfig(
      params: js.Dynamic,
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[js.Dynamic] = js.native

/** JavaScript facade for @a2a-js/sdk ClientFactory */
@js.native
@JSImport("@a2a-js/sdk/client", "ClientFactory")
class JsClientFactory extends js.Object:
  /** Create client from agent card (async in 0.3.12+) */
  def createFromAgentCard(agentCard: JsAgentCard): js.Promise[JsA2AClient] = js.native

  /** Create client from URL (with auto-discovery) */
  def createFromUrl(baseUrl: String, path: js.UndefOr[String] = js.undefined): js.Promise[JsA2AClient] = js.native

/** JavaScript facade for @a2a-js/sdk AgentCardResolver */
@js.native
@JSImport("@a2a-js/sdk/client", "AgentCardResolver")
class JsAgentCardResolver extends js.Object:
  /** Resolve agent card from URL */
  def resolve(url: String): js.Promise[JsAgentCard] = js.native
