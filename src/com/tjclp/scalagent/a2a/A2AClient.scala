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
  def send(message: A2AMessage): Task[A2ATask]

  /** Send a message and stream responses */
  def stream(message: A2AMessage): ZStream[Any, Throwable, A2AResponse.StreamEvent]

  /** Get task by ID */
  def getTask(taskId: TaskId): Task[A2ATask]

  /** Cancel a running task */
  def cancelTask(taskId: TaskId): Task[A2ATask]

  /** Re-subscribe to task updates */
  def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2ATask]

  /** Get agent card (fetches extended card if supported) */
  def getAgentCard: Task[AgentCard]

  /** Set push notification config for a task */
  def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig]

  /** Get push notification config for a task */
  def getPushNotificationConfig(taskId: TaskId): Task[PushNotificationConfig]

object A2AClient:

  /** Configuration for A2A client */
  final case class Config(
      url: String,
      headers: Map[String, String] = Map.empty
  )

  /** Create a client by discovering an agent at the given URL.
    *
    * @param url
    *   The URL of the A2A agent (must be http:// or https://)
    * @param headers
    *   Optional headers for authentication
    * @return
    *   A2AClient for communicating with the agent
    * @throws IllegalArgumentException
    *   if URL scheme is not http or https
    */
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
    ZIO.attempt {
      val factory = new JsClientFactory()
      val jsCard = A2AConverters.toJs(card)
      val jsClient = factory.createFromAgentCard(jsCard)
      A2AClientLive(jsClient)
    }

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

  override def agentCard: Task[AgentCard] =
    getAgentCard

  override def send(message: A2AMessage): Task[A2ATask] =
    ZIO.fromPromiseJS {
      val jsMessage = A2AConverters.toJs(message)
      val params = JsBuilders.sendMessageParams(jsMessage)
      jsClient.sendMessage(params)
    }.flatMap { result =>
      // Result can be Message or Task - we always return Task
      val dyn = result.asInstanceOf[js.Dynamic]
      if dyn.kind.asInstanceOf[String] == "task" then ZIO.succeed(A2AConverters.toScala(result.asInstanceOf[JsTask]))
      else
        // If we got a message back, wrap it in a completed task
        val msg = A2AConverters.toScala(result.asInstanceOf[JsMessage])
        ZIO.succeed(
          A2ATask(
            id = TaskId.generate,
            contextId = msg.contextId,
            status = TaskStatus.completed(msg),
            history = List(message, msg)
          )
        )
    }

  override def stream(message: A2AMessage): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrap {
      ZIO.fromPromiseJS {
        val jsMessage = A2AConverters.toJs(message)
        val params = JsBuilders.sendMessageParams(jsMessage)
        jsClient.sendMessageStream(params)
      }.map { asyncGen =>
        // Convert JS AsyncGenerator to ZStream
        AsyncIteratorOps
          .toZStream(asyncGen.asInstanceOf[AsyncIterator[js.Any]])
          .map { jsEvent =>
            val dyn = jsEvent.asInstanceOf[js.Dynamic]
            val kind = dyn.kind.asInstanceOf[String]
            kind match
              case "task" =>
                val task = A2AConverters.toScala(jsEvent.asInstanceOf[JsTask])
                A2AResponse.StreamEvent.TaskStatusUpdate(task.id, task.status, task.isTerminal)
              case "message" =>
                val msg = A2AConverters.toScala(jsEvent.asInstanceOf[JsMessage])
                A2AResponse.StreamEvent.TaskMessage(msg.taskId.getOrElse(TaskId.generate), msg)
              case "artifact" =>
                val artifact = A2AConverters.toScala(dyn.artifact.asInstanceOf[JsArtifact])
                val taskId = TaskId(dyn.id.asInstanceOf[String])
                A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, artifact)
              case _ =>
                A2AResponse.StreamEvent.TaskStatusUpdate(TaskId.generate, TaskStatus.working(), false)
          }
      }
    }

  override def getTask(taskId: TaskId): Task[A2ATask] =
    ZIO.fromPromiseJS(jsClient.getTask(taskId.value)).map(A2AConverters.toScala)

  override def cancelTask(taskId: TaskId): Task[A2ATask] =
    ZIO.fromPromiseJS(jsClient.cancelTask(taskId.value)).map(A2AConverters.toScala)

  override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2ATask] =
    ZStream.unwrap {
      ZIO.fromPromiseJS(jsClient.resubscribeTask(taskId.value)).map { asyncGen =>
        AsyncIteratorOps
          .toZStream(asyncGen.asInstanceOf[AsyncIterator[js.Any]])
          .map(jsVal => A2AConverters.toScala(jsVal.asInstanceOf[JsTask]))
      }
    }

  override def getAgentCard: Task[AgentCard] =
    ZIO.fromPromiseJS(jsClient.getAgentCard()).map(A2AConverters.toScala)

  override def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig] =
    ZIO.fromPromiseJS {
      jsClient.setTaskPushNotificationConfig(taskId.value, A2AConverters.toJs(config))
    }.map(A2AConverters.toScala)

  override def getPushNotificationConfig(taskId: TaskId): Task[PushNotificationConfig] =
    ZIO.fromPromiseJS(jsClient.getTaskPushNotificationConfig(taskId.value)).map(A2AConverters.toScala)

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
