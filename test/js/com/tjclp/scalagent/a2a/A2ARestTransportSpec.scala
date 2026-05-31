package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
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
    fetchTextWithExtensionHeader(url, method, body, headers).map { case (status, contentType, body, _) =>
      (status, contentType, body)
    }

  private def fetchTextWithExtensionHeader(
    url: String,
    method: String = "GET",
    body: Option[String] = None,
    headers: Map[String, String] = Map.empty,
  ): Task[(Int, String, String, Option[String])] =
    ZIO
      .fromPromiseJS {
        val init = js.Dynamic.literal(method = method, headers = jsHeaders(headers))
        body.foreach(value => init.body = value)
        js.Dynamic.global.fetch(url, init).asInstanceOf[js.Promise[js.Dynamic]]
      }
      .flatMap { response =>
        val extensionHeader = Option(response.headers.get(A2AHeader.StandardExtensions).asInstanceOf[String])
        ZIO
          .fromPromiseJS(response.text().asInstanceOf[js.Promise[String]])
          .map(body =>
            (
              response.status.asInstanceOf[Int],
              response.headers.get("content-type").asInstanceOf[String],
              body,
              extensionHeader,
            )
          )
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
    val config = A2AServer.Config(
      name = "RestCardTest",
      description = "REST card test server",
      host = "127.0.0.1",
      port = 0,
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
      assert(card.skills.nonEmpty)
      assert(card.skills.forall(_.tags.nonEmpty))
    }

  test("REST message send uses application/a2a+json and tenant-prefixed task list"):
    val config = A2AServer.Config(
      name = "RestSendTest",
      description = "REST send test server",
      host = "127.0.0.1",
      port = 0,
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

  test("REST honors tenant request fields outside path bindings"):
    val config = A2AServer.Config(
      name = "RestTenantFieldTest",
      description = "REST tenant field test server",
      host = "127.0.0.1",
      port = 0,
      executionOverride = Some(completedExecution),
    )
    val request = A2ARequest.MessageSend(A2AMessage.userText("body tenant"), tenant = Some("tenant-body")).toJson
    val headers = Map(
      "Content-Type" -> A2AContentType.A2AJson,
      A2AHeader.Version -> A2AProtocol.Version,
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          sent   <- fetchText(server.url + "/message:send", method = "POST", body = Some(request), headers = headers)
          result <- ZIO.fromEither(sent._3.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
          task = result match
            case A2AResponse.SendMessageResult.TaskResult(task) => task
            case other                                          => throw new RuntimeException(s"Expected task, got $other")
          listed <- fetchText(
            server.url + "/tasks?tenant=tenant-body",
            headers = Map(A2AHeader.Version -> A2AProtocol.Version),
          )
          other <- fetchText(
            server.url + "/tasks?tenant=tenant-other",
            headers = Map(A2AHeader.Version -> A2AProtocol.Version),
          )
          conflict <- fetchText(
            server.url + "/tenant-path/message:send",
            method = "POST",
            body = Some(request),
            headers = headers,
          )
          tasks     <- ZIO.fromEither(listed._3.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
          otherList <- ZIO.fromEither(other._3.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        yield (sent, task, tasks, otherList, conflict)
      }

    runTask(program).map { case ((status, _, _), task, tasks, otherList, (conflictStatus, _, conflictBody)) =>
      assertEquals(status, 200)
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(tasks.tasks.map(_.id), List(task.id))
      assertEquals(otherList.tasks, Nil)
      assertEquals(conflictStatus, 400)
      assert(conflictBody.contains("Conflicting tenant values"))
    }

  test("REST CancelTask honors request body tenant"):
    val headers = Map(
      "Content-Type" -> A2AContentType.A2AJson,
      A2AHeader.Version -> A2AProtocol.Version,
    )
    val listHeaders = Map(A2AHeader.Version -> A2AProtocol.Version)

    val program =
      ZIO.scoped {
        for
          started <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          runOverride =
            (_: A2AMessage, _: TaskId, _: ContextId, _: A2AEventPublisher) =>
              started.succeed(()).unit *> release.await
          config = A2AServer.Config(
            name = "RestCancelTenantBodyTest",
            description = "REST cancel tenant body test server",
            host = "127.0.0.1",
            port = 0,
            executionOverride = Some(runOverride),
          )
          request = A2ARequest
            .MessageSend(
              A2AMessage.userText("cancel body tenant"),
              configuration = Some(MessageSendConfiguration(returnImmediately = true)),
              tenant = Some("tenant-cancel"),
            )
            .toJson
          server <- A2AServer.create(config)
          sent   <- fetchText(server.url + "/message:send", method = "POST", body = Some(request), headers = headers)
          result <- ZIO.fromEither(sent._3.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
          task = result match
            case A2AResponse.SendMessageResult.TaskResult(task) => task
            case other                                          => throw new RuntimeException(s"Expected task, got $other")
          _ <- started.await.timeoutFail(new RuntimeException("execution did not start"))(2.seconds)
          cancelBody = """{"tenant":"tenant-cancel","metadata":{"source":"body"}}"""
          canceled <- fetchText(
            server.url + s"/tasks/${task.id.value}:cancel",
            method = "POST",
            body = Some(cancelBody),
            headers = headers,
          )
          conflictBody = s"""{"id":"${task.id.value}-other","tenant":"tenant-cancel"}"""
          conflict <- fetchText(
            server.url + s"/tasks/${task.id.value}:cancel",
            method = "POST",
            body = Some(conflictBody),
            headers = headers,
          )
          tenantList <- fetchText(server.url + "/tasks?tenant=tenant-cancel", headers = listHeaders)
          defaultList <- fetchText(server.url + "/tasks", headers = listHeaders)
          canceledTask <- ZIO.fromEither(canceled._3.fromJson[A2ATask].left.map(new RuntimeException(_)))
          listedTenant <- ZIO.fromEither(tenantList._3.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
          listedDefault <- ZIO.fromEither(defaultList._3.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        yield (canceled, canceledTask, listedTenant, listedDefault, conflict)
      }

    runTask(program).map {
      case ((cancelStatus, _, _), canceledTask, listedTenant, listedDefault, (conflictStatus, _, conflictText)) =>
        assertEquals(cancelStatus, 202)
        assertEquals(canceledTask.status.state, TaskState.Canceled)
        assertEquals(listedTenant.tasks.map(_.id), List(canceledTask.id))
        assertEquals(listedDefault.tasks, Nil)
        assertEquals(conflictStatus, 400)
        assert(conflictText.contains("CancelTaskRequest.id does not match path id"))
    }

  test("REST streaming emits raw StreamResponse SSE data"):
    val config = A2AServer.Config(
      name = "RestStreamTest",
      description = "REST stream test server",
      host = "127.0.0.1",
      port = 0,
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
      assert(!data.contains(""""final""""))
      assert(data.fromJson[A2AResponse.StreamEvent].isRight)
    }

  test("JSON-RPC rejects unsupported content type"):
    val config = A2AServer.Config(
      name = "JsonRpcContentTypeTest",
      description = "JSON-RPC content type test server",
      host = "127.0.0.1",
      port = 0,
    )
    val request = JsonRpcRequest(
      method = A2AMethod.TasksList,
      params = A2ARequest.TasksList().toJsonAST.toOption,
      id = Some(JsonRpcId.Num(1)),
    ).toJson

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- fetchText(
            server.url + "/",
            method = "POST",
            body = Some(request),
            headers = Map("Content-Type" -> A2AContentType.A2AJson, A2AHeader.Version -> A2AProtocol.Version),
          )
          response <- ZIO.fromEither(result._3.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield response
      }

    runTask(program).map { response =>
      assertEquals(response.error.map(_.code), Some(A2AErrorCode.ContentTypeNotSupported))
    }

  test("JSON-RPC distinguishes malformed JSON from invalid request envelopes"):
    val config = A2AServer.Config(
      name = "JsonRpcEnvelopeErrorTest",
      description = "JSON-RPC envelope error test server",
      host = "127.0.0.1",
      port = 0,
    )
    val headers = Map("Content-Type" -> A2AContentType.Json, A2AHeader.Version -> A2AProtocol.Version)
    val invalidEnvelope =
      """{
        |  "jsonrpc": "1.0",
        |  "method": "GetTask",
        |  "id": 1
        |}""".stripMargin
    val missingId =
      """{
        |  "jsonrpc": "2.0",
        |  "method": "ListTasks",
        |  "params": {}
        |}""".stripMargin

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          malformed <- fetchText(
            server.url + "/",
            method = "POST",
            body = Some("""{"jsonrpc":"""),
            headers = headers,
          )
          invalid <- fetchText(
            server.url + "/",
            method = "POST",
            body = Some(invalidEnvelope),
            headers = headers,
          )
          missing <- fetchText(
            server.url + "/",
            method = "POST",
            body = Some(missingId),
            headers = headers,
          )
          malformedResponse <- ZIO.fromEither(malformed._3.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
          invalidResponse   <- ZIO.fromEither(invalid._3.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
          missingResponse   <- ZIO.fromEither(missing._3.fromJson[JsonRpcResponse].left.map(new RuntimeException(_)))
        yield (malformedResponse, invalidResponse, missingResponse)
      }

    runTask(program).map { case (malformed, invalid, missing) =>
      assertEquals(malformed.error.map(_.code), Some(A2AErrorCode.ParseError))
      assertEquals(invalid.error.map(_.code), Some(A2AErrorCode.InvalidRequest))
      assertEquals(missing.error.map(_.code), Some(A2AErrorCode.InvalidRequest))
      assertEquals(missing.id, None)
    }

  test("REST rejects unsupported request body content type"):
    val config = A2AServer.Config(
      name = "RestContentTypeTest",
      description = "REST content type test server",
      host = "127.0.0.1",
      port = 0,
      executionOverride = Some(completedExecution),
    )
    val request = A2ARequest.MessageSend(A2AMessage.userText("bad content type")).toJson

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- fetchText(
            server.url + "/message:send",
            method = "POST",
            body = Some(request),
            headers = Map("Content-Type" -> A2AContentType.Json, A2AHeader.Version -> A2AProtocol.Version),
          )
        yield result
      }

    runTask(program).map { case (status, contentType, body) =>
      assertEquals(status, 400)
      assert(contentType.startsWith(A2AContentType.A2AJson))
      assert(body.contains("CONTENT_TYPE_NOT_SUPPORTED"))
    }

  test("REST errors use google.rpc-style bodies"):
    val config = A2AServer.Config(
      name = "RestErrorTest",
      description = "REST error test server",
      host = "127.0.0.1",
      port = 0,
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

  test("REST SubscribeToTask accepts proto GET and documented POST bindings"):
    val config = A2AServer.Config(
      name = "RestSubscribeVerbTest",
      description = "REST subscribe verb test server",
      host = "127.0.0.1",
      port = 0,
      executionOverride = Some(completedExecution),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          getResult <- fetchText(
            server.url + "/tasks/task-1:subscribe",
            method = "GET",
            headers = Map(A2AHeader.Version -> A2AProtocol.Version),
          )
          postResult <- fetchText(
            server.url + "/tasks/task-1:subscribe",
            method = "POST",
            headers = Map(A2AHeader.Version -> A2AProtocol.Version),
          )
        yield getResult -> postResult
      }

    runTask(program).map { case ((getStatus, getContentType, getBody), (postStatus, postContentType, postBody)) =>
      assertEquals(getStatus, 404)
      assert(getContentType.startsWith(A2AContentType.A2AJson))
      assert(getBody.contains("TASK_NOT_FOUND"))
      assertEquals(postStatus, 404)
      assert(postContentType.startsWith(A2AContentType.A2AJson))
      assert(postBody.contains("TASK_NOT_FOUND"))
    }

  test("REST treats empty A2A-Version parameter as protocol 0.3"):
    val config = A2AServer.Config(
      name = "RestEmptyVersionTest",
      description = "REST empty version test server",
      host = "127.0.0.1",
      port = 0,
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          result <- fetchText(server.url + "/tasks?A2A-Version=")
        yield result
      }

    runTask(program).map { case (status, contentType, body) =>
      assertEquals(status, 400)
      assert(contentType.startsWith(A2AContentType.A2AJson))
      assert(body.contains("VERSION_NOT_SUPPORTED"))
      assert(body.contains("Version not supported: 0.3"))
    }

  test("REST echoes activated standard extension header"):
    val supportedExtension   = "https://example.test/extensions/rest-supported/v1"
    val unsupportedExtension = "https://example.test/extensions/rest-unsupported/v1"
    val config = A2AServer.Config(
      name = "RestActivatedExtensionTest",
      description = "REST activated extension test server",
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
          result <- fetchTextWithExtensionHeader(
            server.url + "/tasks",
            headers = Map(
              A2AHeader.Version -> A2AProtocol.Version,
              A2AHeader.StandardExtensions -> s"$unsupportedExtension,$supportedExtension,$supportedExtension",
            ),
          )
          tasks <- ZIO.fromEither(result._3.fromJson[A2AResponse.ListTasksResult].left.map(new RuntimeException(_)))
        yield result -> tasks
      }

    runTask(program).map { case ((status, contentType, _, extensionHeader), tasks) =>
      assertEquals(status, 200)
      assert(contentType.startsWith(A2AContentType.A2AJson))
      assertEquals(tasks.tasks, Nil)
      assertEquals(extensionHeader, Some(supportedExtension))
    }

  test("REST push notification config list honors pagination"):
    val config = A2AServer.Config(
      name = "RestPushPaginationTest",
      description = "REST push pagination test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(pushNotifications = true),
      executionOverride = Some(completedExecution),
    )
    val postHeaders = Map(
      "Content-Type" -> A2AContentType.A2AJson,
      A2AHeader.Version -> A2AProtocol.Version,
    )
    val getHeaders = Map(A2AHeader.Version -> A2AProtocol.Version)

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          sent <- fetchText(
            server.url + "/message:send",
            method = "POST",
            body = Some(A2ARequest.MessageSend(A2AMessage.userText("push pages")).toJson),
            headers = postHeaders,
          )
          result <- ZIO.fromEither(sent._3.fromJson[A2AResponse.SendMessageResult].left.map(new RuntimeException(_)))
          task = result match
            case A2AResponse.SendMessageResult.TaskResult(task) => task
            case other                                          => throw new RuntimeException(s"Expected task, got $other")
          _ <- fetchText(
            server.url + s"/tasks/${task.id.value}/pushNotificationConfigs",
            method = "POST",
            body = Some(TaskPushNotificationConfig(url = "http://callback.test/1", id = Some("cfg-1")).toJson),
            headers = postHeaders,
          )
          _ <- fetchText(
            server.url + s"/tasks/${task.id.value}/pushNotificationConfigs",
            method = "POST",
            body = Some(TaskPushNotificationConfig(url = "http://callback.test/2", id = Some("cfg-2")).toJson),
            headers = postHeaders,
          )
          _ <- fetchText(
            server.url + s"/tasks/${task.id.value}/pushNotificationConfigs",
            method = "POST",
            body = Some(TaskPushNotificationConfig(url = "http://callback.test/3", id = Some("cfg-3")).toJson),
            headers = postHeaders,
          )
          mismatch <- fetchText(
            server.url + s"/tasks/${task.id.value}/pushNotificationConfigs",
            method = "POST",
            body = Some(
              TaskPushNotificationConfig(
                url = "http://callback.test/mismatch",
                id = Some("cfg-mismatch"),
                taskId = Some(TaskId(s"${task.id.value}-other")),
              ).toJson
            ),
            headers = postHeaders,
          )
          page1Body <- fetchText(
            server.url + s"/tasks/${task.id.value}/pushNotificationConfigs?pageSize=2",
            headers = getHeaders,
          )
          page1 <- ZIO.fromEither(page1Body._3.fromJson[A2AResponse.PushNotificationConfigListResult].left.map(new RuntimeException(_)))
          page2Body <- fetchText(
            server.url + s"/tasks/${task.id.value}/pushNotificationConfigs?page_size=2&page_token=${page1.nextPageToken.getOrElse("")}",
            headers = getHeaders,
          )
          page2 <- ZIO.fromEither(page2Body._3.fromJson[A2AResponse.PushNotificationConfigListResult].left.map(new RuntimeException(_)))
          invalid <- fetchText(
            server.url + s"/tasks/${task.id.value}/pushNotificationConfigs?page_token=-1",
            headers = getHeaders,
          )
        yield (page1Body, page1, page2Body, page2, invalid, mismatch)
      }

    runTask(program).map {
      case (
            (status1, contentType1, _),
            page1,
            (status2, _, _),
            page2,
            (invalidStatus, _, invalidBody),
            (mismatchStatus, _, mismatchBody),
          ) =>
      assertEquals(status1, 200)
      assert(contentType1.startsWith(A2AContentType.A2AJson))
      assertEquals(page1.configs.flatMap(_.id), List("cfg-1", "cfg-2"))
      assert(page1.nextPageToken.exists(_.startsWith("v1:")))
      assertEquals(status2, 200)
      assertEquals(page2.configs.flatMap(_.id), List("cfg-3"))
      assertEquals(page2.nextPageToken, None)
      assertEquals(invalidStatus, 400)
      assert(invalidBody.contains("Invalid pageToken"))
      assertEquals(mismatchStatus, 400)
      assert(mismatchBody.contains("TaskPushNotificationConfig.taskId does not match path taskId"))
    }

  test("REST rejects empty task ids and malformed push config ids"):
    val config = A2AServer.Config(
      name = "RestMalformedPathTest",
      description = "REST malformed path test server",
      host = "127.0.0.1",
      port = 0,
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
    val config = A2AServer.Config(
      name = "RestPaginationTest",
      description = "REST pagination test server",
      host = "127.0.0.1",
      port = 0,
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

  test("REST task list accepts proto snake-case query aliases"):
    val config = A2AServer.Config(
      name = "RestSnakeQueryTest",
      description = "REST snake query test server",
      host = "127.0.0.1",
      port = 0,
    )
    val headers = Map(A2AHeader.Version -> A2AProtocol.Version)

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          pageSize <- fetchText(
            server.url + "/tasks?page_size=0",
            headers = headers,
          )
          historyLength <- fetchText(
            server.url + "/tasks?history_length=-1",
            headers = headers,
          )
          pageToken <- fetchText(
            server.url + "/tasks?page_token=not-a-number",
            headers = headers,
          )
          includeArtifacts <- fetchText(
            server.url + "/tasks?include_artifacts=maybe",
            headers = headers,
          )
          statusTimestamp <- fetchText(
            server.url + "/tasks?status_timestamp_after=not-a-timestamp",
            headers = headers,
          )
          unspecifiedStatus <- fetchText(
            server.url + "/tasks?status=TASK_STATE_UNSPECIFIED",
            headers = headers,
          )
        yield (pageSize, historyLength, pageToken, includeArtifacts, statusTimestamp, unspecifiedStatus)
      }

    runTask(program).map {
      case (
            (pageSizeStatus, _, pageSizeBody),
            (historyStatus, _, historyBody),
            (tokenStatus, _, tokenBody),
            (includeStatus, _, includeBody),
            (timestampStatus, _, timestampBody),
            (unspecifiedStatus, _, unspecifiedBody),
          ) =>
        assertEquals(pageSizeStatus, 400)
        assert(pageSizeBody.contains("pageSize must be between 1 and 100"))
        assertEquals(historyStatus, 400)
        assert(historyBody.contains("historyLength must be non-negative"))
        assertEquals(tokenStatus, 400)
        assert(tokenBody.contains("Invalid pageToken"))
        assertEquals(includeStatus, 400)
        assert(includeBody.contains("includeArtifacts must be a valid boolean"))
        assertEquals(timestampStatus, 400)
        assert(timestampBody.contains("statusTimestampAfter must be an ISO 8601 UTC timestamp"))
        assertEquals(unspecifiedStatus, 400)
        assert(unspecifiedBody.contains("status must be one of"))
    }
end A2ARestTransportSpec
