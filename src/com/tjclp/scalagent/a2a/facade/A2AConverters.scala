package com.tjclp.scalagent.a2a.facade

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import com.tjclp.scalagent.a2a.*
import zio.json.ast.Json

/** Converters between Scala A2A types and JavaScript facade types */
object A2AConverters:

  private def decodeDynamicJson(value: js.Any): Option[Json] =
    if value == null || js.isUndefined(value) then None
    else
      try Json.decoder.decodeJson(JsJSON.stringify(value)).toOption
      catch
        case _: Throwable => None

  private def decodeDynamicJsonOrNull(value: js.Any): Json =
    decodeDynamicJson(value).getOrElse(Json.Null)

  private def optionalString(dyn: js.Dynamic, field: String): Option[String] =
    dyn.selectDynamic(field).asInstanceOf[js.UndefOr[String]].toOption

  private def optionalDynamic(dyn: js.Dynamic, field: String): Option[js.Dynamic] =
    dyn.selectDynamic(field).asInstanceOf[js.UndefOr[js.Dynamic]].toOption

  private def decodeStringMap(value: js.Any): Map[String, String] =
    if value == null || js.isUndefined(value) then Map.empty
    else value.asInstanceOf[js.Dictionary[String]].toMap

  private def toScalaOAuth2Flow(flowDyn: js.Dynamic): OAuth2Flow =
    OAuth2Flow(
      authorizationUrl = optionalString(flowDyn, "authorizationUrl"),
      tokenUrl = optionalString(flowDyn, "tokenUrl"),
      refreshUrl = optionalString(flowDyn, "refreshUrl"),
      scopes = decodeStringMap(flowDyn.selectDynamic("scopes"))
    )

  private def toScalaOAuth2Flows(flowsDyn: js.Dynamic): OAuth2Flows =
    OAuth2Flows(
      authorizationCode = optionalDynamic(flowsDyn, "authorizationCode").map(toScalaOAuth2Flow),
      clientCredentials = optionalDynamic(flowsDyn, "clientCredentials").map(toScalaOAuth2Flow),
      implicit_ = optionalDynamic(flowsDyn, "implicit").map(toScalaOAuth2Flow),
      password = optionalDynamic(flowsDyn, "password").map(toScalaOAuth2Flow)
    )

  private def toJsStringMap(values: Map[String, String]): js.Dictionary[String] =
    js.Dictionary(values.toSeq*)

  private def toJsOAuth2Flow(flow: OAuth2Flow): js.Dynamic =
    val obj = js.Dynamic.literal(
      scopes = toJsStringMap(flow.scopes)
    )
    flow.authorizationUrl.foreach(url => obj.authorizationUrl = url)
    flow.tokenUrl.foreach(url => obj.tokenUrl = url)
    flow.refreshUrl.foreach(url => obj.refreshUrl = url)
    obj

  private def toJsOAuth2Flows(flows: OAuth2Flows): js.Dynamic =
    val obj = js.Dynamic.literal()
    flows.authorizationCode.foreach(flow => obj.authorizationCode = toJsOAuth2Flow(flow))
    flows.clientCredentials.foreach(flow => obj.clientCredentials = toJsOAuth2Flow(flow))
    flows.implicit_.foreach(flow => obj.updateDynamic("implicit")(toJsOAuth2Flow(flow)))
    flows.password.foreach(flow => obj.password = toJsOAuth2Flow(flow))
    obj

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
      preferredTransport = js.preferredTransport.toOption
        .flatMap(s =>
          s match
            case "JSONRPC"            => Some(A2ATransport.JSONRPC)
            case "GRPC"               => Some(A2ATransport.GRPC)
            case "HTTP+JSON" | "REST" => Some(A2ATransport.HTTP_JSON)
            case _                    => None
        )
        .getOrElse(A2ATransport.JSONRPC),
      additionalInterfaces = js.additionalInterfaces.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      defaultInputModes = js.defaultInputModes.toOption.map(_.toList).getOrElse(List("text/plain")),
      defaultOutputModes = js.defaultOutputModes.toOption.map(_.toList).getOrElse(List("text/plain")),
      skills = js.skills.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      security = js.security.toOption
        .map(_.toList.map(toScalaSecurityRequirement))
        .getOrElse(Nil),
      securitySchemes = js.securitySchemes.toOption
        .map(toScalaSecuritySchemes)
        .getOrElse(Map.empty),
      signatures = js.signatures.toOption.map(_.toList.map(toScala)).getOrElse(Nil),
      supportsAuthenticatedExtendedCard = js.supportsAuthenticatedExtendedCard.getOrElse(false)
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
    obj.preferredTransport = card.preferredTransport.toRaw
    if card.additionalInterfaces.nonEmpty then
      obj.additionalInterfaces = card.additionalInterfaces.map(toJs).toJSArray
    obj.defaultInputModes = card.defaultInputModes.toJSArray
    obj.defaultOutputModes = card.defaultOutputModes.toJSArray
    if card.skills.nonEmpty then obj.skills = card.skills.map(toJs).toJSArray
    if card.security.nonEmpty then
      obj.security = card.security.map(toJsSecurityRequirement).toJSArray
    if card.securitySchemes.nonEmpty then
      obj.securitySchemes = toJsSecuritySchemes(card.securitySchemes)
    if card.signatures.nonEmpty then
      obj.signatures = card.signatures.map(toJs).toJSArray
    if card.supportsAuthenticatedExtendedCard then
      obj.supportsAuthenticatedExtendedCard = true
    obj.asInstanceOf[JsAgentCard]

  def toScala(js: JsAgentProvider): AgentProvider =
    AgentProvider(organization = js.organization, url = js.url)

  def toJs(p: AgentProvider): JsAgentProvider =
    js.Dynamic
      .literal(organization = p.organization, url = p.url)
      .asInstanceOf[JsAgentProvider]

  def toScala(js: JsAgentCapabilities): AgentCapabilities =
    AgentCapabilities(
      streaming = js.streaming.getOrElse(true),
      pushNotifications = js.pushNotifications.getOrElse(false),
      stateTransitionHistory = js.stateTransitionHistory.getOrElse(false),
      extensions = js.extensions.toOption.map(_.toList.map(toScala)).getOrElse(Nil)
    )

  def toJs(c: AgentCapabilities): JsAgentCapabilities =
    val obj = js.Dynamic.literal(
      streaming = c.streaming,
      pushNotifications = c.pushNotifications,
      stateTransitionHistory = c.stateTransitionHistory
    )
    if c.extensions.nonEmpty then obj.extensions = c.extensions.map(toJs).toJSArray
    obj.asInstanceOf[JsAgentCapabilities]

  def toScala(js: JsAgentExtension): AgentExtension =
    AgentExtension(
      uri = js.uri,
      description = js.description.toOption,
      params = js.params.toOption.flatMap(decodeDynamicJson),
      required = js.required.getOrElse(false)
    )

  def toJs(e: AgentExtension): JsAgentExtension =
    val obj = js.Dynamic.literal(uri = e.uri)
    e.description.foreach(d => obj.description = d)
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
      security = js.security.toOption
        .map(_.toList.map(toScalaSecurityRequirement))
        .getOrElse(Nil)
    )

  def toJs(s: AgentSkill): JsAgentSkill =
    val obj = js.Dynamic.literal(id = s.id, name = s.name, description = s.description)
    if s.tags.nonEmpty then obj.tags = s.tags.toJSArray
    if s.examples.nonEmpty then obj.examples = s.examples.toJSArray
    if s.inputModes.nonEmpty then obj.inputModes = s.inputModes.toJSArray
    if s.outputModes.nonEmpty then obj.outputModes = s.outputModes.toJSArray
    if s.security.nonEmpty then obj.security = s.security.map(toJsSecurityRequirement).toJSArray
    obj.asInstanceOf[JsAgentSkill]

  def toScala(js: JsAgentInterface): AgentInterface =
    val transport = js.transport match
      case "JSONRPC"            => A2ATransport.JSONRPC
      case "GRPC"               => A2ATransport.GRPC
      case "HTTP+JSON" | "REST" => A2ATransport.HTTP_JSON
      case _                    => A2ATransport.JSONRPC
    AgentInterface(transport = transport, url = js.url)

  def toJs(i: AgentInterface): JsAgentInterface =
    js.Dynamic
      .literal(transport = i.transport.toRaw, url = i.url)
      .asInstanceOf[JsAgentInterface]

  def toScala(js: JsAgentCardSignature): AgentCardSignature =
    AgentCardSignature(
      `protected` = js.`protected`,
      signature = js.signature,
      header = js.header.toOption.flatMap(decodeDynamicJson)
    )

  def toJs(s: AgentCardSignature): JsAgentCardSignature =
    val obj = js.Dynamic.literal(
      `protected` = s.`protected`,
      signature = s.signature
    )
    s.header.foreach(h => obj.header = JsJSON.parse(h.toString))
    obj.asInstanceOf[JsAgentCardSignature]

  // Security helpers (use js.Dynamic since these are complex Map-based types)
  private def toScalaSecurityRequirement(dyn: js.Dynamic): SecurityRequirement =
    val obj = dyn.asInstanceOf[js.Dictionary[js.Array[String]]]
    val schemes = obj.toMap.map { case (k, v) => k -> v.toList }
    SecurityRequirement(schemes = schemes)

  private def toJsSecurityRequirement(req: SecurityRequirement): js.Dynamic =
    val obj = js.Dynamic.literal()
    req.schemes.foreach { case (scheme, scopes) =>
      obj.updateDynamic(scheme)(scopes.toJSArray)
    }
    obj

  private def toScalaSecuritySchemes(dyn: js.Dynamic): Map[String, SecurityScheme] =
    val dict = dyn.asInstanceOf[js.Dictionary[js.Dynamic]]
    dict.toMap.flatMap { case (name, schemeDyn) =>
      val schemeType = schemeDyn.`type`.asInstanceOf[js.UndefOr[String]].toOption
      val scheme = schemeType match
        case Some("apiKey") =>
          Some(SecurityScheme.ApiKey(
            name = schemeDyn.name.asInstanceOf[String],
            in = schemeDyn.in.asInstanceOf[String]
          ))
        case Some("http") =>
          Some(SecurityScheme.Http(
            scheme = schemeDyn.scheme.asInstanceOf[String],
            bearerFormat = schemeDyn.bearerFormat.asInstanceOf[js.UndefOr[String]].toOption
          ))
        case Some("mutualTLS") =>
          Some(SecurityScheme.MutualTLS)
        case Some("openIdConnect") =>
          Some(SecurityScheme.OpenIdConnect(
            openIdConnectUrl = schemeDyn.openIdConnectUrl.asInstanceOf[String]
          ))
        case Some("oauth2") =>
          val flows = schemeDyn.flows.asInstanceOf[js.UndefOr[js.Dynamic]].toOption.getOrElse(js.Dynamic.literal())
          val metadataUrl = optionalString(schemeDyn, "oauth2MetadataUrl")
          Some(SecurityScheme.OAuth2(toScalaOAuth2Flows(flows), metadataUrl))
        case _ => None
      scheme.map(name -> _)
    }

  private def toJsSecuritySchemes(schemes: Map[String, SecurityScheme]): js.Dynamic =
    val obj = js.Dynamic.literal()
    schemes.foreach { case (name, scheme) =>
      val schemeObj = scheme match
        case SecurityScheme.ApiKey(apiName, in) =>
          js.Dynamic.literal(`type` = "apiKey", name = apiName, in = in)
        case SecurityScheme.Http(httpScheme, bearerFormat) =>
          val o = js.Dynamic.literal(`type` = "http", scheme = httpScheme)
          bearerFormat.foreach(b => o.bearerFormat = b)
          o
        case SecurityScheme.OAuth2(flows, metadataUrl) =>
          val o = js.Dynamic.literal(`type` = "oauth2", flows = toJsOAuth2Flows(flows))
          metadataUrl.foreach(u => o.oauth2MetadataUrl = u)
          o
        case SecurityScheme.OpenIdConnect(url) =>
          js.Dynamic.literal(`type` = "openIdConnect", openIdConnectUrl = url)
        case SecurityScheme.MutualTLS =>
          js.Dynamic.literal(`type` = "mutualTLS")
      obj.updateDynamic(name)(schemeObj)
    }
    obj

  // ==================== Message ====================

  def toScala(js: JsMessage): A2AMessage =
    A2AMessage(
      role = if js.role == "user" then A2ARole.User else A2ARole.Agent,
      parts = js.parts.toList.map(toScalaPart),
      messageId = MessageId(js.messageId),
      contextId = js.contextId.toOption.map(ContextId(_)),
      taskId = js.taskId.toOption.map(TaskId(_)),
      referenceTaskIds = js.referenceTaskIds.toOption.map(_.toList.map(TaskId(_))).getOrElse(Nil),
      metadata = js.metadata.toOption.flatMap(decodeDynamicJson),
      extensions = js.extensions.toOption.map(_.toList).getOrElse(Nil)
    )

  def toJs(msg: A2AMessage): JsMessage =
    val obj = js.Dynamic.literal(
      kind = "message",
      messageId = msg.messageId.value,
      role = if msg.role == A2ARole.User then "user" else "agent",
      parts = msg.parts.map(toJsPart).toJSArray
    )
    msg.contextId.foreach(id => obj.contextId = id.value)
    msg.taskId.foreach(id => obj.taskId = id.value)
    if msg.referenceTaskIds.nonEmpty then
      obj.referenceTaskIds = msg.referenceTaskIds.map(_.value).toJSArray
    if msg.extensions.nonEmpty then
      obj.extensions = msg.extensions.toJSArray
    msg.metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
    obj.asInstanceOf[JsMessage]

  def toScalaPart(js: JsPart): Part =
    js.kind match
      case "text" =>
        val tp = js.asInstanceOf[JsTextPart]
        Part.Text(
          tp.text,
          metadata = js.metadata.toOption.flatMap(decodeDynamicJson)
        )
      case "file" =>
        val fp = js.asInstanceOf[JsFilePart]
        Part.File(
          file = toScalaFileContent(fp.file),
          metadata = js.metadata.toOption.flatMap(decodeDynamicJson)
        )
      case "data" =>
        val dp = js.asInstanceOf[JsDataPart]
        Part.Data(
          data = decodeDynamicJsonOrNull(dp.data),
          metadata = js.metadata.toOption.flatMap(decodeDynamicJson)
        )
      case other =>
        Part.Text(s"[Unknown part type: $other]")

  def toJsPart(part: Part): JsPart =
    part match
      case Part.Text(text, metadata) =>
        val obj = js.Dynamic.literal(kind = "text", text = text)
        metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
        obj.asInstanceOf[JsPart]
      case Part.File(file, metadata) =>
        val obj = js.Dynamic.literal(kind = "file", file = toJsFileContent(file))
        metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
        obj.asInstanceOf[JsPart]
      case Part.Data(data, metadata) =>
        val obj = js.Dynamic.literal(kind = "data", data = JsJSON.parse(data.toString))
        metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
        obj.asInstanceOf[JsPart]

  def toScalaFileContent(js: JsFileContent): FileContent =
    val name = js.name.toOption
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
      metadata = js.metadata.toOption.flatMap(decodeDynamicJson)
    )

  def toJs(task: A2ATask): JsTask =
    val obj = js.Dynamic.literal(
      kind = "task",
      id = task.id.value,
      contextId = task.contextId.value,
      status = toJs(task.status)
    )
    if task.artifacts.nonEmpty then obj.artifacts = task.artifacts.map(toJs).toJSArray
    if task.history.nonEmpty then obj.history = task.history.map(toJs).toJSArray
    task.metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
    obj.asInstanceOf[JsTask]

  def toScala(js: JsTaskStatus): TaskStatus =
    TaskStatus(
      state = toScalaTaskState(js.state),
      message = js.message.toOption.map(toScala),
      timestamp = js.timestamp.toOption
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
      metadata = js.metadata.toOption.flatMap(decodeDynamicJson)
    )

  def toJs(a: Artifact): JsArtifact =
    val obj = js.Dynamic.literal(
      artifactId = a.artifactId,
      parts = a.parts.map(toJsPart).toJSArray
    )
    a.name.foreach(n => obj.name = n)
    a.description.foreach(d => obj.description = d)
    if a.extensions.nonEmpty then obj.extensions = a.extensions.toJSArray
    a.metadata.foreach(m => obj.metadata = JsJSON.parse(m.toString))
    obj.asInstanceOf[JsArtifact]

  // ==================== Push Notification ====================

  def toScala(js: JsPushNotificationConfig): PushNotificationConfig =
    PushNotificationConfig(
      url = js.url,
      id = js.id.toOption,
      token = js.token.toOption,
      authentication = js.authentication.toOption.map(toScala)
    )

  def toJs(c: PushNotificationConfig): JsPushNotificationConfig =
    val obj = js.Dynamic.literal(url = c.url)
    c.id.foreach(i => obj.id = i)
    c.token.foreach(t => obj.token = t)
    c.authentication.foreach(a => obj.authentication = toJs(a))
    obj.asInstanceOf[JsPushNotificationConfig]

  def toScalaPushNotificationConfigResult(result: js.Any): PushNotificationConfig =
    val dyn = result.asInstanceOf[js.Dynamic]
    val config = dyn.selectDynamic("pushNotificationConfig").asInstanceOf[js.UndefOr[js.Any]].toOption.getOrElse(result)
    toScala(config.asInstanceOf[JsPushNotificationConfig])

  def toScalaPushNotificationConfigResults(results: js.Array[js.Any]): List[PushNotificationConfig] =
    results.toList.map(toScalaPushNotificationConfigResult)

  def toScala(js: JsPushNotificationAuth): PushNotificationAuth =
    PushNotificationAuth(
      schemes = js.schemes.toList,
      credentials = js.credentials.toOption
    )

  def toJs(a: PushNotificationAuth): JsPushNotificationAuth =
    val obj = js.Dynamic.literal(schemes = a.schemes.toJSArray)
    a.credentials.foreach(c => obj.credentials = c)
    obj.asInstanceOf[JsPushNotificationAuth]
