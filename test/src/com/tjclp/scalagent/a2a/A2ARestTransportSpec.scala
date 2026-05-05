package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.Random
import zio.*
import zio.json.*

class A2ARestTransportSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def jsHeaders(headers: Map[String, String]): js.Dynamic =
    val obj = js.Dynamic.literal()
    headers.foreach { case (name, value) => obj.updateDynamic(name)(value) }
    obj

  private def fetchText(
    url: String,
    method: String = "GET",
    body: Option[String] = None,
    headers: Map[String, String] = Map.empty,
  ): Task[(Int, String, String)] =
    ZIO
      .fromPromiseJS {
        val init = js.Dynamic.literal(method = method, headers = jsHeaders(headers))
        body.foreach(value => init.body = value)
        js.Dynamic.global.fetch(url, init).asInstanceOf[js.Promise[js.Dynamic]]
      }
      .flatMap { response =>
        ZIO
          .fromPromiseJS(response.text().asInstanceOf[js.Promise[String]])
          .map(body => (response.status.asInstanceOf[Int], response.headers.get("content-type").asInstanceOf[String], body))
      }

  private def readFirstSseData(response: js.Dynamic): Task[String] =
    for
      step <- ZIO.fromPromiseJS(response.body.getReader().read().asInstanceOf[js.Promise[js.Dynamic]])
      decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
      chunk   = decoder.decode(step.value).asInstanceOf[String]
      data = chunk
        .linesIterator
        .find(_.startsWith("data:"))
        .map(_.drop("data:".length).trim)
        .getOrElse("")
    yield data

  private def fetchSse(
    url: String,
    body: String,
    headers: Map[String, String],
  ): Task[(Int, String, String)] =
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
      .flatMap(response => readFirstSseData(response).map(data => (response.status.asInstanceOf[Int], response.headers.get("content-type").asInstanceOf[String], data)))

  private def completedExecution(
    message: A2AMessage,
    taskId: TaskId,
    contextId: ContextId,
    publisher: A2AEventPublisher,
  ): Task[Unit] =
    val response = A2AMessage.agentText(s"done: ${message.text}", Some(contextId)).copy(taskId = Some(taskId))
    publisher.publish(
      A2AResponse.StreamEvent.TaskStatusUpdate(
        taskId,
        contextId,
        TaskStatus.completed(response),
        `final` = true,
      )
    )

  test("agent card advertises JSON-RPC and REST v1 interfaces"):
    val port = 54500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "RestCardTest",
      description = "REST card test server",
      host = "127.0.0.1",
      port = port,
      capabilities = AgentCapabilities.default.copy(pushNotifications = true),
      executionOverride = Some(completedExecution),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- fetchText(server.url + A2APaths.AgentCard)
          card   <- ZIO.fromEither(result._3.fromJson[AgentCard].left.map(new RuntimeException(_)))
        yield (result, card)
      }

    runTask(program).map { case ((status, contentType, _), card) =>
      assertEquals(status, 200)
      assert(contentType.startsWith(A2AContentType.Json))
      assertEquals(card.supportedInterfaces.map(_.protocolBinding), List(A2ATransport.JSONRPC, A2ATransport.HTTP_JSON))
      assertEquals(card.supportedInterfaces.map(_.protocolVersion), List(A2AProtocol.Version, A2AProtocol.Version))
      assertEquals(card.capabilities.pushNotifications, true)
    }

  test("REST message send uses application/a2a+json and tenant-prefixed task list"):
    val port = 55500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "RestSendTest",
      description = "REST send test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(completedExecution),
    )
    val request = A2ARequest.MessageSend(A2AMessage.userText("rest hello")).toJson
    val headers = Map(
      "Content-Type" -> A2AContentType.A2AJson,
      A2AHeader.Version -> A2AProtocol.Version,
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          sent   <- fetchText(server.url + "/tenant-a/message:send", method = "POST", body = Some(request), headers = headers)
          result <- ZIO.fromEither(sent._3.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
          task = result match
            case A2AResponse.SendMessageResult.TaskResult(task) => task
            case other                                          => throw new RuntimeException(s"Expected task, got $other")
          listA <- fetchText(server.url + "/tenant-a/tasks", headers = headers)
          listB <- fetchText(server.url + "/tenant-b/tasks", headers = headers)
          tasksA <- ZIO.fromEither(listA._3.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
          tasksB <- ZIO.fromEither(listB._3.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        yield (sent, task, tasksA, tasksB)
      }

    runTask(program).map { case ((status, contentType, _), task, tasksA, tasksB) =>
      assertEquals(status, 200)
      assert(contentType.startsWith(A2AContentType.A2AJson))
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(tasksA.tasks.map(_.id), List(task.id))
      assertEquals(tasksB.tasks, Nil)
    }

  test("REST streaming emits raw StreamResponse SSE data"):
    val port = 56500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "RestStreamTest",
      description = "REST stream test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(completedExecution),
    )
    val request = A2ARequest.MessageSend(A2AMessage.userText("stream hello")).toJson

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- fetchSse(
            server.url + "/message:stream",
            request,
            Map(
              "Content-Type" -> A2AContentType.A2AJson,
              A2AHeader.Version -> A2AProtocol.Version,
            ),
          )
        yield result
      }

    runTask(program).map { case (status, contentType, data) =>
      assertEquals(status, 200)
      assert(contentType.startsWith(A2AContentType.Sse))
      assert(data.contains(""""task""""))
      assert(!data.contains(""""jsonrpc""""))
      assert(data.fromJson[A2AResponse.StreamEvent].isRight)
    }

  test("REST errors use google.rpc-style bodies"):
    val port = 57500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "RestErrorTest",
      description = "REST error test server",
      host = "127.0.0.1",
      port = port,
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- fetchText(
            server.url + "/tasks/missing",
            headers = Map(A2AHeader.Version -> A2AProtocol.Version),
          )
        yield result
      }

    runTask(program).map { case (status, contentType, body) =>
      assertEquals(status, 404)
      assert(contentType.startsWith(A2AContentType.A2AJson))
      assert(body.contains(""""error""""))
      assert(body.contains(""""status":"NOT_FOUND""""))
      assert(body.contains(""""details""""))
    }

  test("REST rejects empty task ids and malformed push config ids"):
    val port = 58500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "RestMalformedPathTest",
      description = "REST malformed path test server",
      host = "127.0.0.1",
      port = port,
      capabilities = AgentCapabilities.default.copy(pushNotifications = true),
    )
    val headers = Map(A2AHeader.Version -> A2AProtocol.Version)

    val program =
      ZIO.scoped {
        for
          server      <- A2AServer.create(config)
          emptyTask   <- fetchText(server.url + "/tasks/", headers = headers)
          emptyTenant <- fetchText(server.url + "/tenant-a/tasks/", headers = headers)
          emptyConfig <- fetchText(server.url + "/tasks/task-1/pushNotificationConfigs/", headers = headers)
        yield (emptyTask, emptyTenant, emptyConfig)
      }

    runTask(program).map { case ((taskStatus, _, taskBody), (tenantStatus, _, tenantBody), (configStatus, _, configBody)) =>
      assertEquals(taskStatus, 400)
      assert(taskBody.contains("Missing task ID"))
      assertEquals(tenantStatus, 400)
      assert(tenantBody.contains("Missing task ID"))
      assertEquals(configStatus, 400)
      assert(configBody.contains("Missing push notification config ID"))
    }

  test("REST list rejects invalid pagination tokens"):
    val port = 59500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "RestPaginationTest",
      description = "REST pagination test server",
      host = "127.0.0.1",
      port = port,
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- fetchText(
            server.url + "/tasks?pageToken=not-a-number",
            headers = Map(A2AHeader.Version -> A2AProtocol.Version),
          )
        yield result
      }

    runTask(program).map { case (status, contentType, body) =>
      assertEquals(status, 400)
      assert(contentType.startsWith(A2AContentType.A2AJson))
      assert(body.contains("Invalid pageToken"))
    }
end A2ARestTransportSpec
