package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import zio.*
import zio.json.*
import zio.json.ast.Json

class A2AServerValidationSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def jsHeaders(headers: Map[String, String]): js.Dynamic =
    val obj = js.Dynamic.literal()
    headers.foreach { case (name, value) => obj.updateDynamic(name)(value) }
    obj

  private def post(url: String, body: String, headers: Map[String, String]): Task[String] =
    postWithExtensionHeader(url, body, headers).map(_._1)

  private def postWithExtensionHeader(
    url: String,
    body: String,
    headers: Map[String, String],
  ): Task[(String, Option[String])] =
    ZIO
      .fromPromiseJS {
        js.Dynamic.global
          .fetch(
            url,
            js.Dynamic.literal(
              method = "POST",
              headers = jsHeaders(headers),
              body = body,
            ),
          )
          .asInstanceOf[js.Promise[js.Dynamic]]
      }
      .flatMap { response =>
        val extensionHeader = Option(response.headers.get(A2AHeader.StandardExtensions).asInstanceOf[String])
        ZIO.fromPromiseJS(response.text().asInstanceOf[js.Promise[String]]).map(body => body -> extensionHeader)
      }

  private def tasksListRequest(id: Long, tenant: Option[String] = None): String =
    JsonRpcRequest(
      method = A2AMethod.TasksList,
      params = A2ARequest.TasksList(tenant = tenant).toJsonAST.toOption,
      id = Some(JsonRpcId.Num(id)),
    ).toJson

  private def messageStreamRequest(id: Long): String =
    JsonRpcRequest(
      method = A2AMethod.MessageStream,
      params = A2ARequest.MessageSend(A2AMessage.userText("hello")).toJsonAST.toOption,
      id = Some(JsonRpcId.Num(id)),
    ).toJson

  private def extendedCardRequest(id: Long): String =
    JsonRpcRequest(
      method = A2AMethod.GetAuthenticatedExtendedCard,
      id = Some(JsonRpcId.Num(id)),
    ).toJson

  test("JSON-RPC rejects unsupported A2A-Version and echoes request id"):
    val config = A2AServer.Config(
      name = "ValidationTest",
      description = "Validation test server",
      host = "127.0.0.1",
      port = 0,
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          body <- post(
            server.url,
            tasksListRequest(77),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> "0.9",
            ),
          )
          response <- ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield response
      }

    runTask(program).map { response =>
      assertEquals(response.id, Some(JsonRpcId.Num(77)))
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.VersionNotSupported))
    }

  test("JSON-RPC version negotiation ignores patch versions"):
    val config = A2AServer.Config(
      name = "VersionPatchTest",
      description = "Version patch test server",
      host = "127.0.0.1",
      port = 0,
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          body <- post(
            server.url,
            tasksListRequest(78),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> s"${A2AProtocol.Version}.99",
            ),
          )
          response <- ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
          result   <- ZIO.fromEither(response.getResult.left.map(error => new RuntimeException(error.message)))
          tasks    <- ZIO.fromEither(result.as[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        yield response -> tasks
      }

    runTask(program).map { case (response, tasks) =>
      assertEquals(response.id, Some(JsonRpcId.Num(78)))
      assertEquals(response.error, None)
      assertEquals(tasks.tasks, Nil)
    }

  test("JSON-RPC rejects missing required extension"):
    val requiredExtension = "https://example.test/extensions/required/v1"
    val config = A2AServer.Config(
      name = "ExtensionTest",
      description = "Extension test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(
        extensions = List(AgentExtension(uri = requiredExtension, required = true))
      ),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          body <- post(
            server.url,
            tasksListRequest(88),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
          response <- ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield response
      }

    runTask(program).map { response =>
      assertEquals(response.id, Some(JsonRpcId.Num(88)))
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.ExtensionSupportRequired))
    }

  test("JSON-RPC accepts required extension from standard extension header"):
    val requiredExtension = "https://example.test/extensions/required/v1"
    val config = A2AServer.Config(
      name = "ExtensionHeaderTest",
      description = "Extension header test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(
        extensions = List(AgentExtension(uri = requiredExtension, required = true))
      ),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          body <- post(
            server.url,
            tasksListRequest(99),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
              A2AHeader.StandardExtensions -> requiredExtension,
            ),
          )
          response <- ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield response
      }

    runTask(program).map { response =>
      assertEquals(response.id, Some(JsonRpcId.Num(99)))
      assert(response.error.isEmpty)
      assert(response.result.isDefined)
    }

  test("JSON-RPC validates advertised interface tenant"):
    val config = A2AServer.Config(
      name = "TenantValidationTest",
      description = "Tenant validation test server",
      host = "127.0.0.1",
      port = 0,
      tenant = Some("tenant-a"),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          acceptedBody <- post(
            server.url,
            tasksListRequest(120, Some("tenant-a")),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
          missingBody <- post(
            server.url,
            tasksListRequest(121),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
          wrongBody <- post(
            server.url,
            tasksListRequest(122, Some("tenant-b")),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
          accepted <- ZIO.fromEither(acceptedBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
          missing  <- ZIO.fromEither(missingBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
          wrong    <- ZIO.fromEither(wrongBody.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield (accepted, missing, wrong)
      }

    runTask(program).map { case (accepted, missing, wrong) =>
      assertEquals(accepted.id, Some(JsonRpcId.Num(120)))
      assertEquals(accepted.error, None)
      assertEquals(missing.id, Some(JsonRpcId.Num(121)))
      assertEquals(missing.error.map(_.code), Some(A2AErrorCode.InvalidParams))
      assert(missing.error.exists(_.message.contains("tenant is required")))
      assertEquals(wrong.id, Some(JsonRpcId.Num(122)))
      assertEquals(wrong.error.map(_.code), Some(A2AErrorCode.InvalidParams))
      assert(wrong.error.exists(_.message.contains("selected AgentInterface tenant")))
    }

  test("JSON-RPC echoes activated standard extension header"):
    val supportedExtension   = "https://example.test/extensions/supported/v1"
    val unsupportedExtension = "https://example.test/extensions/unsupported/v1"
    val config = A2AServer.Config(
      name = "ActivatedExtensionHeaderTest",
      description = "Activated extension header test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(
        extensions = List(AgentExtension(uri = supportedExtension))
      ),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- postWithExtensionHeader(
            server.url,
            tasksListRequest(100),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
              A2AHeader.StandardExtensions -> s"$unsupportedExtension,$supportedExtension,$supportedExtension",
            ),
          )
          response <- ZIO.fromEither(result._1.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield response -> result._2
      }

    runTask(program).map { case (response, extensionHeader) =>
      assertEquals(response.id, Some(JsonRpcId.Num(100)))
      assert(response.error.isEmpty)
      assertEquals(extensionHeader, Some(supportedExtension))
    }

  test("JSON-RPC rejects streaming when capability is disabled"):
    val config = A2AServer.Config(
      name = "NoStreamingTest",
      description = "No streaming test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(streaming = false),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          body <- post(
            server.url,
            messageStreamRequest(111),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
          response <- ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield response
      }

    runTask(program).map { response =>
      assertEquals(response.id, Some(JsonRpcId.Num(111)))
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.UnsupportedOperation))
    }

  test("JSON-RPC returns not-configured error for advertised missing extended card"):
    val config = A2AServer.Config(
      name = "MissingExtendedCardTest",
      description = "Missing extended card test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(extendedAgentCard = true),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          body <- post(
            server.url,
            extendedCardRequest(112),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
          response <- ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield response
      }

    runTask(program).map { response =>
      assertEquals(response.id, Some(JsonRpcId.Num(112)))
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.AuthenticatedExtendedCardNotConfigured))
      assertEquals(
        response.error.flatMap(_.data).flatMap(_.asArray).flatMap(_.headOption).flatMap(_.asObject).flatMap(_.toMap.get("reason")),
        Some(Json.Str("EXTENDED_AGENT_CARD_NOT_CONFIGURED")),
      )
    }

  test("JSON-RPC returns configured extended card when advertised"):
    val extendedCard = AgentCard(
      name = "ExtendedCard",
      description = "Private extended card",
      supportedInterfaces = List(AgentInterface.jsonRpc("https://extended.example.test/a2a")),
    )
    val config = A2AServer.Config(
      name = "ConfiguredExtendedCardTest",
      description = "Configured extended card test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(extendedAgentCard = true),
      extendedAgentCard = Some(extendedCard),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          body <- post(
            server.url,
            extendedCardRequest(113),
            Map(
              "Content-Type" -> A2AContentType.Json,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
          response <- ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
          card     <- ZIO.fromEither(response.getResult.left.map(_.message).flatMap(_.as[AgentCard]).left.map(new RuntimeException(_)))
        yield card
      }

    runTask(program).map { card =>
      assertEquals(card.name, "ExtendedCard")
      assertEquals(card.description, "Private extended card")
    }
end A2AServerValidationSpec
