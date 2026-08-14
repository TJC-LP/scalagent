package com.tjclp.scalagent.a2a

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

class A2ACodecSpec extends FunSuite:
  private def roundTrip[A: JsonEncoder: JsonDecoder](value: A): Unit =
    assertEquals(value.toJson.fromJson[A], Right(value))

  private val contextId = ContextId("ctx-codec")
  private val taskId    = TaskId("task-codec")
  private val message = A2AMessage(
    role = A2ARole.User,
    parts = List(
      Part.Text("hello"),
      Part.File(FileContent.Bytes("cmF3", name = Some("raw.bin"), mimeType = Some("application/octet-stream"))),
      Part.File(FileContent.Uri("https://example.test/file.txt", name = Some("file.txt"), mimeType = Some("text/plain"))),
      Part.Data(Json.Obj("key" -> Json.Str("value"))),
    ),
    messageId = MessageId("msg-codec"),
    contextId = Some(contextId),
    taskId = Some(taskId),
    referenceTaskIds = List(TaskId("task-ref")),
    metadata = Some(Json.Obj("source" -> Json.Str("test"))),
    extensions = List("https://example.test/ext"),
  )
  private val artifact = Artifact(
    artifactId = "artifact-codec",
    parts = List(Part.Text("artifact")),
    name = Some("artifact.txt"),
    description = Some("Artifact"),
    extensions = List("https://example.test/artifact-ext"),
    metadata = Some(Json.Obj("kind" -> Json.Str("text"))),
  )
  private val task = A2ATask(
    id = taskId,
    contextId = contextId,
    status = TaskStatus(TaskState.Completed, Some(message.copy(role = A2ARole.Agent)), Some("2026-05-04T00:00:00Z")),
    artifacts = List(artifact),
    history = List(message),
    metadata = Some(Json.Obj("tenant" -> Json.Str("codec"))),
  )
  private val pushConfig = TaskPushNotificationConfig(
    tenant = Some("tenant-codec"),
    id = Some("push-codec"),
    taskId = Some(taskId),
    url = "https://callback.example.test/a2a",
    token = Some("token"),
    authentication = Some(AuthenticationInfo("Bearer", "secret")),
  )
  private val validSkillJson =
    """{"id":"skill-codec","name":"Codec skill","description":"A valid skill","tags":["test"]}"""

  test("agent card, security schemes, and security requirements round-trip"):
    val card = AgentCard(
      name = "CodecAgent",
      description = "Codec test agent",
      supportedInterfaces = List(AgentInterface.jsonRpc("https://agent.example.test/a2a"), AgentInterface.rest("https://agent.example.test")),
      provider = Some(AgentProvider(url = "https://provider.example.test", organization = "Provider")),
      capabilities = AgentCapabilities(
        streaming = true,
        pushNotifications = true,
        extensions = List(AgentExtension("https://example.test/ext", required = true)),
        extendedAgentCard = true,
      ),
      securitySchemes = Map("bearer" -> SecurityScheme.Http("Bearer", bearerFormat = Some("JWT"))),
      securityRequirements = List(SecurityRequirement(Map("bearer" -> List("tasks:read")))),
      skills = List(AgentSkill("codec", "Codec", "Codec skill", tags = List("test"))),
      signatures = List(AgentCardSignature("protected", "signature")),
      iconUrl = Some("https://agent.example.test/icon.png"),
    )

    roundTrip(card)
    assert(card.toJson.contains("supportedInterfaces"))
    assert(card.toJson.contains("protocolVersion"))

  test("protocol negotiation ignores patch versions"):
    assertEquals(A2AProtocol.negotiationVersion("1.0"), "1.0")
    assertEquals(A2AProtocol.negotiationVersion("1.0.99"), "1.0")
    assertEquals(A2AProtocol.negotiationVersion(" 0.3.12 "), "0.3")
    assertEquals(A2AProtocol.negotiationVersion("1"), "1")
    assertEquals(A2AProtocol.negotiationVersion("1.0.0.1"), "1.0.0.1")

  test("shared enum wire aliases cover proto JSON and JS SDK facade values"):
    assertEquals(TaskState.fromWireValue("TASK_STATE_INPUT_REQUIRED"), Right(TaskState.InputRequired))
    assertEquals(TaskState.fromWireValue("input-required"), Right(TaskState.InputRequired))
    assertEquals(TaskState.AuthRequired.lowerKebabValue, "auth-required")
    assertEquals(A2ARole.fromWireValue("ROLE_AGENT"), Right(A2ARole.Agent))
    assertEquals(A2ARole.fromWireValue("agent"), Right(A2ARole.Agent))
    assertEquals(A2ARole.Unspecified.lowerValue, "unspecified")

  test("task lifecycle helpers include interrupted states in stream-ending states"):
    val inputRequired = task.copy(status = TaskStatus.inputRequired(message.copy(role = A2ARole.Agent)))
    val authRequired  = task.copy(status = TaskStatus.authRequired(message.copy(role = A2ARole.Agent)))
    val working       = task.copy(status = TaskStatus.working())

    assert(!inputRequired.isTerminal)
    assert(inputRequired.isInterrupted)
    assert(inputRequired.isStreamEnding)
    assert(!authRequired.isTerminal)
    assert(authRequired.isInterrupted)
    assert(authRequired.isStreamEnding)
    assert(!working.isTerminal)
    assert(!working.isInterrupted)
    assert(!working.isStreamEnding)

  test("agent card encoder follows ProtoJSON default field presence"):
    val card = AgentCard.minimal("MinimalAgent", "Minimal card", "https://agent.example.test/a2a")
    val json = card.toJson

    assert(json.contains(""""skills":[{"""))
    assert(json.contains(""""tags":["default"]"""))
    assert(!json.contains(""""skills":[]"""))
    assert(json.contains(""""defaultInputModes":["text/plain"]"""))
    assert(json.contains(""""streaming":true"""))
    assert(!json.contains(""""securitySchemes":{}"""))
    assert(!json.contains(""""securityRequirements":[]"""))
    assert(!json.contains(""""signatures":[]"""))
    assert(!json.contains(""""extensions":[]"""))
    assert(!json.contains(""""pushNotifications":false"""))
    assert(!json.contains(""""extendedAgentCard":false"""))
    assertEquals(AgentCapabilities.default.copy(streaming = false).toJson.fromJson[AgentCapabilities], Right(AgentCapabilities.default.copy(streaming = false)))
    assertEquals("{}".fromJson[AgentCapabilities].map(_.streaming), Right(false))

  test("agent interface supports custom protocol bindings and requires v1 binding fields"):
    val customBinding = "https://example.test/bindings/websocket/v1"
    val decoded =
      s"""{
         |  "url": "https://agent.example.test/a2a/ws",
         |  "protocolBinding": "$customBinding",
         |  "tenant": "tenant-a",
         |  "protocolVersion": "1.0"
         |}""".stripMargin.fromJson[AgentInterface]
    val missingBinding =
      """{
        |  "url": "https://agent.example.test/a2a",
        |  "protocolVersion": "1.0"
        |}""".stripMargin.fromJson[AgentInterface]
    val missingVersion =
      """{
        |  "url": "https://agent.example.test/a2a",
        |  "protocolBinding": "JSONRPC"
        |}""".stripMargin.fromJson[AgentInterface]
    val malformedTenant =
      """{
        |  "url": "https://agent.example.test/a2a",
        |  "protocolBinding": "JSONRPC",
        |  "tenant": 5,
        |  "protocolVersion": "1.0"
        |}""".stripMargin.fromJson[AgentInterface]

    assertEquals(
      decoded,
      Right(
        AgentInterface(
          url = "https://agent.example.test/a2a/ws",
          protocolBinding = A2ATransport.Custom(customBinding),
          tenant = Some("tenant-a"),
          protocolVersion = "1.0",
        )
      ),
    )
    assertEquals(decoded.map(_.toJson.fromJson[AgentInterface]), Right(decoded))
    assert(missingBinding.left.exists(_.contains("Missing protocolBinding")))
    assert(missingVersion.left.exists(_.contains("Missing protocolVersion")))
    assert(malformedTenant.left.exists(_.contains("tenant must be a string")))

  test("agent card decoder enforces v1 required fields"):
    val missingInterfaces =
      s"""{
        |  "name": "MissingInterfaces",
        |  "description": "Missing interfaces",
        |  "version": "1.0.0",
        |  "capabilities": {},
        |  "defaultInputModes": ["text/plain"],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": [$validSkillJson]
        |}""".stripMargin.fromJson[AgentCard]
    val missingCapabilities =
      s"""{
        |  "name": "MissingCapabilities",
        |  "description": "Missing capabilities",
        |  "version": "1.0.0",
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://agent.example.test/a2a",
        |      "protocolBinding": "JSONRPC",
        |      "protocolVersion": "1.0"
        |    }
        |  ],
        |  "defaultInputModes": ["text/plain"],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": [$validSkillJson]
        |}""".stripMargin.fromJson[AgentCard]
    val emptyInputModes =
      s"""{
        |  "name": "EmptyInputModes",
        |  "description": "Empty input modes",
        |  "version": "1.0.0",
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://agent.example.test/a2a",
        |      "protocolBinding": "JSONRPC",
        |      "protocolVersion": "1.0"
        |    }
        |  ],
        |  "capabilities": {},
        |  "defaultInputModes": [],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": [$validSkillJson]
        |}""".stripMargin.fromJson[AgentCard]
    val emptySkills =
      """{
        |  "name": "EmptySkills",
        |  "description": "Empty skills",
        |  "version": "1.0.0",
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://agent.example.test/a2a",
        |      "protocolBinding": "JSONRPC",
        |      "protocolVersion": "1.0"
        |    }
        |  ],
        |  "capabilities": {},
        |  "defaultInputModes": ["text/plain"],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": []
        |}""".stripMargin.fromJson[AgentCard]
    val malformedProvider =
      s"""{
        |  "name": "MalformedProvider",
        |  "description": "Malformed provider",
        |  "version": "1.0.0",
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://agent.example.test/a2a",
        |      "protocolBinding": "JSONRPC",
        |      "protocolVersion": "1.0"
        |    }
        |  ],
        |  "provider": {"organization": "Provider"},
        |  "capabilities": {},
        |  "defaultInputModes": ["text/plain"],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": [$validSkillJson]
        |}""".stripMargin.fromJson[AgentCard]
    val malformedDocumentationUrl =
      s"""{
        |  "name": "MalformedDocumentation",
        |  "description": "Malformed documentation URL",
        |  "version": "1.0.0",
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://agent.example.test/a2a",
        |      "protocolBinding": "JSONRPC",
        |      "protocolVersion": "1.0"
        |    }
        |  ],
        |  "documentationUrl": 5,
        |  "capabilities": {},
        |  "defaultInputModes": ["text/plain"],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": [$validSkillJson]
        |}""".stripMargin.fromJson[AgentCard]
    val malformedIconUrl =
      s"""{
        |  "name": "MalformedIcon",
        |  "description": "Malformed icon URL",
        |  "version": "1.0.0",
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://agent.example.test/a2a",
        |      "protocolBinding": "JSONRPC",
        |      "protocolVersion": "1.0"
        |    }
        |  ],
        |  "iconUrl": false,
        |  "capabilities": {},
        |  "defaultInputModes": ["text/plain"],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": [$validSkillJson]
        |}""".stripMargin.fromJson[AgentCard]

    assert(missingInterfaces.left.exists(_.contains("Missing supportedInterfaces")))
    assert(missingCapabilities.left.exists(_.contains("Missing capabilities")))
    assert(emptyInputModes.left.exists(_.contains("defaultInputModes must contain at least one item")))
    assert(emptySkills.left.exists(_.contains("skills must contain at least one item")))
    assert(malformedProvider.left.exists(_.contains("Missing url")))
    assert(malformedDocumentationUrl.left.exists(_.contains("documentationUrl must be a string")))
    assert(malformedIconUrl.left.exists(_.contains("iconUrl must be a string")))

  test("agent card decoder accepts legacy url fallback"):
    val decoded =
      """{
        |  "name": "LegacyAgent",
        |  "description": "Legacy card",
        |  "url": "https://legacy.example.test/a2a"
        |}""".stripMargin.fromJson[AgentCard]

    assertEquals(decoded.map(_.supportedInterfaces.map(_.url)), Right(List("https://legacy.example.test/a2a")))
    assertEquals(decoded.map(_.defaultInputModes), Right(List("text/plain")))

  test("agent extension codec omits default values and rejects malformed declarations"):
    val extension = AgentExtension("https://example.test/ext")
    val required = AgentExtension(
      uri = "https://example.test/required",
      description = "Required extension",
      required = true,
      params = Some(Json.Obj("mode" -> Json.Str("strict"))),
    )
    val missingUri =
      """{
        |  "required": true
        |}""".stripMargin.fromJson[AgentExtension]
    val malformedRequired =
      """{
        |  "uri": "https://example.test/ext",
        |  "required": "yes"
        |}""".stripMargin.fromJson[AgentExtension]
    val malformedParams =
      """{
        |  "uri": "https://example.test/ext",
        |  "params": "not-an-object"
        |}""".stripMargin.fromJson[AgentExtension]

    assertEquals(extension.toJson, """{"uri":"https://example.test/ext"}""")
    assert(required.toJson.contains(""""required":true"""))
    assert(required.toJson.contains(""""params":{"mode":"strict"}"""))
    assert(missingUri.left.exists(_.contains("Missing uri")))
    assert(malformedRequired.left.exists(_.contains("required must be a boolean")))
    assert(malformedParams.left.exists(_.contains("params must be an object")))

  test("agent skill decoder enforces required tags and nested security"):
    val missingTags =
      """{
        |  "id": "skill-missing-tags",
        |  "name": "MissingTags",
        |  "description": "Missing tags"
        |}""".stripMargin.fromJson[AgentSkill]
    val malformedExamples =
      """{
        |  "id": "skill-bad-examples",
        |  "name": "BadExamples",
        |  "description": "Bad examples",
        |  "tags": ["test"],
        |  "examples": "not-an-array"
        |}""".stripMargin.fromJson[AgentSkill]
    val malformedSecurity =
      """{
        |  "id": "skill-bad-security",
        |  "name": "BadSecurity",
        |  "description": "Bad security",
        |  "tags": ["test"],
        |  "securityRequirements": [{"bearer": 5}]
        |}""".stripMargin.fromJson[AgentSkill]

    assert(missingTags.left.exists(_.contains("tags must contain at least one item")))
    assert(malformedExamples.isLeft)
    assert(malformedSecurity.isLeft)

  test("agent skill encoder omits non-required empty repeated fields"):
    val skill = AgentSkill("codec", "Codec", "Codec skill", tags = List("test"))
    val json  = skill.toJson
    val normalized = AgentSkill("untagged", "Untagged", "Untagged skill").toJson

    assert(json.contains(""""tags":["test"]"""))
    assert(!json.contains(""""examples":[]"""))
    assert(!json.contains(""""inputModes":[]"""))
    assert(!json.contains(""""outputModes":[]"""))
    assert(!json.contains(""""securityRequirements":[]"""))
    assert(normalized.contains(""""tags":["untagged"]"""))
    assertEquals(normalized.fromJson[AgentSkill].map(_.tags), Right(List("untagged")))

  test("agent card signature decoder enforces required protected and signature fields"):
    val missingProtected =
      """{
        |  "signature": "sig"
        |}""".stripMargin.fromJson[AgentCardSignature]
    val missingSignature =
      """{
        |  "protected": "protected"
        |}""".stripMargin.fromJson[AgentCardSignature]
    val malformedHeader =
      """{
        |  "protected": "protected",
        |  "signature": "sig",
        |  "header": ["not", "object"]
        |}""".stripMargin.fromJson[AgentCardSignature]

    assert(missingProtected.left.exists(_.contains("Missing protected")))
    assert(missingSignature.left.exists(_.contains("Missing signature")))
    assert(malformedHeader.left.exists(_.contains("header must be an object")))

  test("security requirement decoder rejects malformed scheme maps"):
    val wrapped =
      """{
        |  "schemes": {
        |    "bearer": {"list": ["tasks:read"]}
        |  }
        |}""".stripMargin.fromJson[SecurityRequirement]
    val emptyName =
      """{
        |  "": ["tasks:read"]
        |}""".stripMargin.fromJson[SecurityRequirement]
    val malformedValue =
      """{
        |  "bearer": 5
        |}""".stripMargin.fromJson[SecurityRequirement]

    assertEquals(wrapped, Right(SecurityRequirement(Map("bearer" -> List("tasks:read")))))
    assert(emptyName.left.exists(_.contains("scheme name must be non-empty")))
    assert(malformedValue.isLeft)

  test("security scheme decoder enforces proto oneof exclusivity"):
    val decoded =
      """{
        |  "apiKeySecurityScheme": {
        |    "location": "header",
        |    "name": "x-api-key"
        |  },
        |  "httpAuthSecurityScheme": {
        |    "scheme": "Bearer"
        |  }
        |}""".stripMargin.fromJson[SecurityScheme]

    assert(decoded.left.exists(_.contains("exactly one")))

  test("security scheme decoder accepts SDK OpenAPI-style security schemes"):
    val apiKey =
      """{
        |  "type": "apiKey",
        |  "in": "header",
        |  "name": "x-api-key",
        |  "description": "API key auth"
        |}""".stripMargin.fromJson[SecurityScheme]
    val http =
      """{
        |  "type": "http",
        |  "scheme": "Bearer",
        |  "bearerFormat": "JWT",
        |  "description": "Bearer auth"
        |}""".stripMargin.fromJson[SecurityScheme]
    val oauth =
      """{
        |  "type": "oauth2",
        |  "description": "OAuth auth",
        |  "oauth2MetadataUrl": "https://auth.example.test/.well-known/oauth-authorization-server",
        |  "flows": {
        |    "authorizationCode": {
        |      "authorizationUrl": "https://auth.example.test/authorize",
        |      "tokenUrl": "https://auth.example.test/token",
        |      "scopes": {"tasks:read": "Read tasks"},
        |      "pkceRequired": true
        |    }
        |  }
        |}""".stripMargin.fromJson[SecurityScheme]
    val oidc =
      """{
        |  "type": "openIdConnect",
        |  "openIdConnectUrl": "https://accounts.example.test/.well-known/openid-configuration",
        |  "description": "OIDC auth"
        |}""".stripMargin.fromJson[SecurityScheme]
    val mtls =
      """{
        |  "type": "mutualTLS",
        |  "description": "mTLS auth"
        |}""".stripMargin.fromJson[SecurityScheme]

    assertEquals(apiKey, Right(SecurityScheme.ApiKey("x-api-key", "header", "API key auth")))
    assertEquals(http, Right(SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth")))
    assertEquals(
      oauth,
      Right(
        SecurityScheme.OAuth2(
          OAuth2Flows(
            authorizationCode = Some(
              OAuth2Flow(
                authorizationUrl = Some("https://auth.example.test/authorize"),
                tokenUrl = Some("https://auth.example.test/token"),
                scopes = Map("tasks:read" -> "Read tasks"),
                pkceRequired = true,
              )
            )
          ),
          oauth2MetadataUrl = Some("https://auth.example.test/.well-known/oauth-authorization-server"),
          description = "OAuth auth",
        )
      ),
    )
    assertEquals(
      oidc,
      Right(
        SecurityScheme.OpenIdConnect(
          "https://accounts.example.test/.well-known/openid-configuration",
          "OIDC auth",
        )
      ),
    )
    assertEquals(mtls, Right(SecurityScheme.MutualTLS("mTLS auth")))

  test("security scheme OpenAPI JSON helper emits SDK facade shape"):
    val json = SecurityScheme
      .toOpenApiJson(SecurityScheme.Http("Bearer", bearerFormat = Some("JWT"), description = "Bearer auth"))
      .toString

    assert(json.contains(""""type":"http""""))
    assert(json.contains(""""scheme":"Bearer""""))
    assert(json.contains(""""bearerFormat":"JWT""""))
    assert(json.contains(""""description":"Bearer auth""""))
    assert(!json.contains("httpAuthSecurityScheme"))

  test("security scheme decoder rejects malformed optional string fields"):
    val apiKeyDescription =
      """{
        |  "apiKeySecurityScheme": {
        |    "location": "header",
        |    "name": "x-api-key",
        |    "description": 5
        |  }
        |}""".stripMargin.fromJson[SecurityScheme]
    val bearerFormat =
      """{
        |  "httpAuthSecurityScheme": {
        |    "scheme": "Bearer",
        |    "bearerFormat": false
        |  }
        |}""".stripMargin.fromJson[SecurityScheme]
    val mtlsDescription =
      """{
        |  "mtlsSecurityScheme": {
        |    "description": []
        |  }
        |}""".stripMargin.fromJson[SecurityScheme]

    assert(apiKeyDescription.left.exists(_.contains("description must be a string")))
    assert(bearerFormat.left.exists(_.contains("bearerFormat must be a string")))
    assert(mtlsDescription.left.exists(_.contains("description must be a string")))

  test("security scheme encoders omit default empty optional strings"):
    val apiKey: SecurityScheme = SecurityScheme.ApiKey("x-api-key", "header")
    val http: SecurityScheme   = SecurityScheme.Http("Bearer")
    val mtls: SecurityScheme   = SecurityScheme.MutualTLS()
    val apiKeyJson             = apiKey.toJson
    val httpJson               = http.toJson
    val mtlsJson               = mtls.toJson

    assert(!apiKeyJson.contains(""""description""""))
    assert(!httpJson.contains(""""description""""))
    assert(!httpJson.contains(""""bearerFormat""""))
    assertEquals(mtlsJson, """{"mtlsSecurityScheme":{}}""")

  test("oauth2 flows emit current proto json names"):
    val implicitFlows = OAuth2Flows(
      implicit_ = Some(
        OAuth2Flow(
          authorizationUrl = Some("https://auth.example.test/implicit"),
          refreshUrl = Some("https://auth.example.test/refresh"),
          scopes = Map("profile" -> "Profile"),
        )
      )
    )
    val deviceFlows = OAuth2Flows(
      deviceCode = Some(
        OAuth2Flow(
          deviceAuthorizationUrl = Some("https://auth.example.test/device"),
          tokenUrl = Some("https://auth.example.test/token"),
          scopes = Map("device" -> "Device"),
        )
      )
    )
    val implicitJson = implicitFlows.toJson
    val deviceJson   = deviceFlows.toJson

    assert(implicitJson.contains(""""implicit""""))
    assert(!implicitJson.contains(""""implicit_""""))
    assert(deviceJson.contains(""""deviceCode""""))
    assert(deviceJson.contains(""""deviceAuthorizationUrl""""))
    assertEquals(implicitJson.fromJson[OAuth2Flows], Right(implicitFlows))
    assertEquals(deviceJson.fromJson[OAuth2Flows], Right(deviceFlows))

  test("oauth2 flows accept legacy and snake-case field aliases"):
    val authorizationCode =
      """{
        |  "authorization_code": {
        |    "authorization_url": "https://auth.example.test/authorize",
        |    "token_url": "https://auth.example.test/token",
        |    "pkce_required": true,
        |    "scopes": {"tasks:read": "Read tasks"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]
    val implicitFlow =
      """{
        |  "implicit_": {
        |    "authorization_url": "https://auth.example.test/implicit",
        |    "scopes": {"profile": "Profile"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]
    val deviceCode =
      """{
        |  "device_code": {
        |    "device_authorization_url": "https://auth.example.test/device",
        |    "token_url": "https://auth.example.test/device-token",
        |    "scopes": {"device": "Device"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]

    assert(authorizationCode.exists(_.authorizationCode.exists(_.pkceRequired)))
    assertEquals(
      implicitFlow.flatMap(_.implicit_.flatMap(_.authorizationUrl).toRight("missing implicit")),
      Right("https://auth.example.test/implicit"),
    )
    assertEquals(
      deviceCode.flatMap(_.deviceCode.flatMap(_.deviceAuthorizationUrl).toRight("missing device")),
      Right("https://auth.example.test/device"),
    )

  test("oauth2 flow decoder enforces proto oneof exclusivity"):
    val decoded =
      """{
        |  "authorizationCode": {
        |    "authorizationUrl": "https://auth.example.test/authorize",
        |    "tokenUrl": "https://auth.example.test/token"
        |  },
        |  "deviceCode": {
        |    "deviceAuthorizationUrl": "https://auth.example.test/device",
        |    "tokenUrl": "https://auth.example.test/device-token"
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]

    assert(decoded.left.exists(_.contains("exactly one")))

  test("oauth2 flow decoder enforces flow-specific required fields"):
    val missingScopes =
      """{
        |  "authorizationCode": {
        |    "authorizationUrl": "https://auth.example.test/authorize",
        |    "tokenUrl": "https://auth.example.test/token"
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]
    val missingDeviceAuthorization =
      """{
        |  "deviceCode": {
        |    "tokenUrl": "https://auth.example.test/device-token",
        |    "scopes": {"device": "Device"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]
    val wrongTokenType =
      """{
        |  "clientCredentials": {
        |    "tokenUrl": 5,
        |    "scopes": {"tasks:write": "Write tasks"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]
    val emptyAuthorizationUrl =
      """{
        |  "authorizationCode": {
        |    "authorizationUrl": "",
        |    "tokenUrl": "https://auth.example.test/token",
        |    "scopes": {"tasks:read": "Read tasks"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]
    val emptyClientCredentialsTokenUrl =
      """{
        |  "clientCredentials": {
        |    "tokenUrl": "",
        |    "scopes": {"tasks:write": "Write tasks"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]
    val emptyDeviceAuthorizationUrl =
      """{
        |  "deviceCode": {
        |    "deviceAuthorizationUrl": "",
        |    "tokenUrl": "https://auth.example.test/device-token",
        |    "scopes": {"device": "Device"}
        |  }
        |}""".stripMargin.fromJson[OAuth2Flows]

    assert(missingScopes.left.exists(_.contains("authorizationCode flow missing required field(s): scopes")))
    assert(missingDeviceAuthorization.left.exists(_.contains("deviceCode flow missing required field(s): deviceAuthorizationUrl")))
    assert(wrongTokenType.left.exists(_.contains("tokenUrl must be a string")))
    assert(emptyAuthorizationUrl.left.exists(_.contains("authorizationCode flow missing required field(s): authorizationUrl")))
    assert(emptyClientCredentialsTokenUrl.left.exists(_.contains("clientCredentials flow missing required field(s): tokenUrl")))
    assert(emptyDeviceAuthorizationUrl.left.exists(_.contains("deviceCode flow missing required field(s): deviceAuthorizationUrl")))

  test("oauth2 flow encoder omits absent optional and default fields"):
    val flow = OAuth2Flow(tokenUrl = Some("https://auth.example.test/token"))
    val json = flow.toJson

    assert(json.contains(""""tokenUrl":"https://auth.example.test/token""""))
    assert(!json.contains(""""authorizationUrl""""))
    assert(!json.contains(""""refreshUrl""""))
    assert(!json.contains(""""scopes":{}"""))
    assert(!json.contains(""""pkceRequired":false"""))
    assert(!json.contains("null"))

  test("message parts use v1 text/raw/url/data fields"):
    roundTrip(message)
    val json = message.toJson
    assert(json.contains(""""text":"hello""""))
    assert(json.contains(""""raw":"cmF3""""))
    assert(json.contains(""""url":"https://example.test/file.txt""""))
    assert(json.contains(""""data":{"key":"value"}"""))

  test("role codec covers every proto enum value"):
    assertEquals(A2ARole.Unspecified.toJson, """"ROLE_UNSPECIFIED"""")
    assertEquals(A2ARole.User.toJson, """"ROLE_USER"""")
    assertEquals(A2ARole.Agent.toJson, """"ROLE_AGENT"""")
    assertEquals(""""ROLE_UNSPECIFIED"""".fromJson[A2ARole], Right(A2ARole.Unspecified))

  test("text and data parts do not invent absent proto mediaType fields"):
    val textPart: Part = Part.Text("hello")
    val dataPart: Part = Part.Data(Json.Obj("key" -> Json.Str("value")))
    val textJson       = textPart.toJson
    val dataJson       = dataPart.toJson

    assert(!textJson.contains(""""mediaType""""))
    assert(!dataJson.contains(""""mediaType""""))
    assertEquals("""{"text":"hello"}""".fromJson[Part], Right(Part.Text("hello")))
    assertEquals("""{"data":{"key":"value"}}""".fromJson[Part], Right(Part.Data(Json.Obj("key" -> Json.Str("value")))))

  test("text and data parts preserve proto filename and mediaType fields"):
    val textPart: Part = Part.Text(
      "hello",
      filename = Some("note.md"),
      mediaType = Some("text/markdown"),
    )
    val dataPart: Part = Part.Data(
      Json.Obj("kind" -> Json.Str("payload")),
      filename = Some("payload.json"),
      mediaType = Some("application/vnd.example+json"),
    )

    assertEquals(textPart.toJson.fromJson[Part], Right(textPart))
    assertEquals(dataPart.toJson.fromJson[Part], Right(dataPart))
    assert(textPart.toJson.contains(""""filename":"note.md""""))
    assert(textPart.toJson.contains(""""mediaType":"text/markdown""""))
    assert(dataPart.toJson.contains(""""filename":"payload.json""""))
    assert(dataPart.toJson.contains(""""mediaType":"application/vnd.example+json""""))

  test("part decoder accepts snake-case media_type"):
    val decoded =
      """{
        |  "data": {"kind": "payload"},
        |  "filename": "payload.json",
        |  "media_type": "application/vnd.example+json"
        |}""".stripMargin.fromJson[Part]

    assertEquals(
      decoded,
      Right(
        Part.Data(
          Json.Obj("kind" -> Json.Str("payload")),
          filename = Some("payload.json"),
          mediaType = Some("application/vnd.example+json"),
        )
      ),
    )

  test("part and legacy file decoders reject malformed optional strings"):
    val malformedFilename =
      """{
        |  "text": "hello",
        |  "filename": 5
        |}""".stripMargin.fromJson[Part]
    val malformedMediaType =
      """{
        |  "data": {"kind": "payload"},
        |  "mediaType": false
        |}""".stripMargin.fromJson[Part]
    val malformedFileName =
      """{
        |  "bytes": "cmF3",
        |  "name": 5
        |}""".stripMargin.fromJson[FileContent]
    val fileOneof =
      """{
        |  "bytes": "cmF3",
        |  "uri": "https://example.test/file.txt"
        |}""".stripMargin.fromJson[FileContent]

    assert(malformedFilename.left.exists(_.contains("filename must be a string")))
    assert(malformedMediaType.left.exists(_.contains("mediaType must be a string")))
    assert(malformedFileName.left.exists(_.contains("name must be a string")))
    assert(fileOneof.left.exists(_.contains("exactly one")))

  test("raw byte decoders require base64 strings"):
    val invalidRaw =
      """{
        |  "raw": "not-base64!"
        |}""".stripMargin.fromJson[Part]
    val invalidRawWhitespace =
      """{
        |  "raw": "cm F3"
        |}""".stripMargin.fromJson[Part]
    val invalidLegacyBytes =
      """{
        |  "bytes": "not-base64!"
        |}""".stripMargin.fromJson[FileContent]
    val unpaddedRaw =
      """{
        |  "raw": "aGk"
        |}""".stripMargin.fromJson[Part]
    val emptyRaw =
      """{
        |  "raw": ""
        |}""".stripMargin.fromJson[Part]
    val urlSafeLegacyBytes =
      """{
        |  "bytes": "_w"
        |}""".stripMargin.fromJson[FileContent]

    assert(invalidRaw.left.exists(_.contains("raw must be base64-encoded bytes")))
    assert(invalidRawWhitespace.left.exists(_.contains("raw must be base64-encoded bytes")))
    assert(invalidLegacyBytes.left.exists(_.contains("bytes must be base64-encoded bytes")))
    assertEquals(unpaddedRaw, Right(Part.File(FileContent.Bytes("aGk"))))
    assertEquals(emptyRaw, Right(Part.File(FileContent.Bytes(""))))
    assertEquals(urlSafeLegacyBytes, Right(FileContent.Bytes("_w")))

  test("part decoder rejects multiple proto oneof content fields"):
    val decoded =
      """{
        |  "text": "hello",
        |  "data": {"also": "present"}
        |}""".stripMargin.fromJson[Part]

    assert(decoded.left.exists(_.contains("exactly one of text, raw, url, or data")))

  test("task status and state enum values use proto names"):
    roundTrip(task)
    assert(task.toJson.contains("TASK_STATE_COMPLETED"))

  test("task status decoder enforces UTC proto timestamps"):
    val offsetTimestamp =
      """{
        |  "state": "TASK_STATE_WORKING",
        |  "timestamp": "2026-01-01T00:00:00-05:00"
        |}""".stripMargin.fromJson[TaskStatus]
    val malformedTimestamp =
      """{
        |  "state": "TASK_STATE_WORKING",
        |  "timestamp": "not-a-timestamp"
        |}""".stripMargin.fromJson[TaskStatus]
    val utcTimestamp =
      """{
        |  "state": "TASK_STATE_WORKING",
        |  "timestamp": "2026-01-01T00:00:00.000Z"
        |}""".stripMargin.fromJson[TaskStatus]

    assert(offsetTimestamp.left.exists(_.contains("timestamp must be an ISO 8601 UTC timestamp")))
    assert(malformedTimestamp.left.exists(_.contains("timestamp must be an ISO 8601 UTC timestamp")))
    assertEquals(utcTimestamp.map(_.timestamp), Right(Some("2026-01-01T00:00:00.000Z")))

  test("task status decoder rejects malformed nested messages"):
    val malformedMessage =
      """{
        |  "state": "TASK_STATE_WORKING",
        |  "message": {"role": "ROLE_AGENT", "parts": []}
        |}""".stripMargin.fromJson[TaskStatus]

    assert(malformedMessage.left.exists(_.contains("parts must contain at least one part")))

  test("task decoder accepts omitted optional context and repeated fields"):
    val decoded =
      """{
        |  "id": "task-no-context",
        |  "status": {"state": "TASK_STATE_WORKING"}
        |}""".stripMargin.fromJson[A2ATask]

    assertEquals(decoded.map(task => (task.contextId, task.artifacts, task.history)), Right((ContextId("task-no-context"), Nil, Nil)))

  test("task decoder rejects malformed optional context and repeated fields"):
    val malformedContext =
      """{
        |  "id": "task-bad-context",
        |  "contextId": 5,
        |  "status": {"state": "TASK_STATE_WORKING"}
        |}""".stripMargin.fromJson[A2ATask]
    val malformedArtifacts =
      """{
        |  "id": "task-bad-artifacts",
        |  "status": {"state": "TASK_STATE_WORKING"},
        |  "artifacts": {}
        |}""".stripMargin.fromJson[A2ATask]
    val malformedHistoryEntry =
      """{
        |  "id": "task-bad-history",
        |  "status": {"state": "TASK_STATE_WORKING"},
        |  "history": [{"role": "ROLE_USER", "parts": []}]
        |}""".stripMargin.fromJson[A2ATask]

    assert(malformedContext.left.exists(_.contains("contextId must be a string")))
    assert(malformedArtifacts.left.exists(_.contains("artifacts must be an array")))
    assert(malformedHistoryEntry.left.exists(_.contains("parts must contain at least one part")))

  test("core payload encoders omit absent optional fields"):
    val artifactJson  = Artifact("artifact-min", List(Part.Text("hello"))).toJson
    val statusJson    = TaskStatus(TaskState.Working).toJson
    val authJson      = AuthenticationInfo("Bearer").toJson
    val signatureJson = AgentCardSignature("protected", "signature").toJson

    assert(!artifactJson.contains(""""name""""))
    assert(!artifactJson.contains(""""description""""))
    assert(!artifactJson.contains(""""extensions":[]"""))
    assert(!artifactJson.contains(""""metadata""""))
    assert(!statusJson.contains(""""message""""))
    assert(!statusJson.contains(""""timestamp""""))
    assert(!authJson.contains(""""credentials""""))
    assert(!signatureJson.contains(""""header""""))
    assert(!List(artifactJson, statusJson, authJson, signatureJson).exists(_.contains("null")))

  test("message decoder enforces required proto fields"):
    val missingMessageId =
      """{
        |  "role": "ROLE_USER",
        |  "parts": [{"text": "hello"}]
        |}""".stripMargin.fromJson[A2AMessage]
    val emptyParts =
      """{
        |  "messageId": "msg-empty",
        |  "role": "ROLE_USER",
        |  "parts": []
        |}""".stripMargin.fromJson[A2AMessage]
    val malformedParts =
      """{
        |  "messageId": "msg-bad-parts",
        |  "role": "ROLE_USER",
        |  "parts": {}
        |}""".stripMargin.fromJson[A2AMessage]
    val unspecifiedRole =
      """{
        |  "messageId": "msg-unspecified-role",
        |  "role": "ROLE_UNSPECIFIED",
        |  "parts": [{"text": "hello"}]
        |}""".stripMargin.fromJson[A2AMessage]

    assert(missingMessageId.left.exists(_.contains("Missing messageId")))
    assert(emptyParts.left.exists(_.contains("parts must contain at least one part")))
    assert(malformedParts.left.exists(_.contains("parts must be an array")))
    assert(unspecifiedRole.left.exists(_.contains("role must be one of")))

  test("message decoder rejects malformed optional string arrays"):
    val malformedReferences =
      """{
        |  "messageId": "msg-bad-refs",
        |  "role": "ROLE_USER",
        |  "parts": [{"text": "hello"}],
        |  "referenceTaskIds": "task-ref"
        |}""".stripMargin.fromJson[A2AMessage]
    val malformedExtensionEntry =
      """{
        |  "messageId": "msg-bad-ext",
        |  "role": "ROLE_USER",
        |  "parts": [{"text": "hello"}],
        |  "extensions": [5]
        |}""".stripMargin.fromJson[A2AMessage]
    val malformedArtifactExtensions =
      """{
        |  "artifactId": "artifact-bad-ext",
        |  "parts": [{"text": "hello"}],
        |  "extensions": [false]
        |}""".stripMargin.fromJson[Artifact]

    assert(malformedReferences.left.exists(_.contains("referenceTaskIds must be an array")))
    assert(malformedExtensionEntry.left.exists(_.contains("extensions[0] must be a string")))
    assert(malformedArtifactExtensions.left.exists(_.contains("extensions[0] must be a string")))

  test("artifact decoder enforces required non-empty parts"):
    val missingParts =
      """{
        |  "artifactId": "artifact-missing"
      |}""".stripMargin.fromJson[Artifact]
    val emptyParts =
      """{
        |  "artifactId": "artifact-empty",
        |  "parts": []
      |}""".stripMargin.fromJson[Artifact]
    val malformedParts =
      """{
        |  "artifactId": "artifact-bad-parts",
        |  "parts": {}
      |}""".stripMargin.fromJson[Artifact]

    assert(missingParts.left.exists(_.contains("Missing parts")))
    assert(emptyParts.left.exists(_.contains("parts must contain at least one part")))
    assert(malformedParts.left.exists(_.contains("parts must be an array")))

  test("protobuf Struct fields reject non-object values"):
    val messageMetadata =
      """{
        |  "messageId": "msg-bad-metadata",
        |  "role": "ROLE_USER",
        |  "parts": [{"text": "hello"}],
        |  "metadata": "not-an-object"
        |}""".stripMargin.fromJson[A2AMessage]
    val partMetadata =
      """{
        |  "text": "hello",
        |  "metadata": []
        |}""".stripMargin.fromJson[Part]
    val artifactMetadata =
      """{
        |  "artifactId": "artifact-bad-metadata",
        |  "parts": [{"text": "hello"}],
        |  "metadata": true
        |}""".stripMargin.fromJson[Artifact]
    val taskMetadata =
      """{
        |  "id": "task-bad-metadata",
        |  "status": {"state": "TASK_STATE_WORKING"},
        |  "metadata": "not-an-object"
        |}""".stripMargin.fromJson[A2ATask]
    val sendMetadata =
      s"""{
         |  "message": ${message.toJson},
         |  "metadata": "not-an-object"
         |}""".stripMargin.fromJson[A2ARequest.MessageSend]
    val cancelMetadata =
      """{
        |  "id": "task-bad-metadata",
        |  "metadata": []
        |}""".stripMargin.fromJson[A2ARequest.TasksCancel]
    val streamMetadata =
      """{
        |  "statusUpdate": {
        |    "taskId": "task-codec",
        |    "contextId": "ctx-codec",
        |    "status": {"state": "TASK_STATE_WORKING"},
        |    "metadata": 5
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assert(messageMetadata.left.exists(_.contains("metadata must be an object")))
    assert(partMetadata.left.exists(_.contains("metadata must be an object")))
    assert(artifactMetadata.left.exists(_.contains("metadata must be an object")))
    assert(taskMetadata.left.exists(_.contains("metadata must be an object")))
    assert(sendMetadata.left.exists(_.contains("metadata must be an object")))
    assert(cancelMetadata.left.exists(_.contains("metadata must be an object")))
    assert(streamMetadata.left.exists(_.contains("metadata must be an object")))

  test("request and response model groups round-trip"):
    roundTrip(A2ARequest.MessageSend(message, Some(MessageSendConfiguration(taskPushNotificationConfig = Some(pushConfig), historyLength = Some(1)))))
    roundTrip(A2ARequest.TasksGet(taskId, historyLength = Some(1), includeArtifacts = Some(false), tenant = Some("tenant-codec")))
    roundTrip(A2ARequest.TasksList(contextId = Some(contextId), status = Some(TaskState.Completed), pageSize = Some(10), pageToken = Some("10")))
    roundTrip(A2ARequest.TasksCancel(taskId))
    roundTrip(A2ARequest.TasksResubscribe(taskId))
    roundTrip(A2ARequest.PushNotificationConfigGet(taskId, "push-codec"))
    roundTrip(A2ARequest.PushNotificationConfigList(taskId, pageSize = Some(10), pageToken = Some("next")))
    roundTrip(A2ARequest.PushNotificationConfigDelete(taskId, "push-codec"))
    roundTrip(A2AResponse.SendMessageResult.TaskResult(task))
    roundTrip(A2AResponse.ListTasksResult(List(task), nextPageToken = Some("next"), pageSize = 1, totalSize = 1))
    roundTrip(A2AResponse.PushNotificationConfigListResult(List(pushConfig), nextPageToken = Some("next")))

  test("request encoders omit absent optional fields and preserve optional false presence"):
    val bareMessage     = message.copy(metadata = None)
    val messageSendJson = A2ARequest.MessageSend(bareMessage).toJson
    val getJson         = A2ARequest.TasksGet(taskId).toJson
    val getFalseJson    = A2ARequest.TasksGet(taskId, includeArtifacts = Some(false)).toJson
    val listEmptyJson   = A2ARequest.TasksList().toJson
    val listFalseJson   = A2ARequest.TasksList(includeArtifacts = Some(false)).toJson
    val extendedJson    = A2ARequest.GetAuthenticatedExtendedCard().toJson

    assert(!messageSendJson.contains(""""configuration""""))
    assert(!messageSendJson.contains(""""metadata""""))
    assert(!messageSendJson.contains(""""tenant""""))
    assertEquals(getJson, """{"id":"task-codec"}""")
    assertEquals(listEmptyJson, "{}")
    assertEquals(extendedJson, "{}")
    assert(listFalseJson.contains(""""includeArtifacts":false"""))
    assert(getFalseJson.contains(""""includeArtifacts":false"""))
    assert(!List(messageSendJson, getJson, getFalseJson, listEmptyJson, listFalseJson, extendedJson).exists(_.contains("null")))

  test("ProtoJSON null optional fields decode as unset"):
    val cardJson =
      s"""{
        |  "name": "NullOptionalAgent",
        |  "description": "Null optional fields",
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://agent.example.test/a2a",
        |      "protocolBinding": "JSONRPC",
        |      "tenant": null,
        |      "protocolVersion": "1.0"
        |    }
        |  ],
        |  "version": "1.0.0",
        |  "provider": null,
        |  "documentationUrl": null,
        |  "capabilities": {
        |    "streaming": null,
        |    "pushNotifications": null,
        |    "extensions": null,
        |    "extendedAgentCard": null
        |  },
        |  "securitySchemes": null,
        |  "securityRequirements": null,
        |  "defaultInputModes": ["text/plain"],
        |  "defaultOutputModes": ["text/plain"],
        |  "skills": [$validSkillJson],
        |  "signatures": null,
        |  "iconUrl": null
        |}""".stripMargin.fromJson[AgentCard]

    val requestJson =
      """{
        |  "message": {
        |    "messageId": "msg-null",
        |    "role": "ROLE_USER",
        |    "parts": [
        |      {
        |        "text": "hello",
        |        "metadata": null,
        |        "filename": null,
        |        "mediaType": null
        |      }
        |    ],
        |    "contextId": null,
        |    "taskId": null,
        |    "referenceTaskIds": null,
        |    "metadata": null,
        |    "extensions": null
        |  },
        |  "configuration": {
        |    "acceptedOutputModes": null,
        |    "taskPushNotificationConfig": null,
        |    "historyLength": null,
        |    "returnImmediately": null
        |  },
        |  "metadata": null,
        |  "tenant": null
        |}""".stripMargin.fromJson[A2ARequest.MessageSend]

    val taskJson =
      """{
        |  "id": "task-null",
        |  "contextId": null,
        |  "status": {
        |    "state": "TASK_STATE_WORKING",
        |    "message": null,
        |    "timestamp": null
        |  },
        |  "artifacts": null,
        |  "history": null,
        |  "metadata": null
        |}""".stripMargin.fromJson[A2ATask]

    val listRequestJson =
      """{
        |  "contextId": null,
        |  "status": null,
        |  "pageToken": null,
        |  "includeArtifacts": null,
        |  "tenant": null
        |}""".stripMargin.fromJson[A2ARequest.TasksList]

    val pushConfigJson =
      """{
        |  "url": "https://callback.example.test/a2a",
        |  "tenant": null,
        |  "id": null,
        |  "taskId": null,
        |  "token": null,
        |  "authentication": null
        |}""".stripMargin.fromJson[TaskPushNotificationConfig]

    val taskPayload = task.toJsonAST.toOption.get
    val sendResultJson = Json
      .Obj("message" -> Json.Null, "task" -> taskPayload)
      .toJson
      .fromJson[A2AResponse.SendMessageResult]
    val streamEventJson = Json
      .Obj("message" -> Json.Null, "task" -> taskPayload)
      .toJson
      .fromJson[A2AResponse.StreamEvent]
    val fileContentJson =
      """{
        |  "bytes": null,
        |  "uri": "https://example.test/file.txt"
        |}""".stripMargin.fromJson[FileContent]
    val dataNullPartJson = """{"data":null}""".fromJson[Part]
    val securitySchemeJson =
      """{
        |  "type": "http",
        |  "scheme": "bearer",
        |  "httpAuthSecurityScheme": null
        |}""".stripMargin.fromJson[SecurityScheme]
    val errorJson =
      """{
        |  "code": -32602,
        |  "message": "Invalid params",
        |  "data": null
        |}""".stripMargin.fromJson[A2AError]

    assertEquals(cardJson.map(_.capabilities), Right(AgentCapabilities(streaming = false)))
    assertEquals(cardJson.map(_.provider), Right(None))
    assertEquals(cardJson.map(_.supportedInterfaces.head.tenant), Right(None))
    assertEquals(cardJson.map(_.securitySchemes), Right(Map.empty))
    assertEquals(cardJson.map(_.securityRequirements), Right(Nil))
    assertEquals(requestJson.map(_.tenant), Right(None))
    assertEquals(requestJson.map(_.configuration.exists(_.returnImmediately)), Right(false))
    assertEquals(requestJson.map(_.configuration.toList.flatMap(_.acceptedOutputModes)), Right(Nil))
    assertEquals(requestJson.map(_.message.contextId), Right(None))
    assertEquals(taskJson.map(task => (task.contextId, task.status.message, task.status.timestamp)), Right((ContextId("task-null"), None, None)))
    assertEquals(taskJson.map(task => (task.artifacts, task.history, task.metadata)), Right((Nil, Nil, None)))
    assertEquals(listRequestJson.map(request => (request.status, request.includeArtifacts, request.tenant)), Right((None, None, None)))
    assertEquals(pushConfigJson.map(_.authentication), Right(None))
    assertEquals(sendResultJson, Right(A2AResponse.SendMessageResult.TaskResult(task)))
    assertEquals(streamEventJson, Right(A2AResponse.StreamEvent.TaskSnapshot(task)))
    assertEquals(fileContentJson, Right(FileContent.Uri("https://example.test/file.txt")))
    assertEquals(dataNullPartJson, Right(Part.Data(Json.Null)))
    assertEquals(securitySchemeJson, Right(SecurityScheme.Http("bearer")))
    assertEquals(errorJson.map(_.data), Right(None))

  test("edge A2A encoders omit absent optional fields"):
    val providerJson   = AgentProvider("https://provider.example.test", "Provider").toJson
    val extendedJson   = A2ARequest.GetAuthenticatedExtendedCard().toJson
    val transitionJson = StateTransition(TaskState.Working, "2026-05-04T00:00:00Z").toJson
    val pushAuthJson   = PushNotificationAuth(List("Bearer")).toJson
    val errorJson      = A2AError.invalidRequest("bad").toJson

    assertEquals(providerJson, """{"url":"https://provider.example.test","organization":"Provider"}""")
    assertEquals(extendedJson, "{}")
    assert(!transitionJson.contains(""""message""""))
    assert(!pushAuthJson.contains(""""credentials""""))
    assert(!errorJson.contains(""""data""""))
    assert(!List(providerJson, extendedJson, transitionJson, pushAuthJson, errorJson).exists(_.contains("null")))

  test("edge A2A decoders reject malformed optional and scalar fields"):
    val malformedExtendedTenant =
      """{
        |  "tenant": 5
        |}""".stripMargin.fromJson[A2ARequest.GetAuthenticatedExtendedCard]
    val malformedProvider =
      """{
        |  "url": 5,
        |  "organization": "Provider"
        |}""".stripMargin.fromJson[AgentProvider]
    val malformedTransitionTimestamp =
      """{
        |  "state": "TASK_STATE_WORKING",
        |  "timestamp": 5
        |}""".stripMargin.fromJson[StateTransition]
    val malformedPushAuthScheme =
      """{
        |  "schemes": ["Bearer", 5]
        |}""".stripMargin.fromJson[PushNotificationAuth]
    val malformedA2AErrorCode =
      """{
        |  "code": -32600.5,
        |  "message": "bad"
        |}""".stripMargin.fromJson[A2AError]
    val malformedA2AErrorMessage =
      """{
        |  "code": -32600,
        |  "message": 5
        |}""".stripMargin.fromJson[A2AError]

    assert(malformedExtendedTenant.left.exists(_.contains("tenant must be a string")))
    assert(malformedProvider.left.exists(_.contains("url must be a string")))
    assert(malformedTransitionTimestamp.left.exists(_.contains("timestamp must be a string")))
    assert(malformedPushAuthScheme.left.exists(_.contains("schemes[1] must be a string")))
    assert(malformedA2AErrorCode.left.exists(_.contains("code must be an int32")))
    assert(malformedA2AErrorMessage.left.exists(_.contains("message must be a string")))

  test("push notification config decoder enforces url and authentication shape"):
    val missingUrl =
      """{
        |  "id": "push-missing-url"
        |}""".stripMargin.fromJson[TaskPushNotificationConfig]
    val emptyUrl =
      """{
        |  "url": ""
        |}""".stripMargin.fromJson[TaskPushNotificationConfig]
    val missingScheme =
      """{
        |  "url": "https://callback.example.test/a2a",
        |  "authentication": {"credentials": "secret"}
        |}""".stripMargin.fromJson[TaskPushNotificationConfig]
    val malformedCredentials =
      """{
        |  "url": "https://callback.example.test/a2a",
        |  "authentication": {"scheme": "Bearer", "credentials": 5}
        |}""".stripMargin.fromJson[TaskPushNotificationConfig]
    val malformedTenant =
      """{
        |  "url": "https://callback.example.test/a2a",
        |  "tenant": 5
        |}""".stripMargin.fromJson[TaskPushNotificationConfig]
    val malformedTaskId =
      """{
        |  "url": "https://callback.example.test/a2a",
        |  "taskId": false
        |}""".stripMargin.fromJson[TaskPushNotificationConfig]

    assert(missingUrl.left.exists(_.contains("Missing url")))
    assert(emptyUrl.left.exists(_.contains("Missing url")))
    assert(missingScheme.left.exists(_.contains("Missing scheme")))
    assert(malformedCredentials.left.exists(_.contains("credentials must be a string")))
    assert(malformedTenant.left.exists(_.contains("tenant must be a string")))
    assert(malformedTaskId.left.exists(_.contains("taskId must be a string")))

  test("JSON-RPC encoders omit absent optional fields"):
    val notification = JsonRpcRequest.notification(A2AMethod.TasksList, Json.Obj()).toJson
    val success      = JsonRpcResponse.success(Some(JsonRpcId.Num(1)), Json.Obj("ok" -> Json.Bool(true))).toJson
    val nullId       = JsonRpcResponse.success(Some(JsonRpcId.Null), Json.Obj("ok" -> Json.Bool(true))).toJson
    val error        = JsonRpcResponse.error(None, JsonRpcError(A2AErrorCode.InvalidRequest, "bad")).toJson

    assert(!notification.contains(""""id""""))
    assert(!notification.contains("null"))
    assert(!success.contains(""""error""""))
    assert(!success.contains("null"))
    assert(nullId.contains(""""id":null"""))
    assert(!error.contains(""""result""""))
    assert(!error.contains(""""id""""))
    assert(!error.contains(""""data""""))
    assert(!error.contains("null"))

  test("JSON-RPC decoders enforce version, oneof response, and preserve numeric ids"):
    val invalidVersion =
      """{
        |  "jsonrpc": "1.0",
        |  "method": "GetTask"
        |}""".stripMargin.fromJson[JsonRpcRequest]
    val emptyMethod =
      """{
        |  "jsonrpc": "2.0",
        |  "method": ""
        |}""".stripMargin.fromJson[JsonRpcRequest]
    val fractionalRequestId =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "GetTask",
        |  "id": 1.5
        |}""".stripMargin.fromJson[JsonRpcRequest]
    val largeRequestId =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "GetTask",
        |  "id": 9223372036854775808
        |}""".stripMargin.fromJson[JsonRpcRequest]
    val nullRequestId =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "GetTask",
        |  "id": null
        |}""".stripMargin.fromJson[JsonRpcRequest]
    val bothResponseArms =
      """{
        |  "jsonrpc": "2.0",
        |  "result": {},
        |  "error": {"code": -32603, "message": "bad"},
        |  "id": 1
        |}""".stripMargin.fromJson[JsonRpcResponse]
    val missingResponseArm =
      """{
        |  "jsonrpc": "2.0",
        |  "id": 1
        |}""".stripMargin.fromJson[JsonRpcResponse]
    val nullResult =
      """{
        |  "jsonrpc": "2.0",
        |  "result": null,
        |  "id": 1
        |}""".stripMargin.fromJson[JsonRpcResponse]
    val fractionalErrorCode =
      """{
        |  "code": -32000.5,
        |  "message": "bad"
        |}""".stripMargin.fromJson[JsonRpcError]

    assert(invalidVersion.left.exists(_.contains("""jsonrpc must be "2.0"""")))
    assert(emptyMethod.left.exists(_.contains("Missing method")))
    assertEquals(
      fractionalRequestId.map(_.id),
      Right(Some(JsonRpcId.RawNum(new java.math.BigDecimal("1.5")))),
    )
    assertEquals(
      largeRequestId.map(_.id),
      Right(Some(JsonRpcId.RawNum(new java.math.BigDecimal("9223372036854775808")))),
    )
    assertEquals(nullRequestId.map(_.id), Right(Some(JsonRpcId.Null)))
    assert(bothResponseArms.left.exists(_.contains("exactly one of result or error")))
    assert(missingResponseArm.left.exists(_.contains("exactly one of result or error")))
    assertEquals(nullResult.map(_.result), Right(Some(Json.Null)))
    assert(fractionalErrorCode.left.exists(_.contains("code must be an int32")))

  test("JSON-RPC A2A parser requires an id field and accepts null ids"):
    val decodedNotification =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "ListTasks",
        |  "params": {}
        |}""".stripMargin.fromJson[JsonRpcRequest]
    val parsedNotification =
      JsonRpcRequest.parse(
        """{
          |  "jsonrpc": "2.0",
          |  "method": "ListTasks",
          |  "params": {}
          |}""".stripMargin
      )
    val parsedNullId =
      JsonRpcRequest.parse(
        """{
          |  "jsonrpc": "2.0",
          |  "method": "ListTasks",
          |  "params": {},
          |  "id": null
          |}""".stripMargin
      )

    assertEquals(decodedNotification.map(_.id), Right(None))
    assertEquals(parsedNotification.left.map(_.code), Left(A2AErrorCode.InvalidRequest))
    assert(parsedNotification.left.exists(_.message.contains("Missing id")))
    assertEquals(parsedNullId.map(_.id), Right(Some(JsonRpcId.Null)))

  test("JSON-RPC A2A-specific errors include google.rpc ErrorInfo details"):
    val taskNotFound = A2AError.taskNotFound(taskId).toJsonRpcError
    val extendedCard = A2AError.authenticatedExtendedCardNotConfigured.toJsonRpcError
    val unauthenticated = A2AError.unauthenticated("missing credentials").toJsonRpcError

    assertEquals(taskNotFound.code, A2AErrorCode.TaskNotFound)
    assertEquals(A2AError.httpStatus(A2AError.unauthenticated("missing credentials")), 401)
    assertEquals(
      taskNotFound.data.flatMap(_.asArray).flatMap(_.headOption).flatMap(_.asObject).map(_.toMap),
      Some(
        Map(
          "@type"  -> Json.Str(A2AError.ErrorInfoType),
          "reason" -> Json.Str("TASK_NOT_FOUND"),
          "domain" -> Json.Str(A2AError.ErrorInfoDomain),
        )
      ),
    )
    assertEquals(
      extendedCard.data.flatMap(_.asArray).flatMap(_.headOption).flatMap(_.asObject).flatMap(_.toMap.get("reason")),
      Some(Json.Str("EXTENDED_AGENT_CARD_NOT_CONFIGURED")),
    )
    assertEquals(
      unauthenticated.data.flatMap(_.asArray).flatMap(_.headOption).flatMap(_.asObject).flatMap(_.toMap.get("reason")),
      Some(Json.Str("UNAUTHENTICATED")),
    )

  test("list tasks response emits current v1 proto pagination fields"):
    val result = A2AResponse.ListTasksResult(List(task), nextPageToken = Some("next"), pageSize = 25, totalSize = 200)
    val json   = result.toJson

    assert(json.contains(""""tasks""""))
    assert(json.contains(""""nextPageToken":"next""""))
    assert(json.contains(""""pageSize":25"""))
    assert(json.contains(""""totalSize":200"""))

  test("list tasks response preserves includeArtifacts empty arrays"):
    val emptyArtifactTask = task.copy(artifacts = Nil)
    val omitted          = A2AResponse.ListTasksResult(List(emptyArtifactTask)).toJson
    val included         = A2AResponse.ListTasksResult(List(emptyArtifactTask), includeArtifacts = true).toJson

    assert(!omitted.contains(""""artifacts""""))
    assert(included.contains(""""artifacts":[]"""))

  test("list tasks response accepts snake-case pagination fields"):
    val decoded =
      """{
        |  "tasks": [],
        |  "next_page_token": "",
        |  "page_size": 25,
        |  "total_size": 200
        |}""".stripMargin.fromJson[A2AResponse.ListTasksResult]

    assertEquals(decoded.map(r => (r.pageSize, r.totalSize)), Right((25, 200)))

  test("list tasks response decoder enforces required pagination fields"):
    val missingToken =
      """{
        |  "tasks": [],
        |  "pageSize": 25,
        |  "totalSize": 200
        |}""".stripMargin.fromJson[A2AResponse.ListTasksResult]
    val malformedTasks =
      """{
        |  "tasks": {},
        |  "nextPageToken": "",
        |  "pageSize": 25,
        |  "totalSize": 200
        |}""".stripMargin.fromJson[A2AResponse.ListTasksResult]
    val fractionalPageSize =
      """{
        |  "tasks": [],
        |  "nextPageToken": "",
        |  "pageSize": 1.5,
        |  "totalSize": 200
        |}""".stripMargin.fromJson[A2AResponse.ListTasksResult]
    val overflowTotalSize =
      """{
        |  "tasks": [],
        |  "nextPageToken": "",
        |  "pageSize": 25,
        |  "totalSize": 2147483648
        |}""".stripMargin.fromJson[A2AResponse.ListTasksResult]

    assert(missingToken.left.exists(_.contains("Missing nextPageToken")))
    assert(malformedTasks.left.exists(_.contains("tasks must be an array")))
    assert(fractionalPageSize.left.exists(_.contains("pageSize must be an int32")))
    assert(overflowTotalSize.left.exists(_.contains("totalSize must be an int32")))

  test("push notification config list response rejects malformed optional fields"):
    val malformedConfigs =
      """{
        |  "configs": {},
        |  "nextPageToken": ""
        |}""".stripMargin.fromJson[A2AResponse.PushNotificationConfigListResult]
    val malformedNextToken =
      """{
        |  "configs": [],
        |  "nextPageToken": 5
        |}""".stripMargin.fromJson[A2AResponse.PushNotificationConfigListResult]

    assert(malformedConfigs.left.exists(_.contains("configs must be an array")))
    assert(malformedNextToken.left.exists(_.contains("nextPageToken must be a string")))

  test("stream response variants round-trip"):
    roundTrip[A2AResponse.StreamResponse](A2AResponse.StreamEvent.TaskSnapshot(task))
    val directMessage = message.copy(role = A2ARole.Agent, taskId = None)
    val directEvent: A2AResponse.StreamResponse = A2AResponse.StreamEvent.Message(directMessage)
    roundTrip[A2AResponse.StreamResponse](directEvent)
    val directJson = directEvent.toJson
    assertEquals(directJson.fromJson[A2AResponse.StreamEvent].map(_.isFinal), Right(true))
    assertEquals(directJson.fromJson[A2AResponse.StreamEvent].map(_.closesStream), Right(true))
    roundTrip[A2AResponse.StreamResponse](
      A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, task.status, metadata = Some(Json.Obj("m" -> Json.Str("v"))))
    )
    val finalStatus = A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, task.status, `final` = true)
    val finalJson   = finalStatus.toJson
    assert(!finalJson.contains(""""final""""))
    assert(finalJson.fromJson[A2AResponse.StreamEvent].exists(_.isFinal))
    roundTrip[A2AResponse.StreamResponse](A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact, append = true, lastChunk = false))
    roundTrip[A2AResponse.StreamResponse](A2AResponse.StreamEvent.TaskMessage(taskId, contextId, message))

  test("artifact stream encoder omits default false booleans"):
    val defaultEvent: A2AResponse.StreamEvent =
      A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact)
    val trueEvent: A2AResponse.StreamEvent =
      A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact, append = true, lastChunk = true)
    val defaultJson = defaultEvent.toJson
    val trueJson    = trueEvent.toJson

    assert(!defaultJson.contains(""""append":false"""))
    assert(!defaultJson.contains(""""lastChunk":false"""))
    assert(trueJson.contains(""""append":true"""))
    assert(trueJson.contains(""""lastChunk":true"""))
    assertEquals(defaultJson.fromJson[A2AResponse.StreamEvent].map(_.isFinal), Right(false))

  test("v1 stream event decoders require non-empty ids"):
    val emptyStatusTaskId =
      """{
        |  "statusUpdate": {
        |    "taskId": "",
        |    "contextId": "ctx-codec",
        |    "status": {"state": "TASK_STATE_WORKING"}
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val malformedStatusTaskId =
      """{
        |  "statusUpdate": {
        |    "taskId": 5,
        |    "contextId": "ctx-codec",
        |    "status": {"state": "TASK_STATE_WORKING"}
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val missingStatusContext =
      """{
        |  "statusUpdate": {
        |    "taskId": "task-codec",
        |    "status": {"state": "TASK_STATE_WORKING"}
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val missingArtifactContext =
      """{
        |  "artifactUpdate": {
        |    "taskId": "task-codec",
        |    "artifact": {
        |      "artifactId": "artifact-codec",
        |      "parts": [{"text": "chunk"}]
        |    }
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val malformedStatusContext =
      """{
        |  "statusUpdate": {
        |    "taskId": "task-codec",
        |    "contextId": 5,
        |    "status": {"state": "TASK_STATE_WORKING"}
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val emptyArtifactTaskId =
      """{
        |  "artifactUpdate": {
        |    "taskId": "",
        |    "contextId": "ctx-codec",
        |    "artifact": {
        |      "artifactId": "artifact-codec",
        |      "parts": [{"text": "chunk"}]
        |    }
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val malformedArtifactTaskId =
      """{
        |  "artifactUpdate": {
        |    "taskId": false,
        |    "contextId": "ctx-codec",
        |    "artifact": {
        |      "artifactId": "artifact-codec",
        |      "parts": [{"text": "chunk"}]
        |    }
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assert(emptyStatusTaskId.left.exists(_.contains("Missing taskId")))
    assert(malformedStatusTaskId.left.exists(_.contains("taskId must be a string")))
    assert(missingStatusContext.left.exists(_.contains("Missing contextId")))
    assert(missingArtifactContext.left.exists(_.contains("Missing contextId")))
    assert(malformedStatusContext.left.exists(_.contains("contextId must be a non-empty string")))
    assert(emptyArtifactTaskId.left.exists(_.contains("Missing taskId")))
    assert(malformedArtifactTaskId.left.exists(_.contains("taskId must be a string")))

  test("response oneof decoders reject multiple payload fields"):
    val sendResult =
      s"""{
         |  "task": ${task.toJson},
         |  "message": ${message.toJson}
         |}""".stripMargin.fromJson[A2AResponse.SendMessageResult]
    val streamResult =
      s"""{
         |  "task": ${task.toJson},
         |  "statusUpdate": {
         |    "taskId": "task-codec",
         |    "contextId": "ctx-codec",
         |    "status": {"state": "TASK_STATE_WORKING"}
         |  }
         |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assert(sendResult.left.exists(_.contains("exactly one of message or task")))
    assert(streamResult.left.exists(_.contains("exactly one of task, message, statusUpdate, or artifactUpdate")))

  test("legacy status updates with final field still decode"):
    val decoded =
      """{
        |  "statusUpdate": {
        |    "taskId": "task-codec",
        |    "contextId": "ctx-codec",
        |    "final": true,
        |    "status": {"state": "TASK_STATE_WORKING"}
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assert(decoded.exists {
      case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, isFinal, _) =>
        status.state == TaskState.Working && isFinal
      case _ =>
        false
    })

  test("stream event decoders reject malformed boolean fields"):
    val malformedFinal =
      """{
        |  "statusUpdate": {
        |    "taskId": "task-codec",
        |    "contextId": "ctx-codec",
        |    "final": "yes",
        |    "status": {"state": "TASK_STATE_WORKING"}
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val malformedAppend =
      """{
        |  "artifactUpdate": {
        |    "taskId": "task-codec",
        |    "contextId": "ctx-codec",
        |    "append": "yes",
        |    "artifact": {
        |      "artifactId": "artifact-codec",
        |      "parts": [{"text": "chunk"}]
        |    }
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val malformedLastChunk =
      """{
        |  "artifactUpdate": {
        |    "taskId": "task-codec",
        |    "contextId": "ctx-codec",
        |    "lastChunk": 5,
        |    "artifact": {
        |      "artifactId": "artifact-codec",
        |      "parts": [{"text": "chunk"}]
        |    }
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]
    val malformedNestedAppend =
      """{
        |  "kind": "artifact",
        |  "taskId": "task-codec",
        |  "artifact": {
        |    "artifactId": "artifact-codec",
        |    "parts": [{"text": "chunk"}],
        |    "append": "yes"
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assert(malformedFinal.left.exists(_.contains("final must be a boolean")))
    assert(malformedAppend.left.exists(_.contains("append must be a boolean")))
    assert(malformedLastChunk.left.exists(_.contains("lastChunk must be a boolean")))
    assert(malformedNestedAppend.left.exists(_.contains("append must be a boolean")))

  test("legacy stream events may omit contextId"):
    val decoded =
      """{
        |  "kind": "status-update",
        |  "taskId": "task-codec",
        |  "status": {"state": "TASK_STATE_WORKING"}
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assertEquals(decoded.map(_.taskId), Right(taskId))

  test("task message stream encoding preserves wrapper task and context ids"):
    val event = A2AResponse.StreamEvent.TaskMessage(taskId, contextId, message.copy(taskId = None, contextId = None))
    val json  = event.toJson

    assert(json.contains(""""taskId":"task-codec""""))
    assert(json.contains(""""contextId":"ctx-codec""""))
    assertEquals(json.fromJson[A2AResponse.StreamEvent].map(_.taskId), Right(taskId))

  test("v1 artifact updates default omitted lastChunk to false"):
    val decoded =
      """{
        |  "artifactUpdate": {
        |    "taskId": "task-codec",
        |    "contextId": "ctx-codec",
        |    "artifact": {
        |      "artifactId": "artifact-codec",
        |      "parts": [{"text": "chunk"}]
        |    }
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assert(decoded.exists {
      case A2AResponse.StreamEvent.TaskArtifactUpdate(_, _, _, _, lastChunk, _) => !lastChunk
      case _                                                                   => false
    })

  test("legacy artifact updates preserve omitted lastChunk as final"):
    val decoded =
      """{
        |  "kind": "artifact",
        |  "taskId": "task-codec",
        |  "contextId": "ctx-codec",
        |  "artifact": {
        |    "artifactId": "artifact-codec",
        |    "parts": [{"text": "chunk"}]
        |  }
        |}""".stripMargin.fromJson[A2AResponse.StreamEvent]

    assert(decoded.exists {
      case A2AResponse.StreamEvent.TaskArtifactUpdate(_, _, _, _, lastChunk, _) => lastChunk
      case _                                                                   => false
    })

  test("snake_case aliases decode for interop"):
    val decoded =
      """{
        |  "message": {
        |    "role": "ROLE_USER",
        |    "parts": [{"text": "hello"}],
        |    "message_id": "msg-snake",
        |    "context_id": "ctx-snake",
        |    "task_id": "task-snake",
        |    "reference_task_ids": ["task-ref"]
        |  },
        |  "configuration": {
        |    "accepted_output_modes": ["text/plain"],
        |    "return_immediately": true,
        |    "history_length": 1,
        |    "task_push_notification_config": {
        |      "url": "https://callback.example.test",
        |      "task_id": "task-snake"
        |    }
        |  }
        |}""".stripMargin.fromJson[A2ARequest.MessageSend]

    assert(decoded.exists(_.configuration.exists(_.returnImmediately)))
    assertEquals(decoded.toOption.flatMap(_.message.taskId), Some(TaskId("task-snake")))

  test("SendMessageRequest decoder accepts top-level taskId and contextId aliases"):
    val decoded =
      """{
        |  "taskId": "task-top",
        |  "contextId": "ctx-top",
        |  "message": {
        |    "role": "ROLE_USER",
        |    "parts": [{"text": "hello"}],
        |    "messageId": "msg-top"
        |  }
        |}""".stripMargin.fromJson[A2ARequest.MessageSend]
    val conflict =
      """{
        |  "taskId": "task-top",
        |  "message": {
        |    "role": "ROLE_USER",
        |    "parts": [{"text": "hello"}],
        |    "messageId": "msg-top",
        |    "taskId": "task-nested"
        |  }
        |}""".stripMargin.fromJson[A2ARequest.MessageSend]
    val snake =
      """{
        |  "task_id": "task-snake-top",
        |  "context_id": "ctx-snake-top",
        |  "message": {
        |    "role": "ROLE_USER",
        |    "parts": [{"text": "hello"}],
        |    "messageId": "msg-snake-top"
        |  }
        |}""".stripMargin.fromJson[A2ARequest.MessageSend]

    assertEquals(decoded.map(value => (value.message.taskId, value.message.contextId)), Right((Some(TaskId("task-top")), Some(ContextId("ctx-top")))))
    assert(conflict.left.exists(_.contains("taskId conflicts")))
    assertEquals(
      snake.map(value => (value.message.taskId, value.message.contextId)),
      Right((Some(TaskId("task-snake-top")), Some(ContextId("ctx-snake-top")))),
    )

  test("request decoders accept ProtoJSON original field names"):
    val taskGet =
      """{
        |  "id": "task-snake",
        |  "history_length": 3,
        |  "include_artifacts": false
        |}""".stripMargin.fromJson[A2ARequest.TasksGet]
    val taskList =
      """{
        |  "context_id": "ctx-snake",
        |  "status": "TASK_STATE_WORKING",
        |  "page_size": 25,
        |  "page_token": "cursor",
        |  "history_length": 1,
        |  "status_timestamp_after": "2026-01-01T00:00:00Z",
        |  "include_artifacts": true
        |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val pushGet =
      """{
        |  "task_id": "task-snake",
        |  "id": "push-snake"
        |}""".stripMargin.fromJson[A2ARequest.PushNotificationConfigGet]
    val pushList =
      """{
        |  "task_id": "task-snake",
        |  "page_size": 10,
        |  "page_token": "next"
        |}""".stripMargin.fromJson[A2ARequest.PushNotificationConfigList]

    assertEquals(
      taskGet.map(value => (value.id, value.historyLength, value.includeArtifacts)),
      Right((TaskId("task-snake"), Some(3), Some(false))),
    )
    assertEquals(
      taskList.map(value => (value.contextId, value.pageSize, value.historyLength, value.includeArtifacts)),
      Right((Some(ContextId("ctx-snake")), Some(25), Some(1), Some(true))),
    )
    assertEquals(pushGet.map(value => (value.taskId, value.id)), Right((TaskId("task-snake"), "push-snake")))
    assertEquals(pushList.map(value => (value.taskId, value.pageSize, value.pageToken)), Right((TaskId("task-snake"), Some(10), Some("next"))))

  test("request decoders reject non-int32 numeric fields"):
    val fractional =
      """{
        |  "pageSize": 1.5
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val overflow =
      """{
        |  "id": "task-overflow",
        |  "historyLength": 2147483648
        |}""".stripMargin.fromJson[A2ARequest.TasksGet]

    assert(fractional.left.exists(_.contains("pageSize must be an int32")))
    assert(overflow.left.exists(_.contains("historyLength must be an int32")))

  test("request decoders reject negative history lengths"):
    val getTask =
      """{
        |  "id": "task-negative-history",
        |  "historyLength": -1
      |}""".stripMargin.fromJson[A2ARequest.TasksGet]
    val listTasks =
      """{
        |  "history_length": -1
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val messageConfig =
      """{
        |  "historyLength": -1
      |}""".stripMargin.fromJson[MessageSendConfiguration]
    val zero =
      """{
        |  "id": "task-zero-history",
        |  "historyLength": 0
      |}""".stripMargin.fromJson[A2ARequest.TasksGet]

    assert(getTask.left.exists(_.contains("historyLength must be non-negative integer")))
    assert(listTasks.left.exists(_.contains("historyLength must be non-negative integer")))
    assert(messageConfig.left.exists(_.contains("historyLength must be non-negative integer")))
    assertEquals(zero.map(_.historyLength), Right(Some(0)))

  test("list task request decoder bounds page size to upstream limits"):
    val zero =
      """{
        |  "pageSize": 0
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val negative =
      """{
        |  "pageSize": -1
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val oversizedSnakeCase =
      """{
        |  "page_size": 101
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val max =
      """{
        |  "pageSize": 100
      |}""".stripMargin.fromJson[A2ARequest.TasksList]

    assert(zero.left.exists(_.contains("pageSize must be between 1 and 100")))
    assert(negative.left.exists(_.contains("pageSize must be between 1 and 100")))
    assert(oversizedSnakeCase.left.exists(_.contains("pageSize must be between 1 and 100")))
    assertEquals(max.map(_.pageSize), Right(Some(100)))

  test("list task request decoder enforces UTC status timestamp filter"):
    val malformed =
      """{
        |  "statusTimestampAfter": "not-a-timestamp"
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val offset =
      """{
        |  "status_timestamp_after": "2025-12-31T20:00:00-05:00"
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val nonString =
      """{
        |  "statusTimestampAfter": 5
      |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val utc =
      """{
        |  "statusTimestampAfter": "2026-01-01T00:00:00Z"
      |}""".stripMargin.fromJson[A2ARequest.TasksList]

    assert(malformed.left.exists(_.contains("statusTimestampAfter must be an ISO 8601 UTC timestamp")))
    assert(offset.left.exists(_.contains("statusTimestampAfter must be an ISO 8601 UTC timestamp")))
    assert(nonString.left.exists(_.contains("statusTimestampAfter must be a string")))
    assertEquals(utc.map(_.statusTimestampAfter), Right(Some("2026-01-01T00:00:00Z")))

  test("request and task status decoders reject unspecified task state where a real state is required"):
    val unspecifiedListStatus =
      """{
        |  "status": "TASK_STATE_UNSPECIFIED"
        |}""".stripMargin.fromJson[A2ARequest.TasksList]
    val unspecifiedTaskStatus =
      """{
        |  "state": "TASK_STATE_UNSPECIFIED"
        |}""".stripMargin.fromJson[TaskStatus]

    assert(unspecifiedListStatus.left.exists(_.contains("status must be one of")))
    assert(unspecifiedTaskStatus.left.exists(_.contains("state must be one of")))

  test("message send configuration encoder omits default false returnImmediately"):
    val defaultJson = MessageSendConfiguration.default.toJson
    val submitJson  = MessageSendConfiguration.default.copy(returnImmediately = true).toJson
    val explicitModesJson = MessageSendConfiguration(
      acceptedOutputModes = List("text/plain", "application/json")
    ).toJson

    assertEquals(defaultJson, "{}")
    assert(!defaultJson.contains(""""acceptedOutputModes""""))
    assert(!defaultJson.contains(""""returnImmediately":false"""))
    assert(submitJson.contains(""""returnImmediately":true"""))
    assert(explicitModesJson.contains(""""acceptedOutputModes":["text/plain","application/json"]"""))
    assertEquals(
      defaultJson.fromJson[MessageSendConfiguration].map(config => (config.acceptedOutputModes, config.returnImmediately)),
      Right((Nil, false)),
    )

  test("message send configuration decoder rejects malformed nested fields"):
    val malformedModes =
      """{
        |  "acceptedOutputModes": "text/plain"
        |}""".stripMargin.fromJson[MessageSendConfiguration]
    val malformedPushConfig =
      """{
        |  "taskPushNotificationConfig": {"id": "missing-url"}
        |}""".stripMargin.fromJson[MessageSendConfiguration]
    val nonBooleanReturnImmediately =
      """{
        |  "returnImmediately": "yes"
        |}""".stripMargin.fromJson[MessageSendConfiguration]
    val fractionalHistory =
      """{
        |  "historyLength": 1.5
        |}""".stripMargin.fromJson[MessageSendConfiguration]

    assert(malformedModes.isLeft)
    assert(malformedPushConfig.left.exists(_.contains("Missing url")))
    assert(nonBooleanReturnImmediately.left.exists(_.contains("returnImmediately must be a boolean")))
    assert(fractionalHistory.left.exists(_.contains("historyLength must be an int32")))

  test("artifact and agent capability decoders accept ProtoJSON original field names"):
    val decodedArtifact =
      """{
        |  "artifact_id": "artifact-snake",
        |  "parts": [{"text": "hello"}],
        |  "extensions": ["https://example.test/ext"]
        |}""".stripMargin.fromJson[Artifact]
    val decodedCapabilities =
      """{
        |  "streaming": true,
        |  "push_notifications": true,
        |  "extended_agent_card": true
        |}""".stripMargin.fromJson[AgentCapabilities]

    assertEquals(decodedArtifact.map(value => (value.artifactId, value.parts.size)), Right(("artifact-snake", 1)))
    assertEquals(
      decodedCapabilities.map(value => (value.streaming, value.pushNotifications, value.extendedAgentCard)),
      Right((true, true, true)),
    )
end A2ACodecSpec
