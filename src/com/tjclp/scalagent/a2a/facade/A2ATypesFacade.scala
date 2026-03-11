package com.tjclp.scalagent.a2a.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import com.tjclp.scalagent.a2a.MessageId

/** JavaScript type facades for @a2a-js/sdk types.
  *
  * These facades mirror the TypeScript types from the SDK, allowing us to interop with the
  * JavaScript implementation.
  */

/** JS AgentCard type */
@js.native
trait JsAgentCard extends js.Object:
  val name: String = js.native
  val description: String = js.native
  val url: String = js.native
  val version: js.UndefOr[String] = js.native
  val protocolVersion: js.UndefOr[String] = js.native
  val provider: js.UndefOr[JsAgentProvider] = js.native
  val documentationUrl: js.UndefOr[String] = js.native
  val iconUrl: js.UndefOr[String] = js.native
  val capabilities: js.UndefOr[JsAgentCapabilities] = js.native
  val preferredTransport: js.UndefOr[String] = js.native
  val additionalInterfaces: js.UndefOr[js.Array[JsAgentInterface]] = js.native
  val defaultInputModes: js.UndefOr[js.Array[String]] = js.native
  val defaultOutputModes: js.UndefOr[js.Array[String]] = js.native
  val skills: js.UndefOr[js.Array[JsAgentSkill]] = js.native
  val security: js.UndefOr[js.Array[js.Dynamic]] = js.native
  val securitySchemes: js.UndefOr[js.Dynamic] = js.native
  val signatures: js.UndefOr[js.Array[JsAgentCardSignature]] = js.native
  val supportsAuthenticatedExtendedCard: js.UndefOr[Boolean] = js.native

@js.native
trait JsAgentProvider extends js.Object:
  val organization: String = js.native
  val url: String = js.native

@js.native
trait JsAgentCapabilities extends js.Object:
  val streaming: js.UndefOr[Boolean] = js.native
  val pushNotifications: js.UndefOr[Boolean] = js.native
  val stateTransitionHistory: js.UndefOr[Boolean] = js.native
  val extensions: js.UndefOr[js.Array[JsAgentExtension]] = js.native

@js.native
trait JsAgentExtension extends js.Object:
  val uri: String = js.native
  val description: js.UndefOr[String] = js.native
  val params: js.UndefOr[js.Dynamic] = js.native
  val required: js.UndefOr[Boolean] = js.native

@js.native
trait JsAgentSkill extends js.Object:
  val id: String = js.native
  val name: String = js.native
  val description: String = js.native
  val tags: js.UndefOr[js.Array[String]] = js.native
  val examples: js.UndefOr[js.Array[String]] = js.native
  val inputModes: js.UndefOr[js.Array[String]] = js.native
  val outputModes: js.UndefOr[js.Array[String]] = js.native
  val security: js.UndefOr[js.Array[js.Dynamic]] = js.native

@js.native
trait JsAgentInterface extends js.Object:
  val transport: String = js.native
  val url: String = js.native

@js.native
trait JsAgentCardSignature extends js.Object:
  val `protected`: String = js.native
  val signature: String = js.native
  val header: js.UndefOr[js.Dynamic] = js.native

/** JS Message type */
@js.native
trait JsMessage extends js.Object:
  val kind: String = js.native // "message"
  val messageId: String = js.native
  val role: String = js.native // "user" | "agent"
  val parts: js.Array[JsPart] = js.native
  val contextId: js.UndefOr[String] = js.native
  val taskId: js.UndefOr[String] = js.native
  val referenceTaskIds: js.UndefOr[js.Array[String]] = js.native
  val extensions: js.UndefOr[js.Array[String]] = js.native
  val metadata: js.UndefOr[js.Dynamic] = js.native

/** JS Part types (discriminated union) */
@js.native
trait JsPart extends js.Object:
  val kind: String = js.native // "text" | "file" | "data"
  val metadata: js.UndefOr[js.Dynamic] = js.native

@js.native
trait JsTextPart extends JsPart:
  val text: String = js.native

@js.native
trait JsFilePart extends JsPart:
  val file: JsFileContent = js.native

@js.native
trait JsDataPart extends JsPart:
  val data: js.Dynamic = js.native

@js.native
trait JsFileContent extends js.Object:
  val bytes: js.UndefOr[String] = js.native
  val uri: js.UndefOr[String] = js.native
  val name: js.UndefOr[String] = js.native
  val mimeType: js.UndefOr[String] = js.native

/** JS Task type */
@js.native
trait JsTask extends js.Object:
  val kind: String = js.native // "task"
  val id: String = js.native
  val contextId: String = js.native
  val status: JsTaskStatus = js.native
  val artifacts: js.UndefOr[js.Array[JsArtifact]] = js.native
  val history: js.UndefOr[js.Array[JsMessage]] = js.native
  val metadata: js.UndefOr[js.Dynamic] = js.native

@js.native
trait JsTaskStatus extends js.Object:
  val state: String = js.native
  val message: js.UndefOr[JsMessage] = js.native
  val timestamp: js.UndefOr[String] = js.native

@js.native
trait JsArtifact extends js.Object:
  val artifactId: String = js.native
  val parts: js.Array[JsPart] = js.native
  val name: js.UndefOr[String] = js.native
  val description: js.UndefOr[String] = js.native
  val extensions: js.UndefOr[js.Array[String]] = js.native
  val metadata: js.UndefOr[js.Dynamic] = js.native

/** JS stream event types */
@js.native
trait JsTaskStatusUpdateEvent extends js.Object:
  val kind: String = js.native // "status-update"
  val taskId: String = js.native
  val contextId: String = js.native
  val status: JsTaskStatus = js.native
  val `final`: Boolean = js.native
  val metadata: js.UndefOr[js.Dynamic] = js.native

@js.native
trait JsTaskArtifactUpdateEvent extends js.Object:
  val kind: String = js.native // "artifact-update"
  val taskId: String = js.native
  val contextId: String = js.native
  val artifact: JsArtifact = js.native
  val append: js.UndefOr[Boolean] = js.native
  val lastChunk: js.UndefOr[Boolean] = js.native
  val metadata: js.UndefOr[js.Dynamic] = js.native

/** JS Push notification config */
@js.native
trait JsPushNotificationConfig extends js.Object:
  val url: String = js.native
  val id: js.UndefOr[String] = js.native
  val token: js.UndefOr[String] = js.native
  val authentication: js.UndefOr[JsPushNotificationAuth] = js.native

@js.native
trait JsTaskPushNotificationConfig extends js.Object:
  val taskId: String = js.native
  val pushNotificationConfig: JsPushNotificationConfig = js.native

@js.native
trait JsPushNotificationAuth extends js.Object:
  val schemes: js.Array[String] = js.native
  val credentials: js.UndefOr[String] = js.native

/** JS request/response types */
@js.native
trait JsSendMessageParams extends js.Object:
  val message: JsMessage = js.native
  val configuration: js.UndefOr[JsMessageSendConfiguration] = js.native

@js.native
trait JsMessageSendConfiguration extends js.Object:
  val acceptedOutputModes: js.UndefOr[js.Array[String]] = js.native
  val blocking: js.UndefOr[Boolean] = js.native
  val historyLength: js.UndefOr[Int] = js.native
  val pushNotificationConfig: js.UndefOr[JsPushNotificationConfig] = js.native

/** Builders for creating JS objects */
object JsBuilders:
  def message(role: String, parts: js.Array[JsPart], contextId: Option[String] = None): JsMessage =
    val obj = js.Dynamic.literal(
      kind = "message",
      messageId = MessageId.generate.value,
      role = role,
      parts = parts
    )
    contextId.foreach(c => obj.contextId = c)
    obj.asInstanceOf[JsMessage]

  def textPart(text: String): JsTextPart =
    js.Dynamic.literal(kind = "text", text = text).asInstanceOf[JsTextPart]

  def userMessage(text: String, contextId: Option[String] = None): JsMessage =
    message("user", js.Array(textPart(text)), contextId)

  def agentCard(name: String, description: String, url: String): JsAgentCard =
    js.Dynamic.literal(
      name = name,
      description = description,
      url = url,
      version = "1.0.0",
      protocolVersion = "0.3.0"
    ).asInstanceOf[JsAgentCard]

  def sendMessageParams(
      message: JsMessage,
      configuration: Option[JsMessageSendConfiguration] = None
  ): JsSendMessageParams =
    val obj = js.Dynamic.literal(message = message)
    configuration.foreach(c => obj.configuration = c)
    obj.asInstanceOf[JsSendMessageParams]

  def messageSendConfiguration(
      acceptedOutputModes: Option[List[String]] = None,
      blocking: Option[Boolean] = None,
      historyLength: Option[Int] = None,
      pushNotificationConfig: Option[JsPushNotificationConfig] = None
  ): JsMessageSendConfiguration =
    val obj = js.Dynamic.literal()
    acceptedOutputModes.foreach(m => obj.acceptedOutputModes = js.Array(m*))
    blocking.foreach(b => obj.blocking = b)
    historyLength.foreach(h => obj.historyLength = h)
    pushNotificationConfig.foreach(c => obj.pushNotificationConfig = c)
    obj.asInstanceOf[JsMessageSendConfiguration]

  def taskPushNotificationConfigParams(taskId: String, pushNotificationConfig: JsPushNotificationConfig): js.Dynamic =
    js.Dynamic.literal(
      taskId = taskId,
      pushNotificationConfig = pushNotificationConfig
    )

  def deletePushNotificationConfigParams(id: String, pushNotificationConfigId: String): js.Dynamic =
    // The SDK uses `id` for the task identifier and a separate config id for the callback.
    js.Dynamic.literal(
      id = id,
      pushNotificationConfigId = pushNotificationConfigId
    )

  def taskQueryParams(id: String, historyLength: Option[Int] = None): js.Dynamic =
    val obj = js.Dynamic.literal(id = id)
    historyLength.foreach(h => obj.historyLength = h)
    obj

  def taskIdParams(id: String): js.Dynamic =
    js.Dynamic.literal(id = id)
