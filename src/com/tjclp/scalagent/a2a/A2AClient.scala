package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import java.util.concurrent.TimeoutException
import zio.*
import zio.stream.*
import com.tjclp.scalagent.a2a.facade.*
import com.tjclp.scalagent.streaming.{AsyncIteratorOps, AsyncIterator}

/**
 * Type-safe Scala client for A2A protocol.
 *
 * Wraps the @a2a-js/sdk Client with ZIO effects and Scala types.
 */
trait A2AClient:
  /** Get the agent card for this client's target agent */
  def agentCard: Task[AgentCard]

  /** Send a message and wait for complete response */
  def send(message: A2AMessage, config: Option[MessageSendConfiguration] = None): Task[A2ATask]

  /** Submit a message without holding the HTTP request until terminal state. */
  def submit(message: A2AMessage, config: Option[MessageSendConfiguration] = None): Task[A2ATask] =
    val asyncConfig = config
      .getOrElse(MessageSendConfiguration.default)
      .copy(blocking = Some(false))
    send(message, Some(asyncConfig))

  /** Poll a task until it reaches a terminal state. */
  def awaitTask(
    taskId: TaskId,
    pollEvery: Duration = 1.second,
    timeout: Option[Duration] = None,
    historyLength: Option[Int] = None,
  ): Task[A2ATask] =
    def loop: Task[A2ATask] =
      getTask(taskId, historyLength).flatMap { task =>
        if task.isTerminal then ZIO.succeed(task)
        else ZIO.sleep(pollEvery) *> loop
      }

    timeout match
      case Some(duration) =>
        loop.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} did not finish within $duration"))(duration)
      case None =>
        loop

  /** Submit a message and poll `tasks/get` until the task reaches a terminal state. */
  def sendAndPoll(
    message: A2AMessage,
    config: Option[MessageSendConfiguration] = None,
    pollEvery: Duration = 1.second,
    timeout: Option[Duration] = None,
    historyLength: Option[Int] = None,
  ): Task[A2ATask] =
    submit(message, config).flatMap { task =>
      if task.isTerminal then ZIO.succeed(task)
      else awaitTask(task.id, pollEvery, timeout, historyLength)
    }

  /** Send a message and stream responses */
  def stream(message: A2AMessage, config: Option[MessageSendConfiguration] = None)
    : ZStream[Any, Throwable, A2AResponse.StreamEvent]

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

  /**
   * Get push notification config for a task.
   * @param taskId The task identifier
   * @param configId Optional push notification configuration identifier for non-default configs
   */
  def getPushNotificationConfig(taskId: TaskId, configId: Option[String] = None): Task[PushNotificationConfig]

  /** List push notification configs for a task */
  def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]]

  /**
   * Delete push notification config for a task.
   * @param taskId The task identifier (passed as `id` per SDK 0.3.12 DeleteTaskPushNotificationConfigParams)
   * @param configId The push notification configuration identifier
   */
  def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit]
end A2AClient

object A2AClient:

  /** Configuration for A2A client */
  final case class Config(
    url: String,
    headers: Map[String, String] = Map.empty)

  /** Create a client by discovering an agent at the given URL. */
  def discover(url: String, headers: Map[String, String] = Map.empty): Task[A2AClient] =
    ZIO
      .fail(new IllegalArgumentException(s"Invalid URL scheme. Must be http:// or https://: $url"))
      .when(!url.startsWith("http://") && !url.startsWith("https://"))
      .zipRight {
        ZIO
          .fromPromiseJS {
            val factory = new JsClientFactory()
            factory.createFromUrl(url)
          }
          .map(jsClient => A2AClientLive(jsClient))
      }

  /** Create a client from an existing agent card */
  def fromCard(card: AgentCard, headers: Map[String, String] = Map.empty): Task[A2AClient] =
    ZIO
      .fromPromiseJS {
        val factory = new JsClientFactory()
        val jsCard  = A2AConverters.toJs(card)
        factory.createFromAgentCard(jsCard)
      }
      .map(jsClient => A2AClientLive(jsClient))

  /** Create a client from config */
  def fromConfig(config: Config): Task[A2AClient] =
    discover(config.url, config.headers)

  /** ZLayer for A2AClient */
  def live(url: String): ZLayer[Any, Throwable, A2AClient] =
    ZLayer.fromZIO(discover(url))

  /** ZLayer for A2AClient with config */
  def live(config: Config): ZLayer[Any, Throwable, A2AClient] =
    ZLayer.fromZIO(fromConfig(config))
end A2AClient

/** Live implementation wrapping the JS client */
private final class A2AClientLive(jsClient: JsA2AClient) extends A2AClient:

  private def toJsConfig(config: MessageSendConfiguration): JsMessageSendConfiguration =
    JsBuilders.messageSendConfiguration(
      acceptedOutputModes = Some(config.acceptedOutputModes),
      blocking = config.blocking,
      historyLength = config.historyLength,
      pushNotificationConfig = config.pushNotificationConfig.map(A2AConverters.toJs),
    )

  override def agentCard: Task[AgentCard] =
    getAgentCard

  override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
    ZIO
      .fromPromiseJS {
        val jsMessage       = A2AConverters.toJs(message)
        val effectiveConfig = config match
          case Some(value) if value.blocking.isDefined => Some(value)
          case Some(value)                             => Some(value.copy(blocking = Some(true)))
          case None => Some(MessageSendConfiguration.default.copy(blocking = Some(true)))
        val jsConfig = effectiveConfig.map(toJsConfig)
        val params   = JsBuilders.sendMessageParams(jsMessage, jsConfig)
        jsClient.sendMessage(params)
      }
      .flatMap { result =>
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
    config: Option[MessageSendConfiguration],
  ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrap {
      ZIO.attempt {
        val jsMessage = A2AConverters.toJs(message)
        val jsConfig  = config.map(toJsConfig)
        val params    = JsBuilders.sendMessageParams(jsMessage, jsConfig)
        val asyncGen  = jsClient.sendMessageStream(params)
        AsyncIteratorOps
          .toZStream(asyncGen.asInstanceOf[AsyncIterator[js.Any]])
          .mapZIO(A2AStreamEventParser.parse)
      }
    }

  override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
    ZIO
      .fromPromiseJS {
        jsClient.getTask(JsBuilders.taskQueryParams(taskId.value, historyLength))
      }
      .map(A2AConverters.toScala)

  override def cancelTask(taskId: TaskId): Task[A2ATask] =
    ZIO
      .fromPromiseJS {
        jsClient.cancelTask(JsBuilders.taskIdParams(taskId.value))
      }
      .map(A2AConverters.toScala)

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
    ZIO
      .fromPromiseJS {
        val params = JsBuilders.taskPushNotificationConfigParams(taskId.value, A2AConverters.toJs(config))
        jsClient.setTaskPushNotificationConfig(params)
      }
      .map(A2AConverters.toScalaPushNotificationConfigResult)

  override def getPushNotificationConfig(taskId: TaskId, configId: Option[String]): Task[PushNotificationConfig] =
    ZIO
      .fromPromiseJS {
        jsClient.getTaskPushNotificationConfig(JsBuilders.getPushNotificationConfigParams(taskId.value, configId))
      }
      .map(A2AConverters.toScalaPushNotificationConfigResult)

  override def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]] =
    ZIO
      .fromPromiseJS {
        jsClient.listTaskPushNotificationConfig(JsBuilders.taskIdParams(taskId.value))
      }
      .map { result => A2AConverters.toScalaPushNotificationConfigResults(result.asInstanceOf[js.Array[js.Any]]) }

  override def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
    ZIO.fromPromiseJS {
      val params = JsBuilders.deletePushNotificationConfigParams(taskId.value, configId)
      jsClient.deleteTaskPushNotificationConfig(params)
    }.unit
end A2AClientLive

/** Extension methods for convenient message sending */
extension (client: A2AClient)
  /** Send a text message */
  def sendText(text: String, contextId: Option[ContextId] = None): Task[A2ATask] =
    client.send(A2AMessage.userText(text, contextId))

  /** Submit a text message without waiting for terminal state. */
  def submitText(text: String, contextId: Option[ContextId] = None): Task[A2ATask] =
    client.submit(A2AMessage.userText(text, contextId))

  /** Submit a text message and poll until terminal state. */
  def sendAndPollText(
    text: String,
    contextId: Option[ContextId] = None,
    pollEvery: Duration = 1.second,
    timeout: Option[Duration] = None,
  ): Task[A2ATask] =
    client.sendAndPoll(A2AMessage.userText(text, contextId), pollEvery = pollEvery, timeout = timeout)

  /** Stream a text message */
  def streamText(text: String, contextId: Option[ContextId] = None): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    client.stream(A2AMessage.userText(text, contextId))

  /** Send and wait for text response */
  def askText(text: String, contextId: Option[ContextId] = None): Task[String] =
    client.sendAndPollText(text, contextId).map { task => task.status.message.map(_.text).getOrElse("") }
end extension
