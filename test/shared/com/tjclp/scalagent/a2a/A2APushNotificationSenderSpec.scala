package com.tjclp.scalagent.a2a

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import munit.FunSuite
import zio.*

class A2APushNotificationSenderSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  test("callback headers prefer explicit authentication over notification tokens"):
    val authConfig = TaskPushNotificationConfig(
      url = "https://callback.test",
      token = Some("token-1"),
      authentication = Some(AuthenticationInfo("Bearer", "secret")),
    )
    val tokenConfig = TaskPushNotificationConfig(
      url = "https://callback.test",
      token = Some("token-1"),
    )

    assertEquals(
      A2APushNotificationSender.callbackHeaders(authConfig),
      List(
        "Content-Type"  -> A2AContentType.A2AJson,
        "Authorization" -> "Bearer secret",
      ),
    )
    assertEquals(
      A2APushNotificationSender.callbackHeaders(tokenConfig),
      List(
        "Content-Type"              -> A2AContentType.A2AJson,
        "X-A2A-Notification-Token" -> "token-1",
      ),
    )

  test("ordered sender serializes callbacks for a tenant task"):
    val taskId    = TaskId("task-1")
    val contextId = ContextId("ctx-1")
    val context   = ServerCallContext(tenant = Some("tenant-a"))

    val program =
      for
        store     <- ZIO.succeed(A2APushNotificationStore.inMemory)
        _         <- store.save(taskId, context.tenant, TaskPushNotificationConfig(url = "https://callback.test"))
        started   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        delivered <- Queue.unbounded[String]
        poster = new A2APushNotificationPoster:
          def post(
            event: A2AResponse.StreamEvent,
            config: TaskPushNotificationConfig,
            headers: List[(String, String)],
          ): Task[Unit] =
            val text = eventText(event)
            if text == "first" then started.succeed(()) *> release.await *> delivered.offer(text).unit
            else delivered.offer(text).unit
        sender = A2APushNotificationSender.live(store, PushNotificationUrlPolicy.allowAll, poster)
        _      <- sender.send(statusEvent(taskId, contextId, "first"), context)
        _      <- started.await.timeoutFail(new RuntimeException("first callback did not start"))(1.second)
        _      <- sender.send(statusEvent(taskId, contextId, "second"), context)
        early  <- delivered.take.timeout(50.millis)
        _      <- ZIO.when(early.nonEmpty)(ZIO.fail(new RuntimeException(s"callback delivered out of order: $early")))
        _      <- release.succeed(())
        first  <- delivered.take.timeoutFail(new RuntimeException("first callback did not complete"))(1.second)
        second <- delivered.take.timeoutFail(new RuntimeException("second callback did not complete"))(1.second)
      yield List(first, second)

    runTask(program).map(delivered => assertEquals(delivered, List("first", "second")))

  test("ordered sender retries transient callback failures"):
    val taskId    = TaskId("task-retry")
    val contextId = ContextId("ctx-retry")
    val context   = ServerCallContext(tenant = Some("tenant-a"))

    val program =
      for
        store     <- ZIO.succeed(A2APushNotificationStore.inMemory)
        _         <- store.save(taskId, context.tenant, TaskPushNotificationConfig(url = "https://callback.test"))
        attempts  <- Ref.make(0)
        delivered <- Queue.unbounded[Int]
        poster = new A2APushNotificationPoster:
          def post(
            event: A2AResponse.StreamEvent,
            config: TaskPushNotificationConfig,
            headers: List[(String, String)],
          ): Task[Unit] =
            attempts.updateAndGet(_ + 1).flatMap { count =>
              if count == 1 then ZIO.fail(new RuntimeException("transient callback failure"))
              else delivered.offer(count).unit
            }
        sender = A2APushNotificationSender.live(
          store,
          PushNotificationUrlPolicy.allowAll,
          poster,
          postTimeout = 1.second,
          retrySchedule = Schedule.recurs(1) && Schedule.spaced(10.millis),
        )
        _        <- sender.send(statusEvent(taskId, contextId, "retry"), context)
        deliveredAttempt <- delivered.take.timeoutFail(new RuntimeException("retry callback did not complete"))(1.second)
        attemptCount     <- attempts.get
      yield (deliveredAttempt, attemptCount)

    runTask(program).map { case (deliveredAttempt, attemptCount) =>
      assertEquals(deliveredAttempt, 2)
      assertEquals(attemptCount, 2)
    }

  test("ordered sender bounds a stuck callback before later callbacks"):
    val taskId    = TaskId("task-timeout")
    val contextId = ContextId("ctx-timeout")
    val context   = ServerCallContext(tenant = Some("tenant-a"))

    val program =
      for
        store     <- ZIO.succeed(A2APushNotificationStore.inMemory)
        _         <- store.save(taskId, context.tenant, TaskPushNotificationConfig(url = "https://callback.test"))
        started   <- Promise.make[Nothing, Unit]
        delivered <- Queue.unbounded[String]
        poster = new A2APushNotificationPoster:
          def post(
            event: A2AResponse.StreamEvent,
            config: TaskPushNotificationConfig,
            headers: List[(String, String)],
          ): Task[Unit] =
            eventText(event) match
              case "first" => started.succeed(()) *> ZIO.never
              case text    => delivered.offer(text).unit
        sender = A2APushNotificationSender.live(
          store,
          PushNotificationUrlPolicy.allowAll,
          poster,
          postTimeout = 50.millis,
          retrySchedule = Schedule.stop,
        )
        _      <- sender.send(statusEvent(taskId, contextId, "first"), context)
        _      <- started.await.timeoutFail(new RuntimeException("stuck callback did not start"))(1.second)
        _      <- sender.send(statusEvent(taskId, contextId, "second"), context)
        second <- delivered.take.timeoutFail(new RuntimeException("second callback did not run after timeout"))(1.second)
      yield second

    runTask(program).map(second => assertEquals(second, "second"))

  private def statusEvent(
    taskId: TaskId,
    contextId: ContextId,
    text: String,
  ): A2AResponse.StreamEvent =
    A2AResponse.StreamEvent.TaskStatusUpdate(
      taskId,
      contextId,
      TaskStatus.working(Some(A2AMessage.agentText(text, Some(contextId)).copy(taskId = Some(taskId)))),
    )

  private def eventText(event: A2AResponse.StreamEvent): String =
    event match
      case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, _, _) =>
        status.message.map(_.text).getOrElse("")
      case _ =>
        ""
end A2APushNotificationSenderSpec
