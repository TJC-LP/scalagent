package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import munit.FunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

class A2AClientSpec extends FunSuite:
  private val runtime = Runtime.default

  private final case class CapturedRequest(body: String, headers: Map[String, String])

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def workingTask(taskId: TaskId = TaskId("task-1")): A2ATask =
    A2ATask(
      id = taskId,
      contextId = ContextId("ctx-1"),
      status = TaskStatus.working(),
    )

  private def completedTask(taskId: TaskId = TaskId("task-1")): A2ATask =
    val response = A2AMessage.agentText("done", Some(ContextId("ctx-1"))).copy(taskId = Some(taskId))
    A2ATask(
      id = taskId,
      contextId = ContextId("ctx-1"),
      status = TaskStatus.completed(response),
    )

  private def inputRequiredTask(taskId: TaskId = TaskId("task-1")): A2ATask =
    val response = A2AMessage.agentText("need input", Some(ContextId("ctx-1"))).copy(taskId = Some(taskId))
    A2ATask(
      id = taskId,
      contextId = ContextId("ctx-1"),
      status = TaskStatus.inputRequired(response),
    )

  private def recordingJsonRpcServer: ZIO[Scope, Throwable, (String, mutable.ListBuffer[String])] =
    for
      bodies <- ZIO.succeed(mutable.ListBuffer.empty[String])
      server <- ZIO.acquireRelease(
        ZIO.attempt {
          val Response = js.Dynamic.global.Response
          TestBun.serve(
            js.Dynamic.literal(
              hostname = "127.0.0.1",
              port = 0,
              fetch = { (req: js.Dynamic) =>
                req.text().asInstanceOf[js.Promise[String]].`then`[js.Dynamic] { body =>
                  bodies += body
                  val request = js.JSON.parse(body).asInstanceOf[js.Dynamic]
                  val idJson  = js.JSON.stringify(request.selectDynamic("id"))
                  val resultJson =
                    request.selectDynamic("method").asInstanceOf[String] match
                      case A2AMethod.PushNotificationConfigSet =>
                        """{"url":"https://callback.test","taskId":"task-1"}"""
                      case _ =>
                        """{"tasks":[],"nextPageToken":"","pageSize":0,"totalSize":0}"""
                  js.Dynamic.newInstance(Response)(
                    s"""{"jsonrpc":"2.0","id":$idJson,"result":$resultJson}""",
                    js.Dynamic.literal(status = 200, headers = js.Dynamic.literal(`Content-Type` = A2AContentType.Json)),
                  )
                }
              }: js.Function1[js.Dynamic, js.Promise[js.Dynamic]],
            )
          )
        }
      )(server => ZIO.attempt(server.stop()).ignore)
      port = server.selectDynamic("port").asInstanceOf[Int]
    yield (s"http://127.0.0.1:$port", bodies)

  private def recordingJsonRpcServerWithHeaders: ZIO[Scope, Throwable, (String, mutable.ListBuffer[CapturedRequest])] =
    for
      captured <- ZIO.succeed(mutable.ListBuffer.empty[CapturedRequest])
      server <- ZIO.acquireRelease(
        ZIO.attempt {
          val Response = js.Dynamic.global.Response
          TestBun.serve(
            js.Dynamic.literal(
              hostname = "127.0.0.1",
              port = 0,
              fetch = { (req: js.Dynamic) =>
                req.text().asInstanceOf[js.Promise[String]].`then`[js.Dynamic] { body =>
                  captured += CapturedRequest(body, requestHeaders(req))
                  val request = js.JSON.parse(body).asInstanceOf[js.Dynamic]
                  val idJson  = js.JSON.stringify(request.selectDynamic("id"))
                  js.Dynamic.newInstance(Response)(
                    s"""{"jsonrpc":"2.0","id":$idJson,"result":{"tasks":[],"nextPageToken":"","pageSize":0,"totalSize":0}}""",
                    js.Dynamic.literal(status = 200, headers = js.Dynamic.literal(`Content-Type` = A2AContentType.Json)),
                  )
                }
              }: js.Function1[js.Dynamic, js.Promise[js.Dynamic]],
            )
          )
        }
      )(server => ZIO.attempt(server.stop()).ignore)
      port = server.selectDynamic("port").asInstanceOf[Int]
    yield (s"http://127.0.0.1:$port", captured)

  private def requestHeaders(req: js.Dynamic): Map[String, String] =
    val headers = mutable.Map.empty[String, String]
    req.headers.asInstanceOf[js.Dynamic].forEach { (value: js.Any, key: js.Any) =>
      headers.update(key.asInstanceOf[String], value.asInstanceOf[String])
      ()
    }
    headers.toMap

  private def headerValue(headers: Map[String, String], name: String): Option[String] =
    headers.collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }

  private def headerCount(headers: Map[String, String], name: String): Int =
    headers.count { case (key, _) => key.equalsIgnoreCase(name) }

  private def cardFor(url: String, tenant: Option[String]): AgentCard =
    AgentCard
      .minimal("ClientTenant", "Client tenant test agent", url)
      .copy(
        supportedInterfaces = List(AgentInterface.jsonRpc(url, tenant)),
        capabilities = AgentCapabilities.default.copy(pushNotifications = true),
      )

  private def requestParams(body: String): Map[String, Json] =
    body
      .fromJson[JsonRpcRequest]
      .toOption
      .flatMap(_.params)
      .flatMap(_.asObject)
      .map(_.toMap)
      .getOrElse(fail(s"expected JSON-RPC params in request body: $body"))

  private class RecordingClient extends A2AClient:
    var seenConfig: Option[MessageSendConfiguration] = None

    override def agentCard: Task[AgentCard] =
      ZIO.succeed(AgentCard.minimal("Remote", "Remote test agent", "http://example.test"))

    override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
      seenConfig = config
      ZIO.succeed(workingTask())

    override def stream(
      message: A2AMessage,
      config: Option[MessageSendConfiguration],
    ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.empty

    override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
      ZIO.succeed(completedTask(taskId))

    override def listTasks(params: A2ARequest.TasksList): Task[A2AResponse.ListTasksResult] =
      ZIO.succeed(A2AResponse.ListTasksResult(Nil))

    override def cancelTask(taskId: TaskId): Task[A2ATask] =
      ZIO.succeed(completedTask(taskId))

    override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
      ZStream.empty

    override def getAgentCard: Task[AgentCard] =
      agentCard

    override def createTaskPushNotificationConfig(
      taskId: TaskId,
      config: TaskPushNotificationConfig,
    ): Task[TaskPushNotificationConfig] =
      ZIO.succeed(config)

    override def getTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[TaskPushNotificationConfig] =
      ZIO.dieMessage("unused in test")

    override def listTaskPushNotificationConfigs(taskId: TaskId): Task[List[TaskPushNotificationConfig]] =
      ZIO.succeed(Nil)

    override def deleteTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
      ZIO.unit

  private class PollingClient(polls: A2ATask*) extends RecordingClient:
    var pollCount = 0
    private val remaining = mutable.Queue.from(polls.toList)

    override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
      ZIO.succeed {
        pollCount += 1
        if remaining.nonEmpty then remaining.dequeue()
        else completedTask(taskId)
      }

  test("submit forces non-blocking message/send"):
    val client = new RecordingClient

    runTask(client.submit(A2AMessage.userText("hello"))).map { _ =>
      assertEquals(client.seenConfig.flatMap(_.blocking), Some(false))
    }

  test("sendAndPoll submits then polls until completed"):
    val client = new RecordingClient

    runTask(client.sendAndPoll(A2AMessage.userText("hello"), pollEvery = 1.millis)).map { task =>
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(client.seenConfig.flatMap(_.blocking), Some(false))
    }

  test("sendAndPoll stops when polling reaches an interrupted stream-ending state"):
    val client = new PollingClient(inputRequiredTask())

    runTask(client.sendAndPoll(A2AMessage.userText("hello"), pollEvery = 1.millis)).map { task =>
      assertEquals(task.status.state, TaskState.InputRequired)
      assertEquals(client.pollCount, 1)
      assertEquals(client.seenConfig.flatMap(_.blocking), Some(false))
    }

  test("JSON-RPC response id mismatch fails the client"):
    val responseBody =
      """{"jsonrpc":"2.0","id":999,"result":{"tasks":[],"pageSize":0,"totalSize":0}}"""

    val program =
      ZIO.scoped {
        for
          server <- ZIO.acquireRelease(
            ZIO.attempt {
              val Response = js.Dynamic.global.Response
              TestBun.serve(
                js.Dynamic.literal(
                  hostname = "127.0.0.1",
                  port = 0,
                  fetch = { (_: js.Dynamic) =>
                    js.Promise.resolve(
                      js.Dynamic.newInstance(Response)(
                        responseBody,
                        js.Dynamic.literal(status = 200, headers = js.Dynamic.literal(`Content-Type` = A2AContentType.Json)),
                      )
                    )
                  }: js.Function1[js.Dynamic, js.Promise[js.Dynamic]],
                )
              )
            }
          )(server => ZIO.attempt(server.stop()).ignore)
          port = server.selectDynamic("port").asInstanceOf[Int]
          client <- A2AClient.fromCard(AgentCard.minimal("Mismatch", "Mismatch test", s"http://127.0.0.1:$port"))
          result <- client.listTasks().either
        yield result
      }

    runTask(program).map { result =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidAgentResponse && error.message.contains("id mismatch")
        case _               => false
      })
    }

  test("client omits caller tenant when selected AgentInterface has no tenant"):
    val program =
      ZIO.scoped {
        for
          (url, bodies) <- recordingJsonRpcServer
          client        <- A2AClient.fromCard(cardFor(url, tenant = None))
          _             <- client.listTasks(A2ARequest.TasksList(tenant = Some("caller-tenant")))
          _ <- client.createTaskPushNotificationConfig(
            TaskId("task-1"),
            TaskPushNotificationConfig(url = "https://callback.test", tenant = Some("caller-tenant")),
          )
        yield bodies.toList
      }

    runTask(program).map { bodies =>
      assertEquals(bodies.map(requestParams).flatMap(_.get("tenant")), Nil)
    }

  test("client uses exactly the selected AgentInterface tenant"):
    val program =
      ZIO.scoped {
        for
          (url, bodies) <- recordingJsonRpcServer
          client        <- A2AClient.fromCard(cardFor(url, tenant = Some("iface-tenant")))
          _             <- client.listTasks(A2ARequest.TasksList(tenant = Some("caller-tenant")))
          _ <- client.createTaskPushNotificationConfig(
            TaskId("task-1"),
            TaskPushNotificationConfig(url = "https://callback.test", tenant = Some("caller-tenant")),
          )
        yield bodies.toList
      }

    runTask(program).map { bodies =>
      assertEquals(bodies.map(body => requestParams(body).get("tenant")), List.fill(2)(Some(Json.Str("iface-tenant"))))
    }

  test("client canonicalizes protected service headers from selected AgentInterface"):
    val program =
      ZIO.scoped {
        for
          (url, captured) <- recordingJsonRpcServerWithHeaders
          card = cardFor(url, tenant = None).copy(
            supportedInterfaces = List(AgentInterface.jsonRpc(url).copy(protocolVersion = "1.0"))
          )
          client <- A2AClient.fromCard(
            card,
            headers = Map(
              "a2a-version"                 -> "9.9",
              "content-type"                -> "text/plain",
              A2AHeader.StandardExtensions  -> "urn:trace",
              "Authorization"               -> "Bearer token",
            ),
          )
          _ <- client.listTasks()
        yield captured.toList
      }

    runTask(program).map { captured =>
      val headers = captured.head.headers
      assertEquals(headerValue(headers, A2AHeader.Version), Some("1.0"))
      assertEquals(headerCount(headers, A2AHeader.Version), 1)
      assertEquals(headerValue(headers, "Content-Type"), Some(A2AContentType.Json))
      assertEquals(headerCount(headers, "Content-Type"), 1)
      assertEquals(headerValue(headers, A2AHeader.StandardExtensions), Some("urn:trace"))
      assertEquals(headerValue(headers, "Authorization"), Some("Bearer token"))
    }
end A2AClientSpec

@js.native
@JSGlobal("Bun")
private object TestBun extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
