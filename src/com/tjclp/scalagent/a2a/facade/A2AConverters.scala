package com.tjclp.scalagent.a2a.facade

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import com.tjclp.scalagent.a2a.*
import zio.json.ast.Json

/** Converters between Scala A2A types and JavaScript facade types */
object A2AConverters:

  // ==================== AgentCard ====================

  def toScala(js: JsAgentCard): AgentCard =
    AgentCard(
      name = js.name,
      description = js.description,
      url = js.url,
      version = js.version.getOrElse("1.0.0"),
      protocolVersion = js.protocolVersion.getOrElse(A2AProtocol.Version),
      provider = js.provider.toOption.map(toScala),
      documentationUrl = js.documentationUrl.toOption,
      iconUrl = js.iconUrl.toOption,
      capabilities = js.capabilities.toOption.map(toScala).getOrElse(AgentCapabilities.default),
      defaultInputModes = js.defaultInputModes.toOption.map(_.toList).getOrElse(List("text/plain")),
      defaultOutputModes = js.defaultOutputModes.toOption.map(_.toList).getOrElse(List("text/plain")),
      skills = js.skills.toOption.map(_.toList.map(toScala)).getOrElse(Nil)
    )

  def toJs(card: AgentCard): JsAgentCard =
    val obj = js.Dynamic.literal(
      name = card.name,
      description = card.description,
      url = card.url,
      version = card.version,
      protocolVersion = card.protocolVersion
    )
    card.provider.foreach(p => obj.provider = toJs(p))
    card.documentationUrl.foreach(u => obj.documentationUrl = u)
    card.iconUrl.foreach(u => obj.iconUrl = u)
    obj.capabilities = toJs(card.capabilities)
    obj.defaultInputModes = card.defaultInputModes.toJSArray
    obj.defaultOutputModes = card.defaultOutputModes.toJSArray
    if card.skills.nonEmpty then obj.skills = card.skills.map(toJs).toJSArray
    obj.asInstanceOf[JsAgentCard]

  def toScala(js: JsAgentProvider): AgentProvider =
    AgentProvider(organization = js.organization, url = js.url.toOption)

  def toJs(p: AgentProvider): JsAgentProvider =
    val obj = js.Dynamic.literal(organization = p.organization)
    p.url.foreach(u => obj.url = u)
    obj.asInstanceOf[JsAgentProvider]

  def toScala(js: JsAgentCapabilities): AgentCapabilities =
    AgentCapabilities(
      streaming = js.streaming.getOrElse(true),
      pushNotifications = js.pushNotifications.getOrElse(false),
      stateTransitionHistory = js.stateTransitionHistory.getOrElse(false)
    )

  def toJs(c: AgentCapabilities): JsAgentCapabilities =
    js.Dynamic
      .literal(
        streaming = c.streaming,
        pushNotifications = c.pushNotifications,
        stateTransitionHistory = c.stateTransitionHistory
      )
      .asInstanceOf[JsAgentCapabilities]

  def toScala(js: JsAgentSkill): AgentSkill =
    AgentSkill(
      id = js.id,
      name = js.name,
      description = js.description,
      tags = js.tags.toOption.map(_.toList).getOrElse(Nil),
      examples = js.examples.toOption.map(_.toList).getOrElse(Nil)
    )

  def toJs(s: AgentSkill): JsAgentSkill =
    val obj = js.Dynamic.literal(id = s.id, name = s.name, description = s.description)
    if s.tags.nonEmpty then obj.tags = s.tags.toJSArray
    if s.examples.nonEmpty then obj.examples = s.examples.toJSArray
    obj.asInstanceOf[JsAgentSkill]

  // ==================== Message ====================

  def toScala(js: JsMessage): A2AMessage =
    A2AMessage(
      role = if js.role == "user" then A2ARole.User else A2ARole.Agent,
      parts = js.parts.toList.map(toScalaPart),
      messageId = js.messageId.toOption.map(MessageId(_)),
      contextId = js.contextId.toOption.map(ContextId(_)),
      taskId = js.taskId.toOption.map(TaskId(_)),
      metadata = js.metadata.toOption.flatMap(d => Json.decoder.decodeJson(JsJSON.stringify(d)).toOption)
    )

  def toJs(msg: A2AMessage): JsMessage =
    val obj = js.Dynamic.literal(
      kind = "message",
      role = if msg.role == A2ARole.User then "user" else "agent",
      parts = msg.parts.map(toJsPart).toJSArray
    )
    msg.messageId.foreach(id => obj.messageId = id.value)
    msg.contextId.foreach(id => obj.contextId = id.value)
    msg.taskId.foreach(id => obj.taskId = id.value)
    obj.asInstanceOf[JsMessage]

  def toScalaPart(js: JsPart): Part =
    js.kind match
      case "text" =>
        Part.Text(js.asInstanceOf[JsTextPart].text)
      case "file" =>
        val fp = js.asInstanceOf[JsFilePart]
        Part.File(
          file = toScalaFileContent(fp.file),
          name = fp.name.toOption,
          mimeType = fp.mimeType.toOption
        )
      case "data" =>
        val dp = js.asInstanceOf[JsDataPart]
        Part.Data(
          data = Json.decoder.decodeJson(JsJSON.stringify(dp.data)).toOption.get,
          name = dp.name.toOption,
          mimeType = dp.mimeType.toOption
        )
      case other =>
        Part.Text(s"[Unknown part type: $other]")

  def toJsPart(part: Part): JsPart =
    part match
      case Part.Text(text) =>
        js.Dynamic.literal(kind = "text", text = text).asInstanceOf[JsPart]
      case Part.File(file, name, mimeType) =>
        val obj = js.Dynamic.literal(kind = "file", file = toJsFileContent(file))
        name.foreach(n => obj.name = n)
        mimeType.foreach(m => obj.mimeType = m)
        obj.asInstanceOf[JsPart]
      case Part.Data(data, name, mimeType) =>
        val obj = js.Dynamic.literal(kind = "data", data = JsJSON.parse(data.toString))
        name.foreach(n => obj.name = n)
        mimeType.foreach(m => obj.mimeType = m)
        obj.asInstanceOf[JsPart]

  def toScalaFileContent(js: JsFileContent): FileContent =
    js.bytes.toOption match
      case Some(bytes) => FileContent.Bytes(bytes)
      case None        => FileContent.Uri(js.uri.getOrElse(""))

  def toJsFileContent(fc: FileContent): JsFileContent =
    fc match
      case FileContent.Bytes(bytes) => js.Dynamic.literal(bytes = bytes).asInstanceOf[JsFileContent]
      case FileContent.Uri(uri)     => js.Dynamic.literal(uri = uri).asInstanceOf[JsFileContent]

  // ==================== Task ====================

  def toScala(js: JsTask): A2ATask =
    A2ATask(
      id = TaskId(js.id),
      contextId = js.contextId.toOption.map(ContextId(_)),
      status = toScala(js.status),
      artifacts = js.artifacts.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      history = js.history.toOption.map(_.toList.map(toScala)).getOrElse(Nil)
    )

  def toJs(task: A2ATask): JsTask =
    val obj = js.Dynamic.literal(
      kind = "task",
      id = task.id.value,
      status = toJs(task.status)
    )
    task.contextId.foreach(c => obj.contextId = c.value)
    if task.artifacts.nonEmpty then obj.artifacts = task.artifacts.map(toJs).toJSArray
    if task.history.nonEmpty then obj.history = task.history.map(toJs).toJSArray
    obj.asInstanceOf[JsTask]

  def toScala(js: JsTaskStatus): TaskStatus =
    TaskStatus(
      state = toScalaTaskState(js.state),
      message = js.message.toOption.map(toScala),
      createdAt = js.createdAt.toOption,
      updatedAt = js.updatedAt.toOption
    )

  def toJs(status: TaskStatus): JsTaskStatus =
    val obj = js.Dynamic.literal(state = toJsTaskState(status.state))
    status.message.foreach(m => obj.message = toJs(m))
    status.createdAt.foreach(t => obj.createdAt = t)
    status.updatedAt.foreach(t => obj.updatedAt = t)
    obj.asInstanceOf[JsTaskStatus]

  def toScalaTaskState(s: String): TaskState =
    s match
      case "submitted"      => TaskState.Submitted
      case "working"        => TaskState.Working
      case "input-required" => TaskState.InputRequired
      case "completed"      => TaskState.Completed
      case "canceled"       => TaskState.Canceled
      case "failed"         => TaskState.Failed
      case "rejected"       => TaskState.Rejected
      case "auth-required"  => TaskState.AuthRequired
      case _                => TaskState.Unknown

  def toJsTaskState(s: TaskState): String =
    s match
      case TaskState.Submitted     => "submitted"
      case TaskState.Working       => "working"
      case TaskState.InputRequired => "input-required"
      case TaskState.Completed     => "completed"
      case TaskState.Canceled      => "canceled"
      case TaskState.Failed        => "failed"
      case TaskState.Rejected      => "rejected"
      case TaskState.AuthRequired  => "auth-required"
      case TaskState.Unknown       => "unknown"

  // ==================== Artifact ====================

  def toScala(js: JsArtifact): Artifact =
    Artifact(
      name = js.name,
      parts = js.parts.toList.map(toScalaPart),
      index = js.index.getOrElse(0),
      append = js.append.getOrElse(false),
      lastChunk = js.lastChunk.getOrElse(true)
    )

  def toJs(a: Artifact): JsArtifact =
    js.Dynamic
      .literal(
        name = a.name,
        parts = a.parts.map(toJsPart).toJSArray,
        index = a.index,
        append = a.append,
        lastChunk = a.lastChunk
      )
      .asInstanceOf[JsArtifact]

  // ==================== Push Notification ====================

  def toScala(js: JsPushNotificationConfig): PushNotificationConfig =
    PushNotificationConfig(url = js.url, token = js.token.toOption)

  def toJs(c: PushNotificationConfig): JsPushNotificationConfig =
    val obj = js.Dynamic.literal(url = c.url)
    c.token.foreach(t => obj.token = t)
    obj.asInstanceOf[JsPushNotificationConfig]
