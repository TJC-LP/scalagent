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
  given JsonEncoder[AgentCard] = DeriveJsonEncoder.gen[AgentCard]
  given JsonDecoder[AgentCard] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentCard must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        name                <- fields.get("name").flatMap(_.asString).toRight("Missing name")
        description         <- fields.get("description").flatMap(_.asString).toRight("Missing description")
        supportedInterfaces <-
          fields
            .get("supportedInterfaces")
            .orElse(fields.get("supported_interfaces"))
            .flatMap(_.asArray)
            .map(_.toList.map(_.as[AgentInterface]).foldRight[Either[String, List[AgentInterface]]](Right(Nil)) {
              case (Right(value), Right(values)) => Right(value :: values)
              case (Left(error), _)              => Left(error)
              case (_, Left(error))              => Left(error)
            })
            .getOrElse {
              fields.get("url").flatMap(_.asString) match
                case Some(url) => Right(List(AgentInterface(url = url, protocolVersion = "0.3.0")))
                case None      => Right(Nil)
            }
        provider     = fields.get("provider").flatMap(_.as[AgentProvider].toOption)
        capabilities = fields
          .get("capabilities")
          .flatMap(_.as[AgentCapabilities].toOption)
          .getOrElse(AgentCapabilities.default)
        securitySchemes = fields
          .get("securitySchemes")
          .orElse(fields.get("security_schemes"))
          .flatMap(_.as[Map[String, SecurityScheme]].toOption)
          .getOrElse(Map.empty)
        securityRequirements = fields
          .get("securityRequirements")
          .orElse(fields.get("security_requirements"))
          .flatMap(_.as[List[SecurityRequirement]].toOption)
          .getOrElse(Nil)
        skills     = fields.get("skills").flatMap(_.as[List[AgentSkill]].toOption).getOrElse(Nil)
        signatures = fields.get("signatures").flatMap(_.as[List[AgentCardSignature]].toOption).getOrElse(Nil)
      yield AgentCard(
        name = name,
        description = description,
        supportedInterfaces = supportedInterfaces,
        version = fields.get("version").flatMap(_.asString).getOrElse("1.0.0"),
        provider = provider,
        documentationUrl = fields
          .get("documentationUrl")
          .orElse(fields.get("documentation_url"))
          .flatMap(_.asString),
        capabilities = capabilities,
        securitySchemes = securitySchemes,
        securityRequirements = securityRequirements,
        defaultInputModes = fields
          .get("defaultInputModes")
          .orElse(fields.get("default_input_modes"))
          .flatMap(_.as[List[String]].toOption)
          .getOrElse(List("text/plain")),
        defaultOutputModes = fields
          .get("defaultOutputModes")
          .orElse(fields.get("default_output_modes"))
          .flatMap(_.as[List[String]].toOption)
          .getOrElse(List("text/plain")),
        skills = skills,
        signatures = signatures,
        iconUrl = fields.get("iconUrl").orElse(fields.get("icon_url")).flatMap(_.asString),
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
    )
end AgentCard

/** Agent provider/organization information. */
final case class AgentProvider(
  url: String,
  organization: String)
object AgentProvider:
  given JsonEncoder[AgentProvider] = DeriveJsonEncoder.gen[AgentProvider]
  given JsonDecoder[AgentProvider] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentProvider must be an object").flatMap { obj =>
      val fields = obj.toMap
      for organization <- fields.get("organization").flatMap(_.asString).toRight("Missing organization")
      yield AgentProvider(
        url = fields.get("url").flatMap(_.asString).getOrElse(""),
        organization = organization,
      )
    }
  }

/** Agent capabilities. */
final case class AgentCapabilities(
  streaming: Boolean = true,
  pushNotifications: Boolean = false,
  extensions: List[AgentExtension] = Nil,
  extendedAgentCard: Boolean = false)
object AgentCapabilities:
  val default: AgentCapabilities = AgentCapabilities()

  given JsonEncoder[AgentCapabilities] = DeriveJsonEncoder.gen[AgentCapabilities]
  given JsonDecoder[AgentCapabilities] = DeriveJsonDecoder.gen[AgentCapabilities]

/** Protocol extension declaration. */
final case class AgentExtension(
  uri: String,
  description: String = "",
  required: Boolean = false,
  params: Option[Json] = None)
object AgentExtension:
  given JsonEncoder[AgentExtension] = DeriveJsonEncoder.gen[AgentExtension]
  given JsonDecoder[AgentExtension] = DeriveJsonDecoder.gen[AgentExtension]

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
        url <- fields.get("url").flatMap(_.asString).toRight("Missing url")
        protocolRaw = fields
          .get("protocolBinding")
          .orElse(fields.get("protocol_binding"))
          .orElse(fields.get("transport"))
          .flatMap(_.asString)
          .getOrElse("JSONRPC")
        protocolBinding <- Json.Str(protocolRaw).as[A2ATransport]
      yield AgentInterface(
        url = url,
        protocolBinding = protocolBinding,
        tenant = fields.get("tenant").flatMap(_.asString).filter(_.nonEmpty),
        protocolVersion = fields
          .get("protocolVersion")
          .orElse(fields.get("protocol_version"))
          .flatMap(_.asString)
          .getOrElse(A2AProtocol.Version),
      )
    }
  }

  def jsonRpc(url: String, tenant: Option[String] = None): AgentInterface =
    AgentInterface(url = url, protocolBinding = A2ATransport.JSONRPC, tenant = tenant)

  def rest(url: String, tenant: Option[String] = None): AgentInterface =
    AgentInterface(url = url, protocolBinding = A2ATransport.HTTP_JSON, tenant = tenant)
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
  given JsonEncoder[AgentSkill] = DeriveJsonEncoder.gen[AgentSkill]
  given JsonDecoder[AgentSkill] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AgentSkill must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        id          <- fields.get("id").flatMap(_.asString).toRight("Missing id")
        name        <- fields.get("name").flatMap(_.asString).toRight("Missing name")
        description <- fields.get("description").flatMap(_.asString).toRight("Missing description")
      yield AgentSkill(
        id = id,
        name = name,
        description = description,
        tags = fields.get("tags").flatMap(_.as[List[String]].toOption).getOrElse(Nil),
        examples = fields.get("examples").flatMap(_.as[List[String]].toOption).getOrElse(Nil),
        inputModes = fields
          .get("inputModes")
          .orElse(fields.get("input_modes"))
          .flatMap(_.as[List[String]].toOption)
          .getOrElse(Nil),
        outputModes = fields
          .get("outputModes")
          .orElse(fields.get("output_modes"))
          .flatMap(_.as[List[String]].toOption)
          .getOrElse(Nil),
        securityRequirements = fields
          .get("securityRequirements")
          .orElse(fields.get("security_requirements"))
          .orElse(fields.get("security"))
          .flatMap(_.as[List[SecurityRequirement]].toOption)
          .getOrElse(Nil),
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
  given JsonEncoder[AgentCardSignature] = DeriveJsonEncoder.gen[AgentCardSignature]
  given JsonDecoder[AgentCardSignature] = DeriveJsonDecoder.gen[AgentCardSignature]

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
        .map { schemes =>
          SecurityRequirement(
            schemes.collect {
              case (name, value) if value.asObject.exists(_.toMap.contains("list")) =>
                name -> value.asObject
                  .flatMap(_.toMap.get("list"))
                  .flatMap(_.asArray)
                  .map(_.toList.flatMap(_.asString))
                  .getOrElse(Nil)
              case (name, value) if value.asArray.isDefined =>
                name -> value.asArray.map(_.toList.flatMap(_.asString)).getOrElse(Nil)
            }
          )
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
  given JsonEncoder[SecurityScheme] = JsonEncoder[Json].contramap {
    case ApiKey(name, in, description) =>
      Json.Obj(
        "apiKeySecurityScheme" -> Json.Obj(
          "name"        -> Json.Str(name),
          "location"    -> Json.Str(in),
          "description" -> Json.Str(description),
        )
      )
    case Http(scheme, bearerFormat, description) =>
      var http = Json.Obj("scheme" -> Json.Str(scheme), "description" -> Json.Str(description))
      bearerFormat.foreach(value => http = http.add("bearerFormat", Json.Str(value)))
      Json.Obj("httpAuthSecurityScheme" -> http)
    case OAuth2(flows, oauth2MetadataUrl, description) =>
      var oauth = Json.Obj("flows" -> flows.toJsonAST.toOption.get, "description" -> Json.Str(description))
      oauth2MetadataUrl.foreach(value => oauth = oauth.add("oauth2MetadataUrl", Json.Str(value)))
      Json.Obj("oauth2SecurityScheme" -> oauth)
    case OpenIdConnect(openIdConnectUrl, description) =>
      Json.Obj(
        "openIdConnectSecurityScheme" -> Json.Obj(
          "openIdConnectUrl" -> Json.Str(openIdConnectUrl),
          "description"      -> Json.Str(description),
        )
      )
    case MutualTLS(description) =>
      Json.Obj("mtlsSecurityScheme" -> Json.Obj("description" -> Json.Str(description)))
  }

  given JsonDecoder[SecurityScheme] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("SecurityScheme must be an object").flatMap { obj =>
      val fields = obj.toMap
      fields.get("apiKeySecurityScheme").orElse(fields.get("api_key_security_scheme")) match
        case Some(value) =>
          value.asObject.toRight("apiKeySecurityScheme must be an object").flatMap { scheme =>
            val f = scheme.toMap
            for
              name     <- f.get("name").flatMap(_.asString).toRight("Missing api key name")
              location <- f.get("location").orElse(f.get("in")).flatMap(_.asString).toRight("Missing api key location")
            yield ApiKey(name, location, f.get("description").flatMap(_.asString).getOrElse(""))
          }
        case None =>
          fields.get("httpAuthSecurityScheme").orElse(fields.get("http_auth_security_scheme")) match
            case Some(value) =>
              value.asObject.toRight("httpAuthSecurityScheme must be an object").flatMap { scheme =>
                val f = scheme.toMap
                f.get("scheme").flatMap(_.asString).toRight("Missing http auth scheme").map { schemeName =>
                  Http(
                    schemeName,
                    f.get("bearerFormat").orElse(f.get("bearer_format")).flatMap(_.asString),
                    f.get("description").flatMap(_.asString).getOrElse(""),
                  )
                }
              }
            case None =>
              fields.get("oauth2SecurityScheme").orElse(fields.get("oauth2_security_scheme")) match
                case Some(value) =>
                  value.asObject.toRight("oauth2SecurityScheme must be an object").flatMap { scheme =>
                    val f = scheme.toMap
                    f.get("flows").toRight("Missing oauth flows").flatMap(_.as[OAuth2Flows]).map { flows =>
                      OAuth2(
                        flows,
                        f.get("oauth2MetadataUrl").orElse(f.get("oauth2_metadata_url")).flatMap(_.asString),
                        f.get("description").flatMap(_.asString).getOrElse(""),
                      )
                    }
                  }
                case None =>
                  fields.get("openIdConnectSecurityScheme").orElse(fields.get("open_id_connect_security_scheme")) match
                    case Some(value) =>
                      value.asObject.toRight("openIdConnectSecurityScheme must be an object").flatMap { scheme =>
                        val f = scheme.toMap
                        f.get("openIdConnectUrl")
                          .orElse(f.get("open_id_connect_url"))
                          .flatMap(_.asString)
                          .toRight("Missing openIdConnectUrl")
                          .map { url => OpenIdConnect(url, f.get("description").flatMap(_.asString).getOrElse("")) }
                      }
                    case None =>
                      fields.get("mtlsSecurityScheme").orElse(fields.get("mtls_security_scheme")) match
                        case Some(value) =>
                          Right(
                            MutualTLS(
                              value.asObject.flatMap(_.toMap.get("description")).flatMap(_.asString).getOrElse("")
                            )
                          )
                        case None =>
                          Left("SecurityScheme must contain a recognized oneof field")
      end match
    }
  }
end SecurityScheme

/** OAuth2 flows configuration. */
final case class OAuth2Flows(
  authorizationCode: Option[OAuth2Flow] = None,
  clientCredentials: Option[OAuth2Flow] = None,
  implicit_ : Option[OAuth2Flow] = None,
  password: Option[OAuth2Flow] = None,
  deviceCode: Option[OAuth2Flow] = None)
object OAuth2Flows:
  given JsonEncoder[OAuth2Flows] = DeriveJsonEncoder.gen[OAuth2Flows]
  given JsonDecoder[OAuth2Flows] = DeriveJsonDecoder.gen[OAuth2Flows]

/** Single OAuth2 flow. */
final case class OAuth2Flow(
  authorizationUrl: Option[String] = None,
  tokenUrl: Option[String] = None,
  refreshUrl: Option[String] = None,
  deviceAuthorizationUrl: Option[String] = None,
  scopes: Map[String, String] = Map.empty,
  pkceRequired: Boolean = false)
object OAuth2Flow:
  given JsonEncoder[OAuth2Flow] = DeriveJsonEncoder.gen[OAuth2Flow]
  given JsonDecoder[OAuth2Flow] = DeriveJsonDecoder.gen[OAuth2Flow]
