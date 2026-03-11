package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import zio.stream.*
import com.tjclp.scalagent.a2a.facade.*
import com.tjclp.scalagent.streaming.{AsyncIteratorOps, AsyncIterator}

/** Type-safe Scala client for A2A protocol.
  *
  * Wraps the @a2a-js/sdk Client with ZIO effects and Scala types.
  */
trait A2AClient:
  /** Get the agent card for this client's target agent */
  def agentCard: Task[AgentCard]

  /** Send a message and wait for complete response */
  def send(message: A2AMessage, config: Option[MessageSendConfiguration] = None): Task[A2ATask]

  /** Send a message and stream responses */
  def stream(message: A2AMessage, config: Option[MessageSendConfiguration] = None): ZStream[Any, Throwable, A2AResponse.StreamEvent]

  /** Get task by ID */
  def getTask(taskId: TaskId, historyLength: Option[Int] = None): Task[A2ATask]

  /** Cancel a running task */
  def cancelTask(taskId: TaskId): Task[A2ATask]

  /** Re-subscribe to task updates */
  def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent]

  /** Get agent card (fetches extended card if supported) */
  def getAgentCard: Task[AgentCard]

  /** Set push notification config for a task */
  def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig]

  /** Get push notification config for a task */
  def getPushNotificationConfig(taskId: TaskId): Task[PushNotificationConfig]

  /** List push notification configs for a task */
  def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]]

  /** Delete push notification config for a task */
  def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit]

object A2AClient:

  /** Configuration for A2A client */
  final case class Config(
      url: String,
      headers: Map[String, String] = Map.empty
  )

  /** Create a client by discovering an agent at the given URL. */
  def discover(url: String, headers: Map[String, String] = Map.empty): Task[A2AClient] =
    ZIO
      .fail(new IllegalArgumentException(s"Invalid URL scheme. Must be http:// or https://: $url"))
      .when(!url.startsWith("http://") && !url.startsWith("https://"))
      .zipRight {
        ZIO.fromPromiseJS {
          val factory = new JsClientFactory()
          factory.createFromUrl(url)
        }.map(jsClient => A2AClientLive(jsClient))
      }

  /** Create a client from an existing agent card */
  def fromCard(card: AgentCard, headers: Map[String, String] = Map.empty): Task[A2AClient] =
    ZIO.fromPromiseJS {
      val factory = new JsClientFactory()
      val jsCard = A2AConverters.toJs(card)
      factory.createFromAgentCard(jsCard)
    }.map(jsClient => A2AClientLive(jsClient))

  /** Create a client from config */
  def fromConfig(config: Config): Task[A2AClient] =
    discover(config.url, config.headers)

  /** ZLayer for A2AClient */
  def live(url: String): ZLayer[Any, Throwable, A2AClient] =
    ZLayer.fromZIO(discover(url))

  /** ZLayer for A2AClient with config */
  def live(config: Config): ZLayer[Any, Throwable, A2AClient] =
    ZLayer.fromZIO(fromConfig(config))

/** Live implementation wrapping the JS client */
private final class A2AClientLive(jsClient: JsA2AClient) extends A2AClient:

  private def toJsConfig(config: MessageSendConfiguration): JsMessageSendConfiguration =
    JsBuilders.messageSendConfiguration(
      acceptedOutputModes = Some(config.acceptedOutputModes),
      blocking = config.blocking,
      historyLength = config.historyLength,
      pushNotificationConfig = config.pushNotificationConfig.map(A2AConverters.toJs)
    )

  override def agentCard: Task[AgentCard] =
    getAgentCard

  override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
    ZIO.fromPromiseJS {
      val jsMessage = A2AConverters.toJs(message)
      val jsConfig = config.map(toJsConfig)
      val params = JsBuilders.sendMessageParams(jsMessage, jsConfig)
      jsClient.sendMessage(params)
    }.flatMap { result =>
      val dyn = result.asInstanceOf[js.Dynamic]
      dyn.kind.asInstanceOf[js.UndefOr[String]].toOption match
        case Some("task") =>
          ZIO.succeed(A2AConverters.toScala(result.asInstanceOf[JsTask]))
        case Some("message") =>
          val msg = A2AConverters.toScala(result.asInstanceOf[JsMessage])
          ZIO.succeed(A2AStreamEventParser.taskFromMessage(message, msg))
        case other =>
          ZIO.fail(new IllegalArgumentException(s"Unexpected A2A send result kind: ${other.getOrElse("<missing>")}"))
    }

  override def stream(
      message: A2AMessage,
      config: Option[MessageSendConfiguration]
  ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrap {
      ZIO.attempt {
        val jsMessage = A2AConverters.toJs(message)
        val jsConfig = config.map(toJsConfig)
        val params = JsBuilders.sendMessageParams(jsMessage, jsConfig)
        val asyncGen = jsClient.sendMessageStream(params)
        AsyncIteratorOps
          .toZStream(asyncGen.asInstanceOf[AsyncIterator[js.Any]])
          .mapZIO(A2AStreamEventParser.parse)
      }
    }

  override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
    ZIO.fromPromiseJS {
      jsClient.getTask(JsBuilders.taskQueryParams(taskId.value, historyLength))
    }.map(A2AConverters.toScala)

  override def cancelTask(taskId: TaskId): Task[A2ATask] =
    ZIO.fromPromiseJS {
      jsClient.cancelTask(JsBuilders.taskIdParams(taskId.value))
    }.map(A2AConverters.toScala)

  override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrap {
      ZIO.attempt {
        val asyncGen = jsClient.resubscribeTask(JsBuilders.taskIdParams(taskId.value))
        AsyncIteratorOps
          .toZStream(asyncGen.asInstanceOf[AsyncIterator[js.Any]])
          .mapZIO(A2AStreamEventParser.parse)
      }
    }

  override def getAgentCard: Task[AgentCard] =
    ZIO.fromPromiseJS(jsClient.getAgentCard()).map(A2AConverters.toScala)

  override def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig] =
    ZIO.fromPromiseJS {
      val params = JsBuilders.taskPushNotificationConfigParams(taskId.value, A2AConverters.toJs(config))
      jsClient.setTaskPushNotificationConfig(params)
    }.map(A2AConverters.toScalaPushNotificationConfigResult)

  override def getPushNotificationConfig(taskId: TaskId): Task[PushNotificationConfig] =
    ZIO.fromPromiseJS {
      jsClient.getTaskPushNotificationConfig(JsBuilders.taskIdParams(taskId.value))
    }.map(A2AConverters.toScalaPushNotificationConfigResult)

  override def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]] =
    ZIO.fromPromiseJS {
      jsClient.listTaskPushNotificationConfig(JsBuilders.taskIdParams(taskId.value))
    }.map { result =>
      A2AConverters.toScalaPushNotificationConfigResults(result.asInstanceOf[js.Array[js.Any]])
    }

  override def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
    ZIO.fromPromiseJS {
      val params = JsBuilders.deletePushNotificationConfigParams(taskId.value, configId)
      jsClient.deleteTaskPushNotificationConfig(params)
    }.unit

/** Extension methods for convenient message sending */
extension (client: A2AClient)
  /** Send a text message */
  def sendText(text: String, contextId: Option[ContextId] = None): Task[A2ATask] =
    client.send(A2AMessage.userText(text, contextId))

  /** Stream a text message */
  def streamText(text: String, contextId: Option[ContextId] = None): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    client.stream(A2AMessage.userText(text, contextId))

  /** Send and wait for text response */
  def askText(text: String, contextId: Option[ContextId] = None): Task[String] =
    client.sendText(text, contextId).map { task =>
      task.status.message.map(_.text).getOrElse("")
    }
