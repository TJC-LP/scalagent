package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.util.Random
import zio.*
import zio.json.*

class A2APushNotificationSpec extends FunSuite:
  private val runtime = Runtime.default

  private final case class CallbackRecord(
    body: String,
    contentType: Option[String],
    authorization: Option[String])

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def header(headers: js.Dynamic, name: String): Option[String] =
    val value = headers.get(name)
    if js.isUndefined(value) || value == null then None
    else Some(value.asInstanceOf[String])

  private def callbackServer(port: Int, records: ListBuffer[CallbackRecord]): Task[js.Dynamic] =
    ZIO.attempt {
      val Response = js.Dynamic.global.Response
      PushTestBun.serve(
        js.Dynamic.literal(
          hostname = "127.0.0.1",
          port = port,
          fetch = { (req: js.Dynamic) =>
            val headers = req.headers.asInstanceOf[js.Dynamic]
            req
              .text()
              .asInstanceOf[js.Promise[String]]
              .`then`[js.Dynamic] { body =>
                records += CallbackRecord(
                  body = body,
                  contentType = header(headers, "content-type"),
                  authorization = header(headers, "authorization"),
                )
                js.Dynamic.newInstance(Response)("ok", js.Dynamic.literal(status = 200))
              }
          }: js.Function1[js.Dynamic, js.Promise[js.Dynamic]],
        )
      )
    }

  private def completeImmediately(
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

  private def waitFor(label: String, attempts: Int = 80)(predicate: => Boolean): Task[Unit] =
    if predicate then ZIO.unit
    else if attempts <= 0 then ZIO.fail(new RuntimeException(s"Timed out waiting for $label"))
    else ZIO.sleep(25.millis) *> waitFor(label, attempts - 1)(predicate)

  test("inline push notifications POST StreamResponse bodies with configured auth"):
    val serverPort   = 45500 + Random.nextInt(1000)
    val callbackPort = 46500 + Random.nextInt(1000)
    val records      = ListBuffer.empty[CallbackRecord]

    val config = A2AServer.Config(
      name = "PushTest",
      description = "Push test server",
      host = "127.0.0.1",
      port = serverPort,
      capabilities = AgentCapabilities.default.copy(pushNotifications = true),
      executionOverride = Some(completeImmediately),
    )

    val program =
      ZIO.scoped {
        for
          callback <- ZIO.acquireRelease(callbackServer(callbackPort, records))(server => ZIO.attempt(server.stop()).ignore)
          server   <- A2AServer.create(config)
          client   <- A2AClient.discover(server.url)
          task <- client.send(
            A2AMessage.userText("notify me"),
            Some(
              MessageSendConfiguration(
                taskPushNotificationConfig = Some(
                  TaskPushNotificationConfig(
                    url = s"http://127.0.0.1:$callbackPort",
                    authentication = Some(AuthenticationInfo("Bearer", "secret")),
                  )
                )
              )
            ),
          )
          _ <- waitFor("push callbacks")(records.size >= 2)
        yield (callback, task, records.toList)
      }

    runTask(program).map { case (_, task, delivered) =>
      assertEquals(task.status.state, TaskState.Completed)
      assert(delivered.forall(_.contentType.exists(_.startsWith(A2AContentType.A2AJson))))
      assert(delivered.forall(_.authorization.contains("Bearer secret")))

      val events = delivered.map(record => record.body.fromJson[A2AResponse.StreamEvent])
      assert(events.forall(_.isRight))
      assert(events.head.exists(_.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot]))
      assert(events.last.exists {
        case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, _, _) => status.state == TaskState.Completed
        case _                                                           => false
      })
    }

  test("out-of-band push config create/get/list/delete uses v1 client names"):
    val port = 47500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "PushCrudTest",
      description = "Push CRUD test server",
      host = "127.0.0.1",
      port = port,
      capabilities = AgentCapabilities.default.copy(pushNotifications = true),
      executionOverride = Some(completeImmediately),
    )

    val program =
      ZIO.scoped {
        for
          server  <- A2AServer.create(config)
          client  <- A2AClient.discover(server.url)
          task    <- client.send(A2AMessage.userText("create push config"))
          created <- client.createTaskPushNotificationConfig(task.id, TaskPushNotificationConfig(url = "http://callback.test", id = Some("cfg-1")))
          fetched <- client.getTaskPushNotificationConfig(task.id, "cfg-1")
          listed  <- client.listTaskPushNotificationConfigs(task.id)
          _       <- client.deleteTaskPushNotificationConfig(task.id, "cfg-1")
          after   <- client.listTaskPushNotificationConfigs(task.id)
        yield (created, fetched, listed, after)
      }

    runTask(program).map { case (created, fetched, listed, after) =>
      assertEquals(created.id, Some("cfg-1"))
      assertEquals(fetched, created)
      assertEquals(listed, List(created))
      assertEquals(after, Nil)
    }

  test("push operations fail when capability is disabled"):
    val port = 48500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "PushDisabledTest",
      description = "Push disabled test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(completeImmediately),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          result <- client.createTaskPushNotificationConfig(TaskId("task-1"), TaskPushNotificationConfig(url = "http://callback.test")).either
        yield result
      }

    runTask(program).map { result =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.PushNotificationNotSupported
        case _               => false
      })
    }
end A2APushNotificationSpec

@js.native
@JSGlobal("Bun")
private object PushTestBun extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
