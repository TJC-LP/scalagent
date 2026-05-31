package com.tjclp.scalagent.a2a.facade

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import com.tjclp.scalagent.a2a.*
import zio.json.*
import zio.json.ast.Json

/** Converters between Scala A2A types and JavaScript facade types */
object A2AConverters:

  private def decodeDynamicJson(value: js.Any): Option[Json] =
    if value == null || js.isUndefined(value) then None
    else
      try Json.decoder.decodeJson(JsJSON.stringify(value)).toOption
      catch case _: Throwable => None

  private def decodeDynamicAs[A: JsonDecoder](value: js.Any): Option[A] =
    decodeDynamicJson(value).flatMap(_.as[A].toOption)

  private def decodeDynamicJsonOrNull(value: js.Any): Json =
    decodeDynamicJson(value).getOrElse(Json.Null)

  private def optionalString(dyn: js.Dynamic, field: String): Option[String] =
    dyn.selectDynamic(field).asInstanceOf[js.UndefOr[String]].toOption

  // ==================== AgentCard ====================

  private def transportFromRaw(raw: String): A2ATransport =
    A2ATransport.fromRaw(raw).getOrElse(A2ATransport.JSONRPC)

  def toScala(js: JsAgentCard): AgentCard =
    val decodedSupportedInterfaces = js.supportedInterfaces.toOption.map(_.toList.map(toScala)).filter(_.nonEmpty)
    val primaryUrl                 = js.url.toOption
      .orElse(decodedSupportedInterfaces.flatMap(_.headOption.map(_.url)))
      .getOrElse("")
    val preferredTransport = js.preferredTransport.toOption
      .map(transportFromRaw)
      .getOrElse(A2ATransport.JSONRPC)
    val primaryInterface = AgentInterface(
      url = primaryUrl,
      protocolBinding = preferredTransport,
      protocolVersion = js.protocolVersion.getOrElse(A2AProtocol.Version),
    )
    val supportedInterfaces = decodedSupportedInterfaces.getOrElse {
      primaryInterface :: js.additionalInterfaces.toOption.map(_.toList.map(toScala)).getOrElse(Nil)
    }
    val decodedCapabilities = js.capabilities.toOption
      .map(toScala)
      .getOrElse(AgentCapabilities(streaming = false))
    val capabilities = decodedCapabilities
      .copy(
        extendedAgentCard =
          js.supportsAuthenticatedExtendedCard.toOption.getOrElse(decodedCapabilities.extendedAgentCard)
      )
    AgentCard(
      name = js.name,
      description = js.description,
      supportedInterfaces = supportedInterfaces,
      version = js.version.getOrElse("1.0.0"),
      provider = js.provider.toOption.map(toScala),
      documentationUrl = js.documentationUrl.toOption,
      iconUrl = js.iconUrl.toOption,
      capabilities = capabilities,
      defaultInputModes = js.defaultInputModes.toOption.map(_.toList).getOrElse(List("text/plain")),
      defaultOutputModes = js.defaultOutputModes.toOption.map(_.toList).getOrElse(List("text/plain")),
      skills = AgentCard.requiredSkills(
        js.name,
        js.description,
        js.skills.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      ),
      securityRequirements = js.security.toOption
        .map(_.toList.map(toScalaSecurityRequirement))
        .getOrElse(Nil),
      securitySchemes = js.securitySchemes.toOption
        .map(toScalaSecuritySchemes)
        .getOrElse(Map.empty),
      signatures = js.signatures.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
    )
  end toScala

  def toJs(card: AgentCard): JsAgentCard =
    val obj = js.Dynamic.literal(
      name = card.name,
      description = card.description,
      url = card.url,
      version = card.version,
      protocolVersion = card.supportedInterfaces.headOption.map(_.protocolVersion).getOrElse(A2AProtocol.Version),
    )
    card.provider.foreach(p => obj.provider = toJs(p))
    card.documentationUrl.foreach(u => obj.documentationUrl = u)
    card.iconUrl.foreach(u => obj.iconUrl = u)
    obj.capabilities = toJs(card.capabilities)
    obj.preferredTransport =
      card.supportedInterfaces.headOption.map(_.protocolBinding.toRaw).getOrElse(A2ATransport.JSONRPC.toRaw)
    obj.supportedInterfaces = card.supportedInterfaces.map(toJs).toJSArray
    if card.supportedInterfaces.drop(1).nonEmpty then
      obj.additionalInterfaces = card.supportedInterfaces.drop(1).map(toJs).toJSArray
    obj.defaultInputModes = card.defaultInputModes.toJSArray
    obj.defaultOutputModes = card.defaultOutputModes.toJSArray
    obj.skills = AgentCard.requiredSkills(card.name, card.description, card.skills).map(toJs).toJSArray
    if card.securityRequirements.nonEmpty then
      obj.security = card.securityRequirements.map(toJsSecurityRequirement).toJSArray
    if card.securitySchemes.nonEmpty then obj.securitySchemes = toJsSecuritySchemes(card.securitySchemes)
    if card.signatures.nonEmpty then obj.signatures = card.signatures.map(toJs).toJSArray
    if card.capabilities.extendedAgentCard then obj.supportsAuthenticatedExtendedCard = true
    obj.asInstanceOf[JsAgentCard]
  end toJs

  def toScala(js: JsAgentProvider): AgentProvider =
    AgentProvider(organization = js.organization, url = js.url)

  def toJs(p: AgentProvider): JsAgentProvider =
    js.Dynamic
      .literal(organization = p.organization, url = p.url)
      .asInstanceOf[JsAgentProvider]

  def toScala(js: JsAgentCapabilities): AgentCapabilities =
    AgentCapabilities(
      streaming = js.streaming.getOrElse(false),
      pushNotifications = js.pushNotifications.getOrElse(false),
      extensions = js.extensions.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      extendedAgentCard = js.extendedAgentCard.getOrElse(false),
    )

  def toJs(c: AgentCapabilities): JsAgentCapabilities =
    val obj = js.Dynamic.literal(
      streaming = c.streaming,
      pushNotifications = c.pushNotifications,
      extendedAgentCard = c.extendedAgentCard,
    )
    if c.extensions.nonEmpty then obj.extensions = c.extensions.map(toJs).toJSArray
    obj.asInstanceOf[JsAgentCapabilities]

  def toScala(js: JsAgentExtension): AgentExtension =
    AgentExtension(
      uri = js.uri,
      description = js.description.toOption.getOrElse(""),
      params = js.params.toOption.flatMap(decodeDynamicJson),
      required = js.required.getOrElse(false),
    )

  def toJs(e: AgentExtension): JsAgentExtension =
    val obj = js.Dynamic.literal(uri = e.uri)
    if e.description.nonEmpty then obj.description = e.description
    e.params.foreach(p => obj.params = JsJSON.parse(p.toString))
    if e.required then obj.required = true
    obj.asInstanceOf[JsAgentExtension]

  def toScala(js: JsAgentSkill): AgentSkill =
    AgentSkill(
      id = js.id,
      name = js.name,
      description = js.description,
      tags = js.tags.toOption.map(_.toList).getOrElse(Nil),
      examples = js.examples.toOption.map(_.toList).getOrElse(Nil),
      inputModes = js.inputModes.toOption.map(_.toList).getOrElse(Nil),
      outputModes = js.outputModes.toOption.map(_.toList).getOrElse(Nil),
      securityRequirements = js.security.toOption
        .map(_.toList.map(toScalaSecurityRequirement))
        .getOrElse(Nil),
    )

  def toJs(s: AgentSkill): JsAgentSkill =
    val skill = AgentSkill.withRequiredTags(s)
    val obj   = js.Dynamic.literal(id = skill.id, name = skill.name, description = skill.description)
    obj.tags = skill.tags.toJSArray
    if skill.examples.nonEmpty then obj.examples = skill.examples.toJSArray
    if skill.inputModes.nonEmpty then obj.inputModes = skill.inputModes.toJSArray
    if skill.outputModes.nonEmpty then obj.outputModes = skill.outputModes.toJSArray
    if skill.securityRequirements.nonEmpty then
      obj.security = skill.securityRequirements.map(toJsSecurityRequirement).toJSArray
    obj.asInstanceOf[JsAgentSkill]

  def toScala(js: JsAgentInterface): AgentInterface =
    val transport =
      js.protocolBinding.toOption.orElse(js.transport.toOption).map(transportFromRaw).getOrElse(A2ATransport.JSONRPC)
    AgentInterface(
      url = js.url,
      protocolBinding = transport,
      tenant = js.tenant.toOption.filter(_.nonEmpty),
      protocolVersion = js.protocolVersion.getOrElse(A2AProtocol.Version),
    )

  def toJs(i: AgentInterface): JsAgentInterface =
    val obj = js.Dynamic.literal(
      transport = i.transport.toRaw,
      protocolBinding = i.protocolBinding.toRaw,
      protocolVersion = i.protocolVersion,
      url = i.url,
    )
    i.tenant.foreach(value => obj.tenant = value)
    obj.asInstanceOf[JsAgentInterface]

  def toScala(js: JsAgentCardSignature): AgentCardSignature =
    AgentCardSignature(
      `protected` = js.`protected`,
      signature = js.signature,
      header = js.header.toOption.flatMap(decodeDynamicJson),
    )

  def toJs(s: AgentCardSignature): JsAgentCardSignature =
    val obj = js.Dynamic.literal(
      `protected` = s.`protected`,
      signature = s.signature,
    )
    s.header.foreach(h => obj.header = JsJSON.parse(h.toString))
    obj.asInstanceOf[JsAgentCardSignature]

  // Security helpers (use js.Dynamic since these are complex Map-based types)
  private def toScalaSecurityRequirement(dyn: js.Dynamic): SecurityRequirement =
    decodeDynamicAs[SecurityRequirement](dyn).getOrElse(SecurityRequirement())

  private def toJsSecurityRequirement(req: SecurityRequirement): js.Dynamic =
    val obj = js.Dynamic.literal()
    req.schemes.foreach {
      case (scheme, scopes) =>
        obj.updateDynamic(scheme)(scopes.toJSArray)
    }
    obj

  private def toScalaSecuritySchemes(dyn: js.Dynamic): Map[String, SecurityScheme] =
    decodeDynamicAs[Map[String, SecurityScheme]](dyn).getOrElse(Map.empty)
  end toScalaSecuritySchemes

  private def toJsSecuritySchemes(schemes: Map[String, SecurityScheme]): js.Dynamic =
    val obj = js.Dynamic.literal()
    schemes.foreach {
      case (name, scheme) =>
        obj.updateDynamic(name)(JsJSON.parse(SecurityScheme.toOpenApiJson(scheme).toString))
    }
    obj
  end toJsSecuritySchemes

  // ==================== Message ====================

  def toScala(js: JsMessage): A2AMessage =
    A2AMessage(
      role = toScalaRole(js.role),
      parts = js.parts.toList.map(toScalaPart),
      messageId = MessageId(js.messageId),
      contextId = js.contextId.toOption.map(ContextId(_)),
      taskId = js.taskId.toOption.map(TaskId(_)),
      referenceTaskIds = js.referenceTaskIds.toOption.map(_.toList.map(TaskId(_))).getOrElse(Nil),
      metadata = js.metadata.toOption.flatMap(decodeDynamicJson),
      extensions = js.extensions.toOption.map(_.toList).getOrElse(Nil),
    )

  def toJs(msg: A2AMessage): JsMessage =
    val obj = js.Dynamic.literal(
      kind = "message",
      messageId = msg.messageId.value,
      role = toJsRole(msg.role),
      parts = msg.parts.map(toJsPart).toJSArray,
    )
    msg.contextId.foreach(id => obj.contextId = id.value)
    msg.taskId.foreach(id => obj.taskId = id.value)
    if msg.referenceTaskIds.nonEmpty then obj.referenceTaskIds = msg.referenceTaskIds.map(_.value).toJSArray
    if msg.extensions.nonEmpty then obj.extensions = msg.extensions.toJSArray
    msg.metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
    obj.asInstanceOf[JsMessage]

  private def toScalaRole(role: String): A2ARole =
    role match
      case "user" | "ROLE_USER"               => A2ARole.User
      case "agent" | "ROLE_AGENT"             => A2ARole.Agent
      case "unspecified" | "ROLE_UNSPECIFIED" => A2ARole.Unspecified
      case _                                  => A2ARole.Unspecified

  private def toJsRole(role: A2ARole): String =
    role match
      case A2ARole.User        => "user"
      case A2ARole.Agent       => "agent"
      case A2ARole.Unspecified => "unspecified"

  def toScalaPart(js: JsPart): Part =
    val partDyn  = js.asInstanceOf[scala.scalajs.js.Dynamic]
    val filename = optionalString(partDyn, "filename").orElse(optionalString(partDyn, "name"))
    js.kind match
      case "text" =>
        val tp = js.asInstanceOf[JsTextPart]
        Part.Text(
          tp.text,
          metadata = js.metadata.toOption.flatMap(decodeDynamicJson),
          filename = filename,
          mediaType = optionalString(partDyn, "mediaType").orElse(optionalString(partDyn, "mimeType")),
        )
      case "file" =>
        val fp = js.asInstanceOf[JsFilePart]
        Part.File(
          file = toScalaFileContent(fp.file),
          metadata = js.metadata.toOption.flatMap(decodeDynamicJson),
        )
      case "data" =>
        val dp = js.asInstanceOf[JsDataPart]
        Part.Data(
          data = decodeDynamicJsonOrNull(dp.data),
          metadata = js.metadata.toOption.flatMap(decodeDynamicJson),
          filename = filename,
          mediaType = optionalString(partDyn, "mediaType").orElse(optionalString(partDyn, "mimeType")),
        )
      case other =>
        Part.Text(s"[Unknown part type: $other]")
    end match
  end toScalaPart

  def toJsPart(part: Part): JsPart =
    part match
      case Part.Text(text, metadata, filename, mediaType) =>
        val obj = js.Dynamic.literal(kind = "text", text = text)
        filename.foreach(value => obj.filename = value)
        mediaType.foreach(value => obj.mediaType = value)
        metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
        obj.asInstanceOf[JsPart]
      case Part.File(file, metadata) =>
        val obj = js.Dynamic.literal(kind = "file", file = toJsFileContent(file))
        metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
        obj.asInstanceOf[JsPart]
      case Part.Data(data, metadata, filename, mediaType) =>
        val obj = js.Dynamic.literal(kind = "data", data = JsJSON.parse(data.toString))
        filename.foreach(value => obj.filename = value)
        mediaType.foreach(value => obj.mediaType = value)
        metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
        obj.asInstanceOf[JsPart]

  def toScalaFileContent(js: JsFileContent): FileContent =
    val name     = js.name.toOption
    val mimeType = js.mimeType.toOption
    js.bytes.toOption match
      case Some(bytes) => FileContent.Bytes(bytes, name, mimeType)
      case None        => FileContent.Uri(js.uri.getOrElse(""), name, mimeType)

  def toJsFileContent(fc: FileContent): JsFileContent =
    fc match
      case FileContent.Bytes(bytes, name, mimeType) =>
        val obj = js.Dynamic.literal(bytes = bytes)
        name.foreach(n => obj.name = n)
        mimeType.foreach(m => obj.mimeType = m)
        obj.asInstanceOf[JsFileContent]
      case FileContent.Uri(uri, name, mimeType) =>
        val obj = js.Dynamic.literal(uri = uri)
        name.foreach(n => obj.name = n)
        mimeType.foreach(m => obj.mimeType = m)
        obj.asInstanceOf[JsFileContent]

  // ==================== Task ====================

  def toScala(js: JsTask): A2ATask =
    A2ATask(
      id = TaskId(js.id),
      contextId = ContextId(js.contextId),
      status = toScala(js.status),
      artifacts = js.artifacts.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      history = js.history.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      metadata = js.metadata.toOption.flatMap(decodeDynamicJson),
    )

  def toJs(task: A2ATask): JsTask =
    val obj = js.Dynamic.literal(
      kind = "task",
      id = task.id.value,
      contextId = task.contextId.value,
      status = toJs(task.status),
    )
    if task.artifacts.nonEmpty then obj.artifacts = task.artifacts.map(toJs).toJSArray
    if task.history.nonEmpty then obj.history = task.history.map(toJs).toJSArray
    task.metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
    obj.asInstanceOf[JsTask]

  def toScala(js: JsTaskStatus): TaskStatus =
    TaskStatus(
      state = toScalaTaskState(js.state),
      message = js.message.toOption.map(toScala),
      timestamp = js.timestamp.toOption,
    )

  def toJs(status: TaskStatus): JsTaskStatus =
    val obj = js.Dynamic.literal(state = toJsTaskState(status.state))
    status.message.foreach(m => obj.message = toJs(m))
    status.timestamp.foreach(t => obj.timestamp = t)
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
      artifactId = js.artifactId,
      parts = js.parts.toList.map(toScalaPart),
      name = js.name.toOption,
      description = js.description.toOption,
      extensions = js.extensions.toOption.map(_.toList).getOrElse(Nil),
      metadata = js.metadata.toOption.flatMap(decodeDynamicJson),
    )

  def toJs(a: Artifact): JsArtifact =
    val obj = js.Dynamic.literal(
      artifactId = a.artifactId,
      parts = a.parts.map(toJsPart).toJSArray,
    )
    a.name.foreach(n => obj.name = n)
    a.description.foreach(d => obj.description = d)
    if a.extensions.nonEmpty then obj.extensions = a.extensions.toJSArray
    a.metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
    obj.asInstanceOf[JsArtifact]

  // ==================== Push Notification ====================

  def toScala(js: JsPushNotificationConfig): PushNotificationConfig =
    TaskPushNotificationConfig(
      url = js.url,
      tenant = js.tenant.toOption.filter(_.nonEmpty),
      id = js.id.toOption,
      taskId = js.taskId.toOption.filter(_.nonEmpty).map(TaskId(_)),
      token = js.token.toOption,
      authentication = js.authentication.toOption.map(toScala(_).toAuthenticationInfo),
    )

  def toJs(c: PushNotificationConfig): JsPushNotificationConfig =
    val obj = js.Dynamic.literal(url = c.url)
    c.tenant.foreach(value => obj.tenant = value)
    c.id.foreach(i => obj.id = i)
    c.taskId.foreach(id => obj.taskId = id.value)
    c.token.foreach(t => obj.token = t)
    c.authentication.foreach(a => obj.authentication = toJs(a))
    obj.asInstanceOf[JsPushNotificationConfig]

  def toScalaPushNotificationConfigResult(result: js.Any): PushNotificationConfig =
    val dyn           = result.asInstanceOf[js.Dynamic]
    val wrapperTaskId = optionalString(dyn, "taskId")
    val config = dyn.selectDynamic("pushNotificationConfig").asInstanceOf[js.UndefOr[js.Any]].toOption.getOrElse(result)
    val decoded = toScala(config.asInstanceOf[JsPushNotificationConfig])
    if decoded.taskId.isDefined then decoded
    else decoded.copy(taskId = wrapperTaskId.filter(_.nonEmpty).map(TaskId(_)))

  def toScalaPushNotificationConfigResults(results: js.Array[js.Any]): List[PushNotificationConfig] =
    results.toList.map(toScalaPushNotificationConfigResult)

  def toScala(js: JsPushNotificationAuth): PushNotificationAuth =
    PushNotificationAuth(
      schemes = js.schemes.toList,
      credentials = js.credentials.toOption,
    )

  def toJs(a: AuthenticationInfo): JsPushNotificationAuth =
    js.Dynamic
      .literal(schemes = js.Array(a.scheme), credentials = a.credentials)
      .asInstanceOf[JsPushNotificationAuth]

  def toJs(a: PushNotificationAuth): JsPushNotificationAuth =
    val obj = js.Dynamic.literal(schemes = a.schemes.toJSArray)
    a.credentials.foreach(c => obj.credentials = c)
    obj.asInstanceOf[JsPushNotificationAuth]
end A2AConverters
