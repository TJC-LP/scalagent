package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import zio.*
import zio.json.*

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
      .flatMap(response => ZIO.fromPromiseJS(response.text().asInstanceOf[js.Promise[String]]))

  private def tasksListRequest(id: Long): String =
    JsonRpcRequest(
      method = A2AMethod.TasksList,
      params = A2ARequest.TasksList().toJsonAST.toOption,
      id = Some(JsonRpcId.Num(id)),
    ).toJson

  private def messageStreamRequest(id: Long): String =
    JsonRpcRequest(
      method = A2AMethod.MessageStream,
      params = A2ARequest.MessageSend(A2AMessage.userText("hello")).toJsonAST.toOption,
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
end A2AServerValidationSpec
