package com.tjclp.scalagent.a2a

import zio.json.*

/** Agent Card for A2A discovery.
  *
  * The AgentCard is a self-describing manifest that contains metadata about an agent, including its
  * capabilities, skills, and authentication requirements. It is served at
  * `/.well-known/agent-card.json`.
  *
  * @param name
  *   Human-readable name of the agent
  * @param description
  *   Description of what the agent does
  * @param url
  *   Preferred endpoint URL for the agent
  * @param version
  *   Version of the agent implementation
  * @param protocolVersion
  *   A2A protocol version (defaults to 0.3.0)
  * @param provider
  *   Optional provider/organization information
  * @param documentationUrl
  *   Optional URL to agent documentation
  * @param iconUrl
  *   Optional URL to agent icon
  * @param capabilities
  *   Agent capabilities (streaming, push notifications, etc.)
  * @param preferredTransport
  *   Preferred transport mechanism
  * @param additionalInterfaces
  *   Alternative transport/URL combinations
  * @param defaultInputModes
  *   Supported input MIME types
  * @param defaultOutputModes
  *   Supported output MIME types
  * @param skills
  *   List of agent skills/capabilities
  * @param security
  *   Security requirements
  * @param securitySchemes
  *   Available security schemes
  * @param supportsAuthenticatedExtendedCard
  *   Whether agent supports extended card with auth
  */
final case class AgentCard(
    name: String,
    description: String,
    url: String,
    version: String = "1.0.0",
    protocolVersion: String = A2AProtocol.Version,
    provider: Option[AgentProvider] = None,
    documentationUrl: Option[String] = None,
    iconUrl: Option[String] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default,
    preferredTransport: A2ATransport = A2ATransport.JSONRPC,
    additionalInterfaces: List[AgentInterface] = Nil,
    defaultInputModes: List[String] = List("text/plain"),
    defaultOutputModes: List[String] = List("text/plain"),
    skills: List[AgentSkill] = Nil,
    security: List[SecurityRequirement] = Nil,
    securitySchemes: Map[String, SecurityScheme] = Map.empty,
    signatures: List[AgentCardSignature] = Nil,
    supportsAuthenticatedExtendedCard: Boolean = false
)
object AgentCard:
  given JsonEncoder[AgentCard] = DeriveJsonEncoder.gen[AgentCard]
  given JsonDecoder[AgentCard] = DeriveJsonDecoder.gen[AgentCard]

  /** Create a minimal agent card */
  def minimal(name: String, description: String, url: String): AgentCard =
    AgentCard(name = name, description = description, url = url)

/** Agent provider/organization information */
final case class AgentProvider(
    organization: String,
    url: String
)
object AgentProvider:
  given JsonEncoder[AgentProvider] = DeriveJsonEncoder.gen[AgentProvider]
  given JsonDecoder[AgentProvider] = DeriveJsonDecoder.gen[AgentProvider]

/** Agent capabilities */
final case class AgentCapabilities(
    streaming: Boolean = true,
    pushNotifications: Boolean = false,
    stateTransitionHistory: Boolean = false,
    extensions: List[AgentExtension] = Nil
)
object AgentCapabilities:
  val default: AgentCapabilities = AgentCapabilities()

  given JsonEncoder[AgentCapabilities] = DeriveJsonEncoder.gen[AgentCapabilities]
  given JsonDecoder[AgentCapabilities] = DeriveJsonDecoder.gen[AgentCapabilities]

/** Protocol extension declaration */
final case class AgentExtension(
    uri: String,
    description: Option[String] = None,
    params: Option[zio.json.ast.Json] = None,
    required: Boolean = false
)
object AgentExtension:
  given JsonEncoder[AgentExtension] = DeriveJsonEncoder.gen[AgentExtension]
  given JsonDecoder[AgentExtension] = DeriveJsonDecoder.gen[AgentExtension]

/** Alternative interface for agent communication */
final case class AgentInterface(
    transport: A2ATransport,
    url: String
)
object AgentInterface:
  given JsonEncoder[AgentInterface] = DeriveJsonEncoder.gen[AgentInterface]
  given JsonDecoder[AgentInterface] = DeriveJsonDecoder.gen[AgentInterface]

/** Agent skill/capability description */
final case class AgentSkill(
    id: String,
    name: String,
    description: String,
    tags: List[String] = Nil,
    examples: List[String] = Nil,
    inputModes: List[String] = Nil,
    outputModes: List[String] = Nil,
    security: List[SecurityRequirement] = Nil
)
object AgentSkill:
  given JsonEncoder[AgentSkill] = DeriveJsonEncoder.gen[AgentSkill]
  given JsonDecoder[AgentSkill] = DeriveJsonDecoder.gen[AgentSkill]

/** AgentCard JWS signature (RFC 7515) */
final case class AgentCardSignature(
    `protected`: String,
    signature: String,
    header: Option[zio.json.ast.Json] = None
)
object AgentCardSignature:
  given JsonEncoder[AgentCardSignature] = DeriveJsonEncoder.gen[AgentCardSignature]
  given JsonDecoder[AgentCardSignature] = DeriveJsonDecoder.gen[AgentCardSignature]

/** Security requirement (references a security scheme) */
final case class SecurityRequirement(
    scheme: String,
    scopes: List[String] = Nil
)
object SecurityRequirement:
  given JsonEncoder[SecurityRequirement] = DeriveJsonEncoder.gen[SecurityRequirement]
  given JsonDecoder[SecurityRequirement] = DeriveJsonDecoder.gen[SecurityRequirement]

/** Security scheme definition (OpenAPI-style) */
enum SecurityScheme:
  case ApiKey(name: String, in: String) // in: header, query, cookie
  case Http(scheme: String, bearerFormat: Option[String] = None)
  case OAuth2(flows: OAuth2Flows)
  case OpenIdConnect(openIdConnectUrl: String)
  case MutualTLS

object SecurityScheme:
  given JsonEncoder[SecurityScheme] = DeriveJsonEncoder.gen[SecurityScheme]
  given JsonDecoder[SecurityScheme] = DeriveJsonDecoder.gen[SecurityScheme]

/** OAuth2 flows configuration */
final case class OAuth2Flows(
    authorizationCode: Option[OAuth2Flow] = None,
    clientCredentials: Option[OAuth2Flow] = None,
    implicit_ : Option[OAuth2Flow] = None,
    password: Option[OAuth2Flow] = None
)
object OAuth2Flows:
  given JsonEncoder[OAuth2Flows] = DeriveJsonEncoder.gen[OAuth2Flows]
  given JsonDecoder[OAuth2Flows] = DeriveJsonDecoder.gen[OAuth2Flows]

/** Single OAuth2 flow */
final case class OAuth2Flow(
    authorizationUrl: Option[String] = None,
    tokenUrl: Option[String] = None,
    refreshUrl: Option[String] = None,
    scopes: Map[String, String] = Map.empty
)
object OAuth2Flow:
  given JsonEncoder[OAuth2Flow] = DeriveJsonEncoder.gen[OAuth2Flow]
  given JsonDecoder[OAuth2Flow] = DeriveJsonDecoder.gen[OAuth2Flow]
