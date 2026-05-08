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

  test("send message applies historyLength to immediate and blocking responses"):
    val port = 55500 + Random.nextInt(1000)
    val config = A2AServer.Config(
      name = "HistoryProjectionTest",
      description = "History projection test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(completedExecution),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          zero <- client.send(
            A2AMessage.userText("blocking zero"),
            Some(MessageSendConfiguration(historyLength = Some(0))),
          )
          one <- client.send(
            A2AMessage.userText("blocking one"),
            Some(MessageSendConfiguration(historyLength = Some(1))),
          )
          immediate <- client.submit(
            A2AMessage.userText("immediate zero"),
            Some(MessageSendConfiguration(historyLength = Some(0))),
          )
          full <- client.awaitTask(immediate.id, pollEvery = 10.millis, timeout = Some(2.seconds))
        yield (zero, one, immediate, full)
      }

    runTask(program).map { case (zero, one, immediate, full) =>
      assertEquals(zero.history, Nil)
      assertEquals(one.history.length, 1)
      assertEquals(one.history.head.role, A2ARole.Agent)
      assertEquals(immediate.history, Nil)
      assert(full.history.nonEmpty)
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

  test("cancel awaits uninterruptible execution and prevents late completion from winning"):
    val port = 56500 + Random.nextInt(1000)

    val program =
      ZIO.scoped {
        for
          started <- Promise.make[Nothing, Unit]
          runOverride =
            (message: A2AMessage, taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher) =>
              started.succeed(()).unit *>
                ZIO.uninterruptible(ZIO.sleep(100.millis) *> completedExecution(message, taskId, contextId, publisher))
          config = A2AServer.Config(
            name = "CancelLateCompletionTest",
            description = "Cancel late completion test server",
            host = "127.0.0.1",
            port = port,
            executionOverride = Some(runOverride),
          )
          server   <- A2AServer.create(config)
          client   <- A2AClient.discover(server.url)
          task     <- client.submit(A2AMessage.userText("cancel during finalization"))
          _        <- started.await
          canceled <- client.cancelTask(task.id)
          loaded   <- client.getTask(task.id)
        yield (canceled, loaded)
      }

    runTask(program).map { case (canceled, loaded) =>
      assertEquals(canceled.status.state, TaskState.Canceled)
      assertEquals(loaded.status.state, TaskState.Canceled)
    }

  test("concurrent sends with the same task id reject the duplicate active run"):
    val port     = 57500 + Random.nextInt(1000)
    val sharedId = TaskId("duplicate-active-run")
    val neverComplete =
      (_: A2AMessage, _: TaskId, _: ContextId, _: A2AEventPublisher) => ZIO.never
    val config = A2AServer.Config(
      name = "DuplicateActiveRunTest",
      description = "Duplicate active run test server",
      host = "127.0.0.1",
      port = port,
      executionOverride = Some(neverComplete),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          _      <- client.submit(A2AMessage.userText("first").copy(taskId = Some(sharedId)))
          second <- client.submit(A2AMessage.userText("second").copy(taskId = Some(sharedId))).either
          _      <- client.cancelTask(sharedId).either
        yield second
      }

    runTask(program).map { second =>
      assert(second.left.exists {
        case error: A2AError => error.code == A2AErrorCode.UnsupportedOperation
        case _               => false
      })
    }

  test("event bus replay is capped to the configured limit"):
    val port = 58500 + Random.nextInt(1000)

    val program =
      ZIO.scoped {
        for
          published <- Promise.make[Nothing, Unit]
          runOverride =
            (_: A2AMessage, taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher) =>
              def update(text: String) =
                val msg = A2AMessage.agentText(text, Some(contextId)).copy(taskId = Some(taskId))
                A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, TaskStatus.working(Some(msg)))
              publisher.publish(update("one")) *>
                publisher.publish(update("two")) *>
                publisher.publish(update("three")) *>
                published.succeed(()).unit *>
                ZIO.never
          config = A2AServer.Config(
            name = "ReplayLimitTest",
            description = "Replay limit test server",
            host = "127.0.0.1",
            port = port,
            capabilities = AgentCapabilities.default.copy(streaming = true),
            executionOverride = Some(runOverride),
            eventReplayLimit = 2,
          )
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          task   <- client.submit(A2AMessage.userText("replay"))
          _      <- published.await
          events <- client.resubscribe(task.id).take(3).runCollect
          _      <- client.cancelTask(task.id).either
        yield events.toList
      }

    runTask(program).map { events =>
      assert(events.headOption.exists(_.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot]))
      val replayed = events.collect {
        case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, _, _) => status.message.map(_.text).getOrElse("")
      }
      assertEquals(replayed, List("two", "three"))
    }

  test("resubscribe starts with a task snapshot and replays terminal tasks"):
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
          terminalFirst <- client.resubscribe(done.id).runHead
        yield (task, first, done, terminalFirst)
      }

    runTask(program).map { case (task, first, done, terminalFirst) =>
      assertEquals(first, Some(A2AResponse.StreamEvent.TaskSnapshot(task)))
      assertEquals(terminalFirst, Some(A2AResponse.StreamEvent.TaskSnapshot(done)))
    }

  test("durable event store records published events and replays terminal streams"):
    val port = 53500 + Random.nextInt(1000)

    val program =
      ZIO.scoped {
        for
          ref <- Ref.make(Vector.empty[A2AResponse.StreamEvent])
          store = new A2AEventStore:
            override def append(
              taskId: TaskId,
              tenant: Option[String],
              event: A2AResponse.StreamEvent,
            ): UIO[Unit] =
              val _ = (taskId, tenant)
              ref.update(_ :+ event)

            override def load(
              taskId: TaskId,
              tenant: Option[String],
              limit: Int,
            ): UIO[List[A2AResponse.StreamEvent]] =
              val _ = (taskId, tenant)
              ref.get.map(_.takeRight(limit).toList)
          config = A2AServer.Config(
            name = "DurableEventStoreTest",
            description = "Durable event store test server",
            host = "127.0.0.1",
            port = port,
            capabilities = AgentCapabilities.default.copy(streaming = true),
            executionOverride = Some(completedExecution),
            eventStore = Some(store),
          )
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          task   <- client.send(A2AMessage.userText("durable"))
          replay <- client.resubscribe(task.id).take(3).runCollect
          stored <- ref.get
        yield (task, replay.toList, stored.toList)
      }

    runTask(program).map { case (task, replay, stored) =>
      assert(stored.exists(_.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot]))
      assert(stored.exists(_.isInstanceOf[A2AResponse.StreamEvent.TaskArtifactUpdate]))
      assert(stored.exists(_.isFinal))
      assertEquals(replay.headOption, Some(A2AResponse.StreamEvent.TaskSnapshot(task)))
      assert(replay.exists(_.isInstanceOf[A2AResponse.StreamEvent.TaskArtifactUpdate]))
      assert(replay.exists(_.isFinal))
    }

  test("cancellation events are persisted to the durable event store"):
    val port = 54500 + Random.nextInt(1000)

    val program =
      ZIO.scoped {
        for
          ref <- Ref.make(Vector.empty[A2AResponse.StreamEvent])
          store = new A2AEventStore:
            override def append(
              taskId: TaskId,
              tenant: Option[String],
              event: A2AResponse.StreamEvent,
            ): UIO[Unit] =
              val _ = (taskId, tenant)
              ref.update(_ :+ event)

            override def load(
              taskId: TaskId,
              tenant: Option[String],
              limit: Int,
            ): UIO[List[A2AResponse.StreamEvent]] =
              val _ = (taskId, tenant)
              ref.get.map(_.takeRight(limit).toList)
          config = A2AServer.Config(
            name = "CancelPersistTest",
            description = "Cancel persistence test server",
            host = "127.0.0.1",
            port = port,
            capabilities = AgentCapabilities.default.copy(streaming = true),
            // Long-running execution that will be cancelled
            executionOverride = Some((message, taskId, contextId, publisher) =>
              ZIO.never *> publisher.publish(
                A2AResponse.StreamEvent.TaskStatusUpdate(
                  taskId,
                  contextId,
                  TaskStatus.completed(A2AMessage.agentText("never", Some(contextId)).copy(taskId = Some(taskId))),
                  `final` = true,
                ),
              ),
            ),
            eventStore = Some(store),
          )
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          task   <- client.submit(A2AMessage.userText("cancel-persist"))
          _      <- ZIO.sleep(100.millis)
          _      <- client.cancelTask(task.id)
          // Give the persister daemon a beat to drain the cancel event.
          _      <- ZIO.sleep(150.millis)
          stored <- ref.get
        yield stored.toList
      }

    runTask(program).map { stored =>
      val canceledFinal = stored.collect {
        case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, isFinal, _) if status.state == TaskState.Canceled =>
          isFinal
      }
      assert(canceledFinal.nonEmpty, s"expected at least one canceled status update; got ${stored.map(_.getClass.getSimpleName)}")
      assert(canceledFinal.contains(true), s"expected the canceled status update to carry final=true")
    }

  test("resubscribe prefers replayProvider when both replayProvider and eventStore are configured"):
    val port = 56500 + Random.nextInt(1000)

    val program =
      ZIO.scoped {
        for
          // Store: visited only if precedence is wrong.
          storeRef <- Ref.make(Vector.empty[A2AResponse.StreamEvent])
          storeLoadCount <- Ref.make(0)
          store = new A2AEventStore:
            override def append(
              taskId: TaskId,
              tenant: Option[String],
              event: A2AResponse.StreamEvent,
            ): UIO[Unit] =
              val _ = (taskId, tenant)
              storeRef.update(_ :+ event)

            override def load(
              taskId: TaskId,
              tenant: Option[String],
              limit: Int,
            ): UIO[List[A2AResponse.StreamEvent]] =
              val _ = (taskId, tenant, limit)
              storeLoadCount.update(_ + 1) *> storeRef.get.map(_.toList)
          // Provider yields a sentinel event the store would never emit.
          providerCalled <- Ref.make(false)
          provider = new A2AReplayProvider:
            override def replay(
              task: A2ATask,
              tenant: Option[String],
            ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
              val _ = tenant
              ZStream.fromZIO(providerCalled.set(true)) *>
                ZStream.succeed(
                  A2AResponse.StreamEvent.TaskMessage(
                    task.id,
                    task.contextId,
                    A2AMessage.agentText("PROVIDER_SENTINEL", Some(task.contextId)).copy(taskId = Some(task.id)),
                  ),
                )
          config = A2AServer.Config(
            name = "ReplayPrecedenceTest",
            description = "Replay precedence test server",
            host = "127.0.0.1",
            port = port,
            capabilities = AgentCapabilities.default.copy(streaming = true),
            executionOverride = Some(completedExecution),
            eventStore = Some(store),
            replayProvider = Some(provider),
          )
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          // `send` blocks until terminal; the executor's `ensuring` removes
          // the runtime entry so resubscribe falls into durableReplay.
          task              <- client.send(A2AMessage.userText("precedence"))
          replay            <- client.resubscribe(task.id).runCollect
          providerWasCalled <- providerCalled.get
          loadCount         <- storeLoadCount.get
        yield (replay.toList, providerWasCalled, loadCount)
      }

    runTask(program).map { case (replay, providerWasCalled, loadCount) =>
      assert(providerWasCalled, "replayProvider was not invoked despite being configured alongside eventStore")
      assertEquals(loadCount, 0, "eventStore.load must not be called when replayProvider is configured")
      val sentinelText = replay.toList.collect {
        case A2AResponse.StreamEvent.TaskMessage(_, _, message) => message.text
      }
      assert(
        sentinelText.contains("PROVIDER_SENTINEL"),
        s"expected provider sentinel in replay; got $sentinelText",
      )
    }

  test("event store failures do not break execution"):
    val port = 58500 + Random.nextInt(1000)

    val program =
      ZIO.scoped {
        for
          // Store that always dies (uncaught defect).
          store = new A2AEventStore:
            override def append(
              taskId: TaskId,
              tenant: Option[String],
              event: A2AResponse.StreamEvent,
            ): UIO[Unit] =
              val _ = (taskId, tenant, event)
              ZIO.die(new RuntimeException("store is broken"))

            override def load(
              taskId: TaskId,
              tenant: Option[String],
              limit: Int,
            ): UIO[List[A2AResponse.StreamEvent]] =
              val _ = (taskId, tenant, limit)
              ZIO.die(new RuntimeException("store is broken"))
          config = A2AServer.Config(
            name = "BrokenStoreTest",
            description = "Broken store test server",
            host = "127.0.0.1",
            port = port,
            capabilities = AgentCapabilities.default.copy(streaming = true),
            executionOverride = Some(completedExecution),
            eventStore = Some(store),
          )
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          task   <- client.send(A2AMessage.userText("broken store"))
        yield task
      }

    runTask(program).map { task =>
      assertEquals(task.status.state, TaskState.Completed)
      assertEquals(task.artifacts.size, 1)
    }

  test("tenant-scoped clients do not see each other's tasks"):
    val port = 57500 + Random.nextInt(1000)
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
