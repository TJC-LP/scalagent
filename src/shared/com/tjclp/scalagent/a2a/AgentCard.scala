package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/**
 * A2A v1 Agent Card.
 *
 * The v1 card advertises concrete protocol bindings through
 * `supportedInterfaces`. The first interface is the preferred one.
 */
final case class AgentCard(
  name: String,
  description: String,
  supportedInterfaces: List[AgentInterface],
  version: String = "1.0.0",
  provider: Option[AgentProvider] = None,
  documentationUrl: Option[String] = None,
  capabilities: AgentCapabilities = AgentCapabilities.default,
  securitySchemes: Map[String, SecurityScheme] = Map.empty,
  securityRequirements: List[SecurityRequirement] = Nil,
  defaultInputModes: List[String] = List("text/plain"),
  defaultOutputModes: List[String] = List("text/plain"),
  skills: List[AgentSkill] = Nil,
  signatures: List[AgentCardSignature] = Nil,
  iconUrl: Option[String] = None):

  /** Preferred endpoint URL, retained as source-compatibility sugar. */
  def url: String =
    supportedInterfaces.headOption.map(_.url).getOrElse("")

  /** Whether the card advertises an authenticated extended card. */
  def supportsAuthenticatedExtendedCard: Boolean =
    capabilities.extendedAgentCard
end AgentCard

object AgentCard:
  given JsonEncoder[AgentCard] = JsonEncoder[Json].contramap { card =>
    var obj = Json.Obj(
      "name"                -> Json.Str(card.name),
      "description"         -> Json.Str(card.description),
      "supportedInterfaces" -> Json.Arr(card.supportedInterfaces.map(_.toJsonAST.toOption.get)*),
      "version"             -> Json.Str(card.version),
      "capabilities"        -> card.capabilities.toJsonAST.toOption.get,
      "defaultInputModes"   -> Json.Arr(card.defaultInputModes.map(Json.Str(_))*),
      "defaultOutputModes"  -> Json.Arr(card.defaultOutputModes.map(Json.Str(_))*),
      "skills" -> Json.Arr(requiredSkills(card.name, card.description, card.skills).map(_.toJsonAST.toOption.get)*),
    )
    card.provider.foreach(value => obj = obj.add("provider", value.toJsonAST.toOption.get))
    card.documentationUrl.foreach(value => obj = obj.add("documentationUrl", Json.Str(value)))
    if card.securitySchemes.nonEmpty then
      obj = obj.add(
        "securitySchemes",
        Json.Obj(card.securitySchemes.toSeq.map { case (name, scheme) => name -> scheme.toJsonAST.toOption.get }*),
      )
    if card.securityRequirements.nonEmpty then
      obj = obj.add("securityRequirements", Json.Arr(card.securityRequirements.map(_.toJsonAST.toOption.get)*))
    if card.signatures.nonEmpty then
      obj = obj.add("signatures", Json.Arr(card.signatures.map(_.toJsonAST.toOption.get)*))
    card.iconUrl.foreach(value => obj = obj.add("iconUrl", Json.Str(value)))
    obj
  }

  private def field(fields: Map[String, Json], names: String*): Option[Json] =
    names.iterator.flatMap(fields.get).nextOption()

  private def requiredString(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, String] =
    field(fields, (name +: aliases)*)
      .flatMap(_.asString)
      .filter(_.nonEmpty)
      .toRight(s"Missing $name")

  private def decodeList[A: JsonDecoder](value: Json, label: String): Either[String, List[A]] =
    value.asArray
      .toRight(s"$label must be an array")
      .flatMap(values =>
        values.toList.map(_.as[A]).foldRight[Either[String, List[A]]](Right(Nil)) {
          case (Right(value), Right(values)) => Right(value :: values)
          case (Left(error), _)              => Left(error)
          case (_, Left(error))              => Left(error)
        }
      )

  private def requiredList[A: JsonDecoder](
    fields: Map[String, Json],
    label: String,
    aliases: String*
  ): Either[String, List[A]] =
    field(fields, (label +: aliases)*).toRight(s"Missing $label").flatMap(decodeList[A](_, label))

  private def requiredNonEmptyList[A: JsonDecoder](
    fields: Map[String, Json],
    label: String,
    aliases: String*
  ): Either[String, List[A]] =
    requiredList[A](fields, label, aliases*).flatMap {
      case Nil    => Left(s"$label must contain at least one item")
      case values => Right(values)
    }

  given JsonDecoder[AgentCard] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentCard must be an object").flatMap { obj =>
      val fields       = obj.toMap
      val hasV1Binding = field(fields, "supportedInterfaces", "supported_interfaces").isDefined
      val legacyUrl    = fields.get("url").flatMap(_.asString).filter(_.nonEmpty)
      val legacyCard   = !hasV1Binding && legacyUrl.isDefined
      for
        name                <- requiredString(fields, "name")
        description         <- requiredString(fields, "description")
        supportedInterfaces <- field(fields, "supportedInterfaces", "supported_interfaces") match
          case Some(value) =>
            decodeList[AgentInterface](value, "supportedInterfaces").flatMap {
              case Nil    => Left("supportedInterfaces must contain at least one item")
              case values => Right(values)
            }
          case None =>
            legacyUrl match
              case Some(url) => Right(List(AgentInterface(url = url, protocolVersion = "0.3.0")))
              case None      => Left("Missing supportedInterfaces")
        version <- field(fields, "version").flatMap(_.asString).filter(_.nonEmpty) match
          case Some(value)        => Right(value)
          case None if legacyCard => Right("1.0.0")
          case None               => Left("Missing version")
        provider <- field(fields, "provider") match
          case Some(Json.Null) => Right(None)
          case Some(value)     => value.as[AgentProvider].map(Some(_))
          case None            => Right(None)
        capabilities <- field(fields, "capabilities") match
          case Some(value)        => value.as[AgentCapabilities]
          case None if legacyCard => Right(AgentCapabilities.default)
          case None               => Left("Missing capabilities")
        securitySchemes <- field(fields, "securitySchemes", "security_schemes") match
          case Some(Json.Null) => Right(Map.empty)
          case Some(value)     => value.as[Map[String, SecurityScheme]]
          case None            => Right(Map.empty)
        securityRequirements <- field(fields, "securityRequirements", "security_requirements", "security") match
          case Some(Json.Null) => Right(Nil)
          case Some(value)     => value.as[List[SecurityRequirement]]
          case None            => Right(Nil)
        defaultInputModes <- field(fields, "defaultInputModes", "default_input_modes") match
          case Some(value) =>
            decodeList[String](value, "defaultInputModes").flatMap {
              case Nil    => Left("defaultInputModes must contain at least one item")
              case values => Right(values)
            }
          case None if legacyCard => Right(List("text/plain"))
          case None               => Left("Missing defaultInputModes")
        defaultOutputModes <- field(fields, "defaultOutputModes", "default_output_modes") match
          case Some(value) =>
            decodeList[String](value, "defaultOutputModes").flatMap {
              case Nil    => Left("defaultOutputModes must contain at least one item")
              case values => Right(values)
            }
          case None if legacyCard => Right(List("text/plain"))
          case None               => Left("Missing defaultOutputModes")
        skills <- field(fields, "skills") match
          case Some(_)            => requiredNonEmptyList[AgentSkill](fields, "skills")
          case None if legacyCard => Right(requiredSkills(name, description, Nil))
          case None               => Left("Missing skills")
        signatures <- field(fields, "signatures") match
          case Some(Json.Null) => Right(Nil)
          case Some(value)     => value.as[List[AgentCardSignature]]
          case None            => Right(Nil)
        documentationUrl <- A2AJson.optionalString(fields, "documentationUrl", "documentation_url")
        iconUrl          <- A2AJson.optionalString(fields, "iconUrl", "icon_url")
      yield AgentCard(
        name = name,
        description = description,
        supportedInterfaces = supportedInterfaces,
        version = version,
        provider = provider,
        documentationUrl = documentationUrl,
        capabilities = capabilities,
        securitySchemes = securitySchemes,
        securityRequirements = securityRequirements,
        defaultInputModes = defaultInputModes,
        defaultOutputModes = defaultOutputModes,
        skills = skills,
        signatures = signatures,
        iconUrl = iconUrl,
      )
      end for
    }
  }

  /** Create a minimal v1 agent card with a single JSON-RPC interface. */
  def minimal(
    name: String,
    description: String,
    url: String,
  ): AgentCard =
    AgentCard(
      name = name,
      description = description,
      supportedInterfaces = List(AgentInterface.jsonRpc(url)),
      skills = requiredSkills(name, description, Nil),
    )

  def requiredSkills(
    agentName: String,
    agentDescription: String,
    skills: List[AgentSkill],
  ): List[AgentSkill] =
    skills.map(AgentSkill.withRequiredTags) match
      case Nil    => List(AgentSkill.defaultFor(agentName, agentDescription))
      case values => values
end AgentCard

/** Agent provider/organization information. */
final case class AgentProvider(
  url: String,
  organization: String)
object AgentProvider:
  given JsonEncoder[AgentProvider] = JsonEncoder[Json].contramap { provider =>
    Json.Obj(
      "url"          -> Json.Str(provider.url),
      "organization" -> Json.Str(provider.organization),
    )
  }
  given JsonDecoder[AgentProvider] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentProvider must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        url <- fields.get("url") match
          case Some(value) =>
            value.asString.toRight("url must be a string").flatMap {
              case value if value.nonEmpty => Right(value)
              case _                       => Left("Missing url")
            }
          case None => Left("Missing url")
        organization <- fields.get("organization") match
          case Some(value) =>
            value.asString.toRight("organization must be a string").flatMap {
              case value if value.nonEmpty => Right(value)
              case _                       => Left("Missing organization")
            }
          case None => Left("Missing organization")
      yield AgentProvider(
        url = url,
        organization = organization,
      )
    }
  }
end AgentProvider

/** Agent capabilities. */
final case class AgentCapabilities(
  streaming: Boolean = true,
  pushNotifications: Boolean = false,
  extensions: List[AgentExtension] = Nil,
  extendedAgentCard: Boolean = false)
object AgentCapabilities:
  val default: AgentCapabilities = AgentCapabilities()

  given JsonEncoder[AgentCapabilities] = JsonEncoder[Json].contramap { capabilities =>
    var obj = Json.Obj("streaming" -> Json.Bool(capabilities.streaming))
    if capabilities.pushNotifications then obj = obj.add("pushNotifications", Json.Bool(true))
    if capabilities.extensions.nonEmpty then
      obj = obj.add("extensions", Json.Arr(capabilities.extensions.map(_.toJsonAST.toOption.get)*))
    if capabilities.extendedAgentCard then obj = obj.add("extendedAgentCard", Json.Bool(true))
    obj
  }

  given JsonDecoder[AgentCapabilities] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentCapabilities must be an object").flatMap { obj =>
      val fields = obj.toMap
      def bool(
        defaultValue: Boolean,
        name: String,
        aliases: String*
      ): Either[String, Boolean] =
        (name +: aliases).iterator.flatMap(fields.get).nextOption() match
          case Some(Json.Null) => Right(defaultValue)
          case Some(value)     => value.asBoolean.toRight(s"$name must be a boolean")
          case None            => Right(defaultValue)
      def extensions: Either[String, List[AgentExtension]] =
        fields.get("extensions") match
          case Some(Json.Null) => Right(Nil)
          case Some(value)     => value.as[List[AgentExtension]]
          case None            => Right(Nil)
      for
        streaming         <- bool(false, "streaming")
        pushNotifications <- bool(default.pushNotifications, "pushNotifications", "push_notifications")
        decodedExtensions <- extensions
        extendedAgentCard <- bool(default.extendedAgentCard, "extendedAgentCard", "extended_agent_card")
      yield AgentCapabilities(
        streaming = streaming,
        pushNotifications = pushNotifications,
        extensions = decodedExtensions,
        extendedAgentCard = extendedAgentCard,
      )
    }
  }
end AgentCapabilities

/** Protocol extension declaration. */
final case class AgentExtension(
  uri: String,
  description: String = "",
  required: Boolean = false,
  params: Option[Json] = None)
object AgentExtension:
  given JsonEncoder[AgentExtension] = JsonEncoder[Json].contramap { extension =>
    var obj = Json.Obj("uri" -> Json.Str(extension.uri))
    if extension.description.nonEmpty then obj = obj.add("description", Json.Str(extension.description))
    if extension.required then obj = obj.add("required", Json.Bool(true))
    extension.params.foreach(value => obj = obj.add("params", value))
    obj
  }

  given JsonDecoder[AgentExtension] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentExtension must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        uri         <- fields.get("uri").flatMap(_.asString).filter(_.nonEmpty).toRight("Missing uri")
        description <- fields.get("description") match
          case Some(Json.Null) => Right("")
          case Some(value)     => value.asString.toRight("description must be a string")
          case None            => Right("")
        required <- fields.get("required") match
          case Some(Json.Null) => Right(false)
          case Some(value)     => value.asBoolean.toRight("required must be a boolean")
          case None            => Right(false)
        params <- A2AJson.optionalStruct(fields, "params")
      yield AgentExtension(
        uri = uri,
        description = description,
        required = required,
        params = params,
      )
    }
  }
end AgentExtension

/** A concrete protocol binding advertised by an AgentCard. */
final case class AgentInterface(
  url: String,
  protocolBinding: A2ATransport = A2ATransport.JSONRPC,
  tenant: Option[String] = None,
  protocolVersion: String = A2AProtocol.Version):

  /** Compatibility sugar for the old 0.3 model name. */
  def transport: A2ATransport = protocolBinding

object AgentInterface:
  given JsonEncoder[AgentInterface] = JsonEncoder[Json].contramap { iface =>
    var obj = Json.Obj(
      "url"             -> Json.Str(iface.url),
      "protocolBinding" -> Json.Str(iface.protocolBinding.toRaw),
      "protocolVersion" -> Json.Str(iface.protocolVersion),
    )
    iface.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
    obj
  }

  given JsonDecoder[AgentInterface] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentInterface must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        url         <- fields.get("url").flatMap(_.asString).filter(_.nonEmpty).toRight("Missing url")
        protocolRaw <- fields
          .get("protocolBinding")
          .orElse(fields.get("protocol_binding"))
          .flatMap(_.asString)
          .filter(_.nonEmpty)
          .toRight("Missing protocolBinding")
        protocolBinding <- Json.Str(protocolRaw).as[A2ATransport]
        protocolVersion <- fields
          .get("protocolVersion")
          .orElse(fields.get("protocol_version"))
          .flatMap(_.asString)
          .filter(_.nonEmpty)
          .toRight("Missing protocolVersion")
        tenant <- A2AJson.optionalString(fields, "tenant")
      yield AgentInterface(
        url = url,
        protocolBinding = protocolBinding,
        tenant = tenant.filter(_.nonEmpty),
        protocolVersion = protocolVersion,
      )
      end for
    }
  }

  def jsonRpc(url: String, tenant: Option[String] = None): AgentInterface =
    AgentInterface(url = url, protocolBinding = A2ATransport.JSONRPC, tenant = tenant)

  def rest(url: String, tenant: Option[String] = None): AgentInterface =
    AgentInterface(url = url, protocolBinding = A2ATransport.HTTP_JSON, tenant = tenant)

  def grpc(url: String, tenant: Option[String] = None): AgentInterface =
    AgentInterface(url = url, protocolBinding = A2ATransport.GRPC, tenant = tenant)
end AgentInterface

/** Agent skill/capability description. */
final case class AgentSkill(
  id: String,
  name: String,
  description: String,
  tags: List[String] = Nil,
  examples: List[String] = Nil,
  inputModes: List[String] = Nil,
  outputModes: List[String] = Nil,
  securityRequirements: List[SecurityRequirement] = Nil)
object AgentSkill:
  given JsonEncoder[AgentSkill] = JsonEncoder[Json].contramap { skill =>
    val normalized = withRequiredTags(skill)
    var obj        = Json.Obj(
      "id"          -> Json.Str(normalized.id),
      "name"        -> Json.Str(normalized.name),
      "description" -> Json.Str(normalized.description),
      "tags"        -> Json.Arr(normalized.tags.map(Json.Str(_))*),
    )
    if normalized.examples.nonEmpty then obj = obj.add("examples", Json.Arr(normalized.examples.map(Json.Str(_))*))
    if normalized.inputModes.nonEmpty then
      obj = obj.add("inputModes", Json.Arr(normalized.inputModes.map(Json.Str(_))*))
    if normalized.outputModes.nonEmpty then
      obj = obj.add("outputModes", Json.Arr(normalized.outputModes.map(Json.Str(_))*))
    if normalized.securityRequirements.nonEmpty then
      obj = obj.add("securityRequirements", Json.Arr(normalized.securityRequirements.map(_.toJsonAST.toOption.get)*))
    obj
  }

  def defaultFor(agentName: String, agentDescription: String): AgentSkill =
    val label = Option(agentName).map(_.trim).filter(_.nonEmpty).getOrElse("Default")
    AgentSkill(
      id = "default",
      name = s"$label default skill",
      description = Option(agentDescription).map(_.trim).filter(_.nonEmpty).getOrElse("Default agent skill"),
      tags = List("default"),
    )

  def withRequiredTags(skill: AgentSkill): AgentSkill =
    if skill.tags.exists(_.nonEmpty) then skill
    else
      skill.copy(
        tags = List(
          Option(skill.id)
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse("default")
        )
      )

  private def field(fields: Map[String, Json], names: String*): Option[Json] =
    names.iterator.flatMap(fields.get).nextOption()

  private def requiredString(
    fields: Map[String, Json],
    name: String,
  ): Either[String, String] =
    field(fields, name).flatMap(_.asString).filter(_.nonEmpty).toRight(s"Missing $name")

  private def optionalList[A: JsonDecoder](
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, List[A]] =
    field(fields, (name +: aliases)*) match
      case Some(Json.Null) => Right(Nil)
      case Some(value)     => value.as[List[A]]
      case None            => Right(Nil)

  private def requiredNonEmptyList[A: JsonDecoder](
    fields: Map[String, Json],
    name: String,
  ): Either[String, List[A]] =
    optionalList[A](fields, name).flatMap {
      case Nil    => Left(s"$name must contain at least one item")
      case values => Right(values)
    }

  given JsonDecoder[AgentSkill] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentSkill must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        id                   <- requiredString(fields, "id")
        name                 <- requiredString(fields, "name")
        description          <- requiredString(fields, "description")
        tags                 <- requiredNonEmptyList[String](fields, "tags")
        examples             <- optionalList[String](fields, "examples")
        inputModes           <- optionalList[String](fields, "inputModes", "input_modes")
        outputModes          <- optionalList[String](fields, "outputModes", "output_modes")
        securityRequirements <- optionalList[SecurityRequirement](
          fields,
          "securityRequirements",
          "security_requirements",
          "security",
        )
      yield AgentSkill(
        id = id,
        name = name,
        description = description,
        tags = tags,
        examples = examples,
        inputModes = inputModes,
        outputModes = outputModes,
        securityRequirements = securityRequirements,
      )
      end for
    }
  }
end AgentSkill

/** AgentCard JWS signature (RFC 7515). */
final case class AgentCardSignature(
  `protected`: String,
  signature: String,
  header: Option[Json] = None)
object AgentCardSignature:
  given JsonEncoder[AgentCardSignature] = JsonEncoder[Json].contramap { signature =>
    var obj = Json.Obj(
      "protected" -> Json.Str(signature.`protected`),
      "signature" -> Json.Str(signature.signature),
    )
    signature.header.foreach(value => obj = obj.add("header", value))
    obj
  }
  given JsonDecoder[AgentCardSignature] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentCardSignature must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        protectedValue <- fields.get("protected").flatMap(_.asString).filter(_.nonEmpty).toRight("Missing protected")
        signature      <- fields.get("signature").flatMap(_.asString).filter(_.nonEmpty).toRight("Missing signature")
        header         <- A2AJson.optionalStruct(fields, "header")
      yield AgentCardSignature(
        `protected` = protectedValue,
        signature = signature,
        header = header,
      )
    }
  }
end AgentCardSignature

/** Security requirement (OpenAPI style). */
final case class SecurityRequirement(
  schemes: Map[String, List[String]] = Map.empty)
object SecurityRequirement:
  given JsonEncoder[SecurityRequirement] = JsonEncoder[Json].contramap { requirement =>
    val encoded = requirement.schemes.map {
      case (name, scopes) =>
        name -> Json.Obj("list" -> Json.Arr(scopes.map(Json.Str(_))*))
    }
    Json.Obj("schemes" -> Json.Obj(encoded.toSeq*))
  }

  given JsonDecoder[SecurityRequirement] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("SecurityRequirement must be an object").flatMap { obj =>
      val raw         = obj.toMap
      val schemesJson = raw.get("schemes").orElse(Some(json))
      schemesJson
        .flatMap(_.asObject)
        .map(_.toMap)
        .toRight("SecurityRequirement schemes must be an object")
        .flatMap { schemes =>
          schemes.toList
            .map {
              case (name, value) if name.nonEmpty =>
                value.asObject.flatMap(_.toMap.get("list")) match
                  case Some(list) => list.as[List[String]].map(name -> _)
                  case None       => value.as[List[String]].map(name -> _)
              case _ =>
                Left("SecurityRequirement scheme name must be non-empty")
            }
            .foldRight[Either[String, List[(String, List[String])]]](Right(Nil)) {
              case (Right(value), Right(values)) => Right(value :: values)
              case (Left(error), _)              => Left(error)
              case (_, Left(error))              => Left(error)
            }
            .map(values => SecurityRequirement(values.toMap))
        }
    }
  }
end SecurityRequirement

/** Security scheme definition (OpenAPI-style). */
enum SecurityScheme:
  case ApiKey(
    name: String,
    in: String,
    description: String = "")
  case Http(
    scheme: String,
    bearerFormat: Option[String] = None,
    description: String = "")
  case OAuth2(
    flows: OAuth2Flows,
    oauth2MetadataUrl: Option[String] = None,
    description: String = "")
  case OpenIdConnect(openIdConnectUrl: String, description: String = "")
  case MutualTLS(description: String = "")

object SecurityScheme:
  def toOpenApiJson(scheme: SecurityScheme): Json =
    scheme match
      case ApiKey(name, in, description) =>
        var apiKey = Json.Obj(
          "type" -> Json.Str("apiKey"),
          "name" -> Json.Str(name),
          "in"   -> Json.Str(in),
        )
        if description.nonEmpty then apiKey = apiKey.add("description", Json.Str(description))
        apiKey
      case Http(scheme, bearerFormat, description) =>
        var http = Json.Obj(
          "type"   -> Json.Str("http"),
          "scheme" -> Json.Str(scheme),
        )
        bearerFormat.foreach(value => http = http.add("bearerFormat", Json.Str(value)))
        if description.nonEmpty then http = http.add("description", Json.Str(description))
        http
      case OAuth2(flows, oauth2MetadataUrl, description) =>
        var oauth = Json.Obj(
          "type"  -> Json.Str("oauth2"),
          "flows" -> flows.toJsonAST.toOption.get,
        )
        oauth2MetadataUrl.foreach(value => oauth = oauth.add("oauth2MetadataUrl", Json.Str(value)))
        if description.nonEmpty then oauth = oauth.add("description", Json.Str(description))
        oauth
      case OpenIdConnect(openIdConnectUrl, description) =>
        var oidc = Json.Obj(
          "type"             -> Json.Str("openIdConnect"),
          "openIdConnectUrl" -> Json.Str(openIdConnectUrl),
        )
        if description.nonEmpty then oidc = oidc.add("description", Json.Str(description))
        oidc
      case MutualTLS(description) =>
        var mtls = Json.Obj("type" -> Json.Str("mutualTLS"))
        if description.nonEmpty then mtls = mtls.add("description", Json.Str(description))
        mtls

  given JsonEncoder[SecurityScheme] = JsonEncoder[Json].contramap {
    case ApiKey(name, in, description) =>
      var apiKey = Json.Obj(
        "name"     -> Json.Str(name),
        "location" -> Json.Str(in),
      )
      if description.nonEmpty then apiKey = apiKey.add("description", Json.Str(description))
      Json.Obj("apiKeySecurityScheme" -> apiKey)
    case Http(scheme, bearerFormat, description) =>
      var http = Json.Obj("scheme" -> Json.Str(scheme))
      bearerFormat.foreach(value => http = http.add("bearerFormat", Json.Str(value)))
      if description.nonEmpty then http = http.add("description", Json.Str(description))
      Json.Obj("httpAuthSecurityScheme" -> http)
    case OAuth2(flows, oauth2MetadataUrl, description) =>
      var oauth = Json.Obj("flows" -> flows.toJsonAST.toOption.get)
      oauth2MetadataUrl.foreach(value => oauth = oauth.add("oauth2MetadataUrl", Json.Str(value)))
      if description.nonEmpty then oauth = oauth.add("description", Json.Str(description))
      Json.Obj("oauth2SecurityScheme" -> oauth)
    case OpenIdConnect(openIdConnectUrl, description) =>
      var oidc = Json.Obj("openIdConnectUrl" -> Json.Str(openIdConnectUrl))
      if description.nonEmpty then oidc = oidc.add("description", Json.Str(description))
      Json.Obj("openIdConnectSecurityScheme" -> oidc)
    case MutualTLS(description) =>
      val mtls =
        if description.nonEmpty then Json.Obj("description" -> Json.Str(description))
        else Json.Obj()
      Json.Obj("mtlsSecurityScheme" -> mtls)
  }

  private def decodeOpenApiSecurityScheme(fields: Map[String, Json], schemeType: String)
    : Either[String, SecurityScheme] =
    schemeType match
      case "apiKey" =>
        for
          name <- A2AJson
            .optionalString(fields, "name")
            .flatMap(_.filter(_.nonEmpty).toRight("Missing api key name"))
          location <- A2AJson
            .optionalString(fields, "in", "location")
            .flatMap(_.filter(_.nonEmpty).toRight("Missing api key location"))
          description <- A2AJson.optionalString(fields, "description")
        yield ApiKey(name, location, description.getOrElse(""))
      case "http" =>
        for
          schemeName <- A2AJson
            .optionalString(fields, "scheme")
            .flatMap(_.filter(_.nonEmpty).toRight("Missing http auth scheme"))
          bearerFormat <- A2AJson.optionalString(fields, "bearerFormat", "bearer_format")
          description  <- A2AJson.optionalString(fields, "description")
        yield Http(schemeName, bearerFormat, description.getOrElse(""))
      case "oauth2" =>
        for
          flows             <- fields.get("flows").toRight("Missing oauth flows").flatMap(_.as[OAuth2Flows])
          oauth2MetadataUrl <- A2AJson.optionalString(fields, "oauth2MetadataUrl", "oauth2_metadata_url")
          description       <- A2AJson.optionalString(fields, "description")
        yield OAuth2(flows, oauth2MetadataUrl, description.getOrElse(""))
      case "openIdConnect" =>
        for
          url <- A2AJson
            .optionalString(fields, "openIdConnectUrl", "open_id_connect_url")
            .flatMap(_.filter(_.nonEmpty).toRight("Missing openIdConnectUrl"))
          description <- A2AJson.optionalString(fields, "description")
        yield OpenIdConnect(url, description.getOrElse(""))
      case "mutualTLS" =>
        A2AJson.optionalString(fields, "description").map(description => MutualTLS(description.getOrElse("")))
      case other =>
        Left(s"Unknown security scheme type: $other")

  private def decodeProtoSecurityScheme(schemes: List[(String, Json)]): Either[String, SecurityScheme] =
    schemes match
      case ("apiKeySecurityScheme", value) :: Nil =>
        value.asObject.toRight("apiKeySecurityScheme must be an object").flatMap { scheme =>
          val f = scheme.toMap
          for
            name <- A2AJson
              .optionalString(f, "name")
              .flatMap(_.filter(_.nonEmpty).toRight("Missing api key name"))
            location <- A2AJson
              .optionalString(f, "location", "in")
              .flatMap(_.filter(_.nonEmpty).toRight("Missing api key location"))
            description <- A2AJson.optionalString(f, "description")
          yield ApiKey(name, location, description.getOrElse(""))
        }
      case ("httpAuthSecurityScheme", value) :: Nil =>
        value.asObject.toRight("httpAuthSecurityScheme must be an object").flatMap { scheme =>
          val f = scheme.toMap
          for
            schemeName <- A2AJson
              .optionalString(f, "scheme")
              .flatMap(_.filter(_.nonEmpty).toRight("Missing http auth scheme"))
            bearerFormat <- A2AJson.optionalString(f, "bearerFormat", "bearer_format")
            description  <- A2AJson.optionalString(f, "description")
          yield Http(
            schemeName,
            bearerFormat,
            description.getOrElse(""),
          )
        }
      case ("oauth2SecurityScheme", value) :: Nil =>
        value.asObject.toRight("oauth2SecurityScheme must be an object").flatMap { scheme =>
          val f = scheme.toMap
          for
            flows             <- f.get("flows").toRight("Missing oauth flows").flatMap(_.as[OAuth2Flows])
            oauth2MetadataUrl <- A2AJson.optionalString(f, "oauth2MetadataUrl", "oauth2_metadata_url")
            description       <- A2AJson.optionalString(f, "description")
          yield OAuth2(
            flows,
            oauth2MetadataUrl,
            description.getOrElse(""),
          )
        }
      case ("openIdConnectSecurityScheme", value) :: Nil =>
        value.asObject.toRight("openIdConnectSecurityScheme must be an object").flatMap { scheme =>
          val f = scheme.toMap
          for
            url <- A2AJson
              .optionalString(f, "openIdConnectUrl", "open_id_connect_url")
              .flatMap(_.filter(_.nonEmpty).toRight("Missing openIdConnectUrl"))
            description <- A2AJson.optionalString(f, "description")
          yield OpenIdConnect(url, description.getOrElse(""))
        }
      case ("mtlsSecurityScheme", value) :: Nil =>
        value.asObject.toRight("mtlsSecurityScheme must be an object").flatMap { scheme =>
          A2AJson.optionalString(scheme.toMap, "description").map(description => MutualTLS(description.getOrElse("")))
        }
      case Nil =>
        Left("SecurityScheme must contain a recognized oneof field")
      case _ =>
        Left("SecurityScheme must contain exactly one recognized oneof field")

  given JsonDecoder[SecurityScheme] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("SecurityScheme must be an object").flatMap { obj =>
      val fields     = obj.toMap
      val schemeType = fields.get("type").flatMap(_.asString)
      val schemes    = List(
        A2AJson.nonNullNamedField(fields, "apiKeySecurityScheme", "api_key_security_scheme"),
        A2AJson.nonNullNamedField(fields, "httpAuthSecurityScheme", "http_auth_security_scheme"),
        A2AJson.nonNullNamedField(fields, "oauth2SecurityScheme", "oauth2_security_scheme"),
        A2AJson.nonNullNamedField(fields, "openIdConnectSecurityScheme", "open_id_connect_security_scheme"),
        A2AJson.nonNullNamedField(fields, "mtlsSecurityScheme", "mtls_security_scheme"),
      ).flatten
      (schemeType, schemes) match
        case (Some(value), Nil) =>
          decodeOpenApiSecurityScheme(fields, value)
        case (Some(_), _ :: _) =>
          Left("SecurityScheme must contain exactly one recognized oneof field or one OpenAPI type")
        case (None, values) =>
          decodeProtoSecurityScheme(values)
    }
  }
end SecurityScheme

/** OAuth2 flows configuration. */
final case class OAuth2Flows(
  authorizationCode: Option[OAuth2Flow] = None,
  clientCredentials: Option[OAuth2Flow] = None,
  implicit_ : Option[OAuth2Flow] = None,
  password: Option[OAuth2Flow] = None,
  deviceCode: Option[OAuth2Flow] = None):
  require(activeFlowCount <= 1, "OAuth2Flows must contain at most one flow")

  def activeFlowCount: Int =
    List(authorizationCode, clientCredentials, implicit_, password, deviceCode).count(_.isDefined)
object OAuth2Flows:
  private def exactlyOneFlowError: String =
    "OAuth2Flows must contain exactly one of authorizationCode, clientCredentials, implicit, password, or deviceCode"

  private def decodeRequiredFlow(
    value: Json,
    flowName: String,
    requiredFields: List[(String, List[String])],
  ): Either[String, OAuth2Flow] =
    value.asObject.toRight(s"$flowName flow must be an object").flatMap { obj =>
      val fields                               = obj.toMap
      def hasAny(names: List[String]): Boolean =
        names.exists(fields.contains)
      val missing = requiredFields.collect { case (label, names) if !hasAny(names) => label }
      if missing.nonEmpty then Left(s"$flowName flow missing required field(s): ${missing.mkString(", ")}")
      else
        value.as[OAuth2Flow].flatMap { flow =>
          val empty = requiredFields.collect {
            case ("authorizationUrl", _) if flow.authorizationUrl.forall(_.isEmpty)             => "authorizationUrl"
            case ("tokenUrl", _) if flow.tokenUrl.forall(_.isEmpty)                             => "tokenUrl"
            case ("deviceAuthorizationUrl", _) if flow.deviceAuthorizationUrl.forall(_.isEmpty) =>
              "deviceAuthorizationUrl"
          }
          if empty.nonEmpty then Left(s"$flowName flow missing required field(s): ${empty.mkString(", ")}")
          else Right(flow)
        }
    }

  given JsonEncoder[OAuth2Flows] = JsonEncoder[Json].contramap { flows =>
    var obj = Json.Obj()
    flows.authorizationCode.foreach(flow => obj = obj.add("authorizationCode", flow.toJsonAST.toOption.get))
    flows.clientCredentials.foreach(flow => obj = obj.add("clientCredentials", flow.toJsonAST.toOption.get))
    flows.implicit_.foreach(flow => obj = obj.add("implicit", flow.toJsonAST.toOption.get))
    flows.password.foreach(flow => obj = obj.add("password", flow.toJsonAST.toOption.get))
    flows.deviceCode.foreach(flow => obj = obj.add("deviceCode", flow.toJsonAST.toOption.get))
    obj
  }

  given JsonDecoder[OAuth2Flows] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("OAuth2Flows must be an object").flatMap { obj =>
      val fields                             = obj.toMap
      def flow(names: String*): Option[Json] =
        names.iterator.flatMap(fields.get).filter(_ != Json.Null).nextOption()

      val candidates = List(
        flow("authorizationCode", "authorization_code").map("authorizationCode" -> _),
        flow("clientCredentials", "client_credentials").map("clientCredentials" -> _),
        flow("implicit", "implicit_").map("implicit" -> _),
        flow("password").map("password" -> _),
        flow("deviceCode", "device_code").map("deviceCode" -> _),
      ).flatten

      candidates match
        case ("authorizationCode", value) :: Nil =>
          decodeRequiredFlow(
            value,
            "authorizationCode",
            List(
              "authorizationUrl" -> List("authorizationUrl", "authorization_url"),
              "tokenUrl"         -> List("tokenUrl", "token_url"),
              "scopes"           -> List("scopes"),
            ),
          ).map(flow => OAuth2Flows(authorizationCode = Some(flow)))
        case ("clientCredentials", value) :: Nil =>
          decodeRequiredFlow(
            value,
            "clientCredentials",
            List(
              "tokenUrl" -> List("tokenUrl", "token_url"),
              "scopes"   -> List("scopes"),
            ),
          ).map(flow => OAuth2Flows(clientCredentials = Some(flow)))
        case ("implicit", value) :: Nil =>
          value.as[OAuth2Flow].map(flow => OAuth2Flows(implicit_ = Some(flow)))
        case ("password", value) :: Nil =>
          value.as[OAuth2Flow].map(flow => OAuth2Flows(password = Some(flow)))
        case ("deviceCode", value) :: Nil =>
          decodeRequiredFlow(
            value,
            "deviceCode",
            List(
              "deviceAuthorizationUrl" -> List("deviceAuthorizationUrl", "device_authorization_url"),
              "tokenUrl"               -> List("tokenUrl", "token_url"),
              "scopes"                 -> List("scopes"),
            ),
          ).map(flow => OAuth2Flows(deviceCode = Some(flow)))
        case Nil =>
          Left(exactlyOneFlowError)
        case _ =>
          Left(exactlyOneFlowError)
      end match
    }
  }
end OAuth2Flows

/** Single OAuth2 flow. */
final case class OAuth2Flow(
  authorizationUrl: Option[String] = None,
  tokenUrl: Option[String] = None,
  refreshUrl: Option[String] = None,
  deviceAuthorizationUrl: Option[String] = None,
  scopes: Map[String, String] = Map.empty,
  pkceRequired: Boolean = false)
object OAuth2Flow:
  given JsonEncoder[OAuth2Flow] = JsonEncoder[Json].contramap { flow =>
    var obj = Json.Obj()
    flow.authorizationUrl.filter(_.nonEmpty).foreach(value => obj = obj.add("authorizationUrl", Json.Str(value)))
    flow.tokenUrl.filter(_.nonEmpty).foreach(value => obj = obj.add("tokenUrl", Json.Str(value)))
    flow.refreshUrl.filter(_.nonEmpty).foreach(value => obj = obj.add("refreshUrl", Json.Str(value)))
    flow.deviceAuthorizationUrl
      .filter(_.nonEmpty)
      .foreach(value => obj = obj.add("deviceAuthorizationUrl", Json.Str(value)))
    if flow.scopes.nonEmpty then
      obj = obj.add("scopes", Json.Obj(flow.scopes.toSeq.map { case (k, v) => k -> Json.Str(v) }*))
    if flow.pkceRequired then obj = obj.add("pkceRequired", Json.Bool(true))
    obj
  }
  given JsonDecoder[OAuth2Flow] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("OAuth2Flow must be an object").flatMap { obj =>
      val fields                                                         = obj.toMap
      def optionalString(names: String*): Either[String, Option[String]] =
        names.iterator.flatMap(fields.get).nextOption() match
          case Some(Json.Null) => Right(None)
          case Some(value)     => value.asString.map(Some(_)).toRight(s"${names.head} must be a string")
          case None            => Right(None)
      def optionalBool(names: String*): Either[String, Boolean] =
        names.iterator.flatMap(fields.get).nextOption() match
          case Some(Json.Null) => Right(false)
          case Some(value)     => value.asBoolean.toRight(s"${names.head} must be a boolean")
          case None            => Right(false)
      for
        authorizationUrl       <- optionalString("authorizationUrl", "authorization_url")
        tokenUrl               <- optionalString("tokenUrl", "token_url")
        refreshUrl             <- optionalString("refreshUrl", "refresh_url")
        deviceAuthorizationUrl <- optionalString("deviceAuthorizationUrl", "device_authorization_url")
        scopes <- fields.get("scopes").filter(_ != Json.Null).map(_.as[Map[String, String]]).getOrElse(Right(Map.empty))
        pkceRequired <- optionalBool("pkceRequired", "pkce_required")
      yield OAuth2Flow(
        authorizationUrl = authorizationUrl,
        tokenUrl = tokenUrl,
        refreshUrl = refreshUrl,
        deviceAuthorizationUrl = deviceAuthorizationUrl,
        scopes = scopes,
        pkceRequired = pkceRequired,
      )
    }
  }
end OAuth2Flow
