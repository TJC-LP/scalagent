package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random
import zio.*
import zio.json.ast.Json
import zio.stream.*

class A2AServerOperationSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def completedExecution(
    message: A2AMessage,
    taskId: TaskId,
    contextId: ContextId,
    publisher: A2AEventPublisher,
  ): Task[Unit] =
    val artifact = Artifact(
      artifactId = "artifact-1",
      parts = List(Part.Text("artifact body")),
      name = Some("result.txt"),
    )
    val response = A2AMessage.agentText(s"done: ${message.text}", Some(contextId)).copy(taskId = Some(taskId))
    publisher.publish(A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact)) *>
      publisher.publish(
        A2AResponse.StreamEvent.TaskStatusUpdate(
          taskId,
          contextId,
          TaskStatus.completed(response),
          `final` = true,
        )
      )

  test("blocking send stores artifacts, history, and list filters"):
    val port = 49500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "OperationsTest",
      description = "Operations test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(completedExecution),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          task   <- client.send(A2AMessage.userText("hello"))
          full   <- client.getTask(task.id)
          tail   <- client.getTask(task.id, Some(1))
          listed <- client.listTasks(
            A2ARequest.TasksList(
              contextId = Some(task.contextId),
              status = Some(TaskState.Completed),
              includeArtifacts = Some(true),
            )
          )
          wrongState <- client.listTasks(A2ARequest.TasksList(status = Some(TaskState.Failed)))
        yield (task, full, tail, listed, wrongState)
      }

    runTask(program).map { case (task, full, tail, listed, wrongState) =>
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(full.artifacts.map(_.artifactId), List("artifact-1"))
      assertEquals(tail.history.length, 1)
      assertEquals(listed.tasks.map(_.id), List(task.id))
      assertEquals(listed.tasks.head.artifacts.map(_.artifactId), List("artifact-1"))
      assertEquals(wrongState.tasks, Nil)
    }

  test("nonblocking send returns a working task while execution continues"):
    val port = 50500 + Random.nextInt(1000)

    val program =
      ZIO.scoped {
        for
          completed <- Promise.make[Nothing, Unit]
          delayed =
            (_: A2AMessage, taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher) =>
              ZIO.sleep(100.millis) *>
                completedExecution(A2AMessage.userText("async"), taskId, contextId, publisher) *>
                completed.succeed(()).unit
          config = A2AServer.Config(
            name = "AsyncOperationsTest",
            description = "Async operations test server",
            host = "127.0.0.1",
            port = port,
            executionOverride = Some(delayed),
          )
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          first  <- client.submit(A2AMessage.userText("run async"))
          _      <- completed.await
          finalTask <- client.awaitTask(first.id, pollEvery = 10.millis, timeout = Some(2.seconds))
        yield (first, finalTask)
      }

    runTask(program).map { case (first, finalTask) =>
      assertEquals(first.status.state, TaskState.Working)
      assertEquals(finalTask.status.state, TaskState.Completed)
    }

  test("cancel interrupts an active task and persists canceled state"):
    val port = 51500 + Random.nextInt(1000)
    val neverComplete =
      (_: A2AMessage, _: TaskId, _: ContextId, _: A2AEventPublisher) => ZIO.never

    val config = A2AServer.Config(
      name = "CancelOperationsTest",
      description = "Cancel operations test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(neverComplete),
    )

    val program =
      ZIO.scoped {
        for
          subscribed <- Promise.make[Nothing, Unit]
          server   <- A2AServer.create(config)
          client   <- A2AClient.discover(server.url)
          task     <- client.submit(A2AMessage.userText("cancel me"))
          streamFiber <- client
            .resubscribe(task.id)
            .tap(_ => subscribed.succeed(()).unit)
            .runCollect
            .fork
          _        <- subscribed.await
          canceled <- client.cancelTask(task.id)
          events <- streamFiber.join.timeoutFail(new RuntimeException("cancel stream did not finish"))(2.seconds)
          loaded   <- client.getTask(task.id)
        yield (canceled, loaded, events.toList)
      }

    runTask(program).map { case (canceled, loaded, events) =>
      assertEquals(canceled.status.state, TaskState.Canceled)
      assertEquals(loaded.status.state, TaskState.Canceled)
      assert(events.exists {
        case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, _, _) => status.state == TaskState.Canceled
        case _                                                           => false
      })
    }

  test("resubscribe starts with a task snapshot and rejects terminal tasks"):
    val port = 52500 + Random.nextInt(1000)
    val delayed =
      (_: A2AMessage, taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher) =>
        ZIO.sleep(150.millis) *> completedExecution(A2AMessage.userText("resubscribe"), taskId, contextId, publisher)

    val config = A2AServer.Config(
      name = "SubscribeOperationsTest",
      description = "Subscribe operations test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(delayed),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          task   <- client.submit(A2AMessage.userText("subscribe"))
          first  <- client.resubscribe(task.id).runHead
          done   <- client.awaitTask(task.id, pollEvery = 10.millis, timeout = Some(2.seconds))
          rejected <- client.resubscribe(done.id).runHead.either
        yield (task, first, rejected)
      }

    runTask(program).map { case (task, first, rejected) =>
      assertEquals(first, Some(A2AResponse.StreamEvent.TaskSnapshot(task)))
      assert(rejected.left.exists {
        case error: A2AError => error.code == A2AErrorCode.UnsupportedOperation
        case _               => false
      })
    }

  test("tenant-scoped clients do not see each other's tasks"):
    val port = 53500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "TenantOperationsTest",
      description = "Tenant operations test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(completedExecution),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          card    = server.agentCard
          clientA <- A2AClient.fromCard(card.copy(supportedInterfaces = List(AgentInterface.jsonRpc(server.url, Some("tenant-a")))))
          clientB <- A2AClient.fromCard(card.copy(supportedInterfaces = List(AgentInterface.jsonRpc(server.url, Some("tenant-b")))))
          taskA   <- clientA.send(A2AMessage.userText("tenant a"))
          listA   <- clientA.listTasks()
          listB   <- clientB.listTasks()
        yield (taskA, listA, listB)
      }

    runTask(program).map { case (taskA, listA, listB) =>
      assertEquals(listA.tasks.map(_.id), List(taskA.id))
      assertEquals(listB.tasks, Nil)
    }

  test("tenant-scoped active runs with the same task id do not interrupt each other"):
    val port     = 54500 + Random.nextInt(1000)
    val sharedId = TaskId("shared-task")

    val program =
      ZIO.scoped {
        for
          completeTenantB <- Promise.make[Nothing, Unit]
          runOverride =
            (message: A2AMessage, taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher) =>
              if message.text == "tenant-b" then completeTenantB.await *> completedExecution(message, taskId, contextId, publisher)
              else ZIO.never
          config = A2AServer.Config(
            name = "TenantActiveRunTest",
            description = "Tenant active run test server",
            host = "127.0.0.1",
            port = port,
            executionOverride = Some(runOverride),
          )
          server <- A2AServer.create(config)
          card    = server.agentCard
          clientA <- A2AClient.fromCard(card.copy(supportedInterfaces = List(AgentInterface.jsonRpc(server.url, Some("tenant-a")))))
          clientB <- A2AClient.fromCard(card.copy(supportedInterfaces = List(AgentInterface.jsonRpc(server.url, Some("tenant-b")))))
          _       <- clientA.submit(A2AMessage.userText("tenant-a").copy(taskId = Some(sharedId)))
          _       <- clientB.submit(A2AMessage.userText("tenant-b").copy(taskId = Some(sharedId)))
          canceledA <- clientA.cancelTask(sharedId)
          _         <- completeTenantB.succeed(()).unit
          finalB    <- clientB.awaitTask(sharedId, pollEvery = 10.millis, timeout = Some(2.seconds))
          loadedA   <- clientA.getTask(sharedId)
        yield (canceledA, loadedA, finalB)
      }

    runTask(program).map { case (canceledA, loadedA, finalB) =>
      assertEquals(canceledA.status.state, TaskState.Canceled)
      assertEquals(loadedA.status.state, TaskState.Canceled)
      assertEquals(finalB.status.state, TaskState.Completed)
      assertEquals(finalB.status.message.map(_.text), Some("done: tenant-b"))
    }
end A2AServerOperationSpec
