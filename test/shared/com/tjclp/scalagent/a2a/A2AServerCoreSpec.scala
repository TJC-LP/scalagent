package com.tjclp.scalagent.a2a

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.util.concurrent.TimeoutException

import munit.FunSuite

import zio.*
import zio.json.*

class A2AServerCoreSpec extends FunSuite:
  private val runtime = Runtime.default

  private final case class TestConfig(
    capabilities: AgentCapabilities = AgentCapabilities.default,
    extendedAgentCard: Option[AgentCard] = None,
    pushNotificationStore: Option[A2APushNotificationStore] = None,
    taskStore: Option[A2ATaskStore] = None,
    eventStore: Option[A2AEventStore] = None,
    replayProvider: Option[A2AReplayProvider] = None,
    eventReplayLimit: Int = A2AServerDefaults.EventReplayLimit,
    eventStoreAppendTimeout: Duration = A2AServerDefaults.EventStoreAppendTimeout,
    eventStoreLoadTimeout: Duration = A2AServerDefaults.EventStoreLoadTimeout,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy = A2AServerDefaults.PushUrlPolicy,
    extendedAgentCardAuth: A2AExtendedAgentCardAuth = A2AExtendedAgentCardAuth.requireAuthorizationHeader,
    requestAuth: A2ARequestAuth = A2ARequestAuth.requireAuthorizationWhenAdvertised,
    messageResponseSelectorOverride: Option[A2ARequest.MessageSend => Task[Option[A2AMessage]]] = None,
    override val messageResponseOverride: Option[A2ARequest.MessageSend => Task[A2AMessage]] = None)
      extends A2AServerCoreConfig:
    override def messageResponseSelector: Option[A2ARequest.MessageSend => Task[Option[A2AMessage]]] =
      messageResponseSelectorOverride.orElse(super.messageResponseSelector)

  private object NoopPushPoster extends A2APushNotificationPoster:
    def post(
      event: A2AResponse.StreamEvent,
      config: TaskPushNotificationConfig,
      headers: List[(String, String)],
    ): Task[Unit] =
      ZIO.unit

  private object NoopPublisher extends A2AEventPublisher:
    def publish(event: A2AResponse.StreamEvent): UIO[Unit] = ZIO.unit
    def finish: UIO[Unit]                                 = ZIO.unit

  private object NoopPushSender extends A2APushNotificationSender:
    def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit] =
      ZIO.unit

  private def runTask[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
    }

  private def card(name: String, capabilities: AgentCapabilities = AgentCapabilities.default): AgentCard =
    A2AServerAgentCard(
      name = name,
      description = s"$name description",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = capabilities,
      skills = Nil,
      tenant = None,
    )

  private final class RecordingServer(started: Ref[Int], stopped: Ref[Int]) extends A2AServer:
    def start: Task[Unit] =
      started.update(_ + 1)

    def stop: Task[Unit] =
      stopped.update(_ + 1)

    def agentCard: AgentCard =
      card("Lifecycle")

    def url: String =
      "https://agent.example.test/a2a"

  test("shared server lifecycle starts constructed servers"):
    val effect =
      for
        started <- Ref.make(0)
        stopped <- Ref.make(0)
        server  <- A2AServerLifecycle.start(_ => ZIO.succeed(RecordingServer(started, stopped)))
        counts  <- started.get.zip(stopped.get)
      yield server.url -> counts

    runTask(effect).map { case (url, (started, stopped)) =>
      assertEquals(url, "https://agent.example.test/a2a")
      assertEquals(started, 1)
      assertEquals(stopped, 0)
    }

  test("shared server lifecycle releases scoped servers"):
    val effect =
      for
        started <- Ref.make(0)
        stopped <- Ref.make(0)
        _       <- ZIO.scoped {
          A2AServerLifecycle
            .create(_ =>
              ZIO
                .succeed(RecordingServer(started, stopped))
                .flatMap(server => server.start.as(server))
            )
            .unit
        }
        counts <- started.get.zip(stopped.get)
      yield counts

    runTask(effect).map { case (started, stopped) =>
      assertEquals(started, 1)
      assertEquals(stopped, 1)
    }

  test("shared lifecycle startOnce and stopOnce are idempotent"):
    val effect =
      for
        acquired <- Ref.make(0)
        released <- Ref.make(0)
        resource <- Ref.Synchronized.make(Option.empty[String])
        _        <- A2AServerLifecycle.startOnce(resource)(acquired.updateAndGet(_ + 1).map(value => s"resource-$value"))
        first    <- resource.get
        _        <- A2AServerLifecycle.startOnce(resource)(acquired.updateAndGet(_ + 1).map(value => s"resource-$value"))
        second   <- resource.get
        _        <- A2AServerLifecycle.stopOnce(resource)(_ => released.update(_ + 1))
        stopped  <- resource.get
        _        <- A2AServerLifecycle.stopOnce(resource)(_ => released.update(_ + 1))
        counts   <- acquired.get.zip(released.get)
      yield (first, second, stopped, counts)

    runTask(effect).map { case (first, second, stopped, (acquired, released)) =>
      assertEquals(first, Some("resource-1"))
      assertEquals(second, Some("resource-1"))
      assertEquals(stopped, None)
      assertEquals(acquired, 1)
      assertEquals(released, 1)
    }

  test("shared result manager closes streams when a terminal event is published"):
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        bus = A2AEventBus(replayLimit = 10)
        userMessage = A2AMessage.userText("run")
        task = A2ATask(
          id = TaskId("terminal-close-task"),
          contextId = ContextId("terminal-close-context"),
          status = TaskStatus.working(),
          history = List(userMessage),
        )
        manager = ResultManager(taskStore, None, NoopPushSender, bus, registry, ServerCallContext(), userMessage)
        finalMessage = A2AMessage.agentText("done", Some(task.contextId)).copy(taskId = Some(task.id))
        finalEvent = A2AResponse.StreamEvent.TaskStatusUpdate(
          task.id,
          task.contextId,
          TaskStatus.completed(finalMessage),
          `final` = true,
        )
        _      <- taskStore.save(task, None)
        _      <- manager.publish(finalEvent)
        events <- bus.stream.runCollect.timeoutFail(new RuntimeException("terminal stream did not close"))(500.millis)
      yield finalEvent -> events.toList

    runTask(effect).map { case (finalEvent, events) =>
      assertEquals(events, List(finalEvent))
    }

  test("shared event bus broadcasts live events to concurrent streams in order"):
    val taskId    = TaskId("broadcast-task")
    val contextId = ContextId("broadcast-context")
    val first = A2AResponse.StreamEvent.TaskStatusUpdate(
      taskId,
      contextId,
      TaskStatus.working(Some(A2AMessage.agentText("one", Some(contextId)).copy(taskId = Some(taskId)))),
    )
    val second = A2AResponse.StreamEvent.TaskStatusUpdate(
      taskId,
      contextId,
      TaskStatus.working(Some(A2AMessage.agentText("two", Some(contextId)).copy(taskId = Some(taskId)))),
    )
    val finalEvent = A2AResponse.StreamEvent.TaskStatusUpdate(
      taskId,
      contextId,
      TaskStatus.completed(A2AMessage.agentText("done", Some(contextId)).copy(taskId = Some(taskId))),
      `final` = true,
    )

    val effect =
      for
        bus       <- ZIO.succeed(A2AEventBus(replayLimit = 10))
        ready1    <- Promise.make[Nothing, Unit]
        ready2    <- Promise.make[Nothing, Unit]
        stream1   <- bus.stream.tap(_ => ready1.succeed(())).runCollect.fork
        stream2   <- bus.stream.tap(_ => ready2.succeed(())).runCollect.fork
        _         <- bus.publish(first)
        _         <- ready1.await.zipPar(ready2.await).timeoutFail(new RuntimeException("subscribers did not receive first event"))(500.millis)
        _         <- bus.publish(second)
        _         <- bus.publish(finalEvent)
        _         <- bus.finish
        collected <- stream1.join.zipPar(stream2.join).timeoutFail(new RuntimeException("broadcast streams did not close"))(500.millis)
      yield collected

    runTask(effect).map { case (stream1, stream2) =>
      val expected = Chunk(first, second, finalEvent)
      assertEquals(stream1, expected)
      assertEquals(stream2, expected)
    }

  test("active resubscribe emits one snapshot and filters bus-replayed snapshots"):
    val effect =
      for
        taskStore  <- ZIO.succeed(A2ATaskStore.inMemory)
        registry   <- A2ARuntimeRegistry.make
        runStarted <- Promise.make[Nothing, TaskId]
        releaseRun <- Promise.make[Nothing, Unit]
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("ActiveResubscribe"),
          (prepared, publisher) =>
            val taskId    = prepared.task.id
            val contextId = prepared.task.contextId
            val working = A2AResponse.StreamEvent.TaskStatusUpdate(
              taskId,
              contextId,
              TaskStatus.working(Some(A2AMessage.agentText("working", Some(contextId)).copy(taskId = Some(taskId)))),
            )
            val finalEvent = A2AResponse.StreamEvent.TaskStatusUpdate(
              taskId,
              contextId,
              TaskStatus.completed(A2AMessage.agentText("done", Some(contextId)).copy(taskId = Some(taskId))),
              `final` = true,
            )
            runStarted.succeed(taskId) *>
              publisher.publish(working) *>
              releaseRun.await *>
              publisher.publish(finalEvent),
        )
        primary <- core.requestHandler.sendMessageStream(A2ARequest.MessageSend(A2AMessage.userText("stream")), ServerCallContext())
        primaryFiber <- primary.runCollect.fork
        taskId <- runStarted.await.timeoutFail(new RuntimeException("run did not start"))(500.millis)
        resubscribe <- core.requestHandler.resubscribe(A2ARequest.TasksResubscribe(taskId), ServerCallContext())
        resubscribeFiber <- resubscribe.runCollect.fork
        _                <- releaseRun.succeed(())
        resubscribeEvents <- resubscribeFiber.join
          .timeoutFail(new RuntimeException("resubscribe stream did not close"))(500.millis)
        _ <- primaryFiber.join.timeoutFail(new RuntimeException("primary stream did not close"))(500.millis)
      yield resubscribeEvents.toList

    runTask(effect).map { events =>
      assertEquals(events.count(_.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot]), 1)
      assert(events.exists(_.isFinal))
    }

  test("shared result manager closes streams when an interrupted event is published"):
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        bus = A2AEventBus(replayLimit = 10)
        userMessage = A2AMessage.userText("run")
        task = A2ATask(
          id = TaskId("interrupted-close-task"),
          contextId = ContextId("interrupted-close-context"),
          status = TaskStatus.working(),
          history = List(userMessage),
        )
        manager = ResultManager(taskStore, None, NoopPushSender, bus, registry, ServerCallContext(), userMessage)
        prompt = A2AMessage.agentText("need input", Some(task.contextId)).copy(taskId = Some(task.id))
        interruptedEvent = A2AResponse.StreamEvent.TaskStatusUpdate(
          task.id,
          task.contextId,
          TaskStatus.inputRequired(prompt),
        )
        _      <- taskStore.save(task, None)
        _      <- manager.publish(interruptedEvent)
        events <- bus.stream.runCollect.timeoutFail(new RuntimeException("interrupted stream did not close"))(500.millis)
      yield interruptedEvent -> events.toList

    runTask(effect).map { case (interruptedEvent, events) =>
      assertEquals(events, List(interruptedEvent))
    }

  test("shared result manager keeps terminal task state immutable after final publication"):
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        bus = A2AEventBus(replayLimit = 10)
        taskId    = TaskId("terminal-immutable-task")
        contextId = ContextId("terminal-immutable-context")
        userMessage = A2AMessage.userText("run").copy(taskId = Some(taskId), contextId = Some(contextId))
        task = A2ATask(
          id = taskId,
          contextId = contextId,
          status = TaskStatus.working(),
          history = List(userMessage),
        )
        manager = ResultManager(taskStore, None, NoopPushSender, bus, registry, ServerCallContext(), userMessage)
        finalMessage = A2AMessage.agentText("done", Some(contextId)).copy(taskId = Some(taskId))
        finalEvent = A2AResponse.StreamEvent.TaskStatusUpdate(
          taskId,
          contextId,
          TaskStatus.completed(finalMessage),
          `final` = true,
        )
        lateMessage = A2AMessage.agentText("late", Some(contextId)).copy(taskId = Some(taskId))
        lateStatus = A2AResponse.StreamEvent.TaskStatusUpdate(
          taskId,
          contextId,
          TaskStatus.working(Some(lateMessage)),
        )
        lateArtifact = A2AResponse.StreamEvent.TaskArtifactUpdate(
          taskId,
          contextId,
          Artifact("late-artifact", parts = List(Part.Text("late"))),
        )
        _      <- taskStore.save(task, None)
        _      <- manager.publish(finalEvent)
        _      <- manager.publish(lateStatus)
        _      <- manager.publish(lateArtifact)
        _      <- manager.publish(A2AResponse.StreamEvent.TaskMessage(taskId, contextId, lateMessage))
        stored <- taskStore.load(taskId, None).someOrFailException
      yield stored

    runTask(effect).map { stored =>
      assertEquals(stored.status.state, TaskState.Completed)
      assertEquals(stored.status.message.map(_.text), Some("done"))
      assertEquals(stored.artifacts, Nil)
      assertEquals(stored.history.map(_.text), List("run"))
    }

  test("shared request handler preserves inactive interrupted tasks"):
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        taskId    = TaskId("inactive-interrupted-task")
        contextId = ContextId("inactive-interrupted-context")
        prompt = A2AMessage.agentText("need input", Some(contextId)).copy(taskId = Some(taskId))
        task = A2ATask(
          id = taskId,
          contextId = contextId,
          status = TaskStatus.inputRequired(prompt),
          history = List(prompt),
        )
        _ <- taskStore.save(task, None)
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("Interrupted"),
          (_, _) => ZIO.unit,
        )
        loaded <- core.requestHandler.getTask(A2ARequest.TasksGet(taskId), ServerCallContext())
        stream <- core.requestHandler.resubscribe(A2ARequest.TasksResubscribe(taskId), ServerCallContext())
        events <- stream.runCollect.timeoutFail(new RuntimeException("interrupted replay did not close"))(500.millis)
      yield loaded -> events.toList

    runTask(effect).map { case (loaded, events) =>
      assertEquals(loaded.status.state, TaskState.InputRequired)
      assertEquals(events, List(A2AResponse.StreamEvent.TaskSnapshot(loaded)))
    }

  test("sendMessage rejects an unknown client-supplied contextId"):
    val request = A2ARequest.MessageSend(
      A2AMessage.userText("hi").copy(contextId = Some(ContextId("unknown-context"))),
    )
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("UnknownCtx"),
          (_, _) => ZIO.fail(new RuntimeException("unknown contextId must fail before execution")),
        )
        result <- core.requestHandler.sendMessage(request, ServerCallContext()).either
        tasks  <- taskStore.list(A2ARequest.TasksList(), None)
      yield result -> tasks

    runTask(effect).map { case (result, tasks) =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidParams && error.message.contains("contextId is not known")
        case _               => false
      })
      assertEquals(tasks.tasks, Nil)
    }

  test("sendMessage accepts a contextId that already has a task"):
    val contextId = ContextId("known-context")
    val seeded = A2ATask(
      id = TaskId("seed-task"),
      contextId = contextId,
      status = TaskStatus.completed(A2AMessage.agentText("seeded", Some(contextId))),
    )
    val request = A2ARequest.MessageSend(A2AMessage.userText("follow-up").copy(contextId = Some(contextId)))
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        _         <- taskStore.save(seeded, None)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("KnownCtx"),
          (_, _) => ZIO.unit,
        )
        result <- core.requestHandler.sendMessage(request, ServerCallContext()).either
      yield result

    runTask(effect).map { result =>
      // The context check must not reject a known contextId (it gets past
      // validateContextReference into normal task handling).
      assert(result.isRight || result.left.forall {
        case error: A2AError => error.code != A2AErrorCode.InvalidParams || !error.message.contains("contextId is not known")
        case _               => true
      })
    }

  test("shared request handler fails an orphaned working task with no active run"):
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        taskId    = TaskId("orphaned-working-task")
        contextId = ContextId("orphaned-working-context")
        prompt = A2AMessage.agentText("working", Some(contextId)).copy(taskId = Some(taskId))
        task = A2ATask(
          id = taskId,
          contextId = contextId,
          status = TaskStatus.working(Some(prompt)),
          history = List(prompt),
        )
        _ <- taskStore.save(task, None)
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("Orphan"),
          (_, _) => ZIO.unit,
        )
        loaded <- core.requestHandler.getTask(A2ARequest.TasksGet(taskId), ServerCallContext())
      yield loaded

    runTask(effect).map { loaded =>
      assertEquals(loaded.status.state, TaskState.Failed)
      assert(loaded.status.message.exists(_.text.contains("Task interrupted")))
    }

  test("reconcile does not clobber a task that completes during the bus check (TOCTOU)"):
    // The run can persist a terminal status and remove its bus between getTask's
    // initial load and reconcileOrphaned's bus check. A store whose first load
    // returns `working` (what getTask saw) and whose re-load returns `completed`
    // (the run finished in the window) must NOT be failed — reconcile must
    // re-read and honor the terminal state.
    val taskId    = TaskId("toctou-task")
    val contextId = ContextId("toctou-context")
    val prompt    = A2AMessage.agentText("working", Some(contextId)).copy(taskId = Some(taskId))
    val working   = A2ATask(taskId, contextId, TaskStatus.working(Some(prompt)), history = List(prompt))
    val completed = working.copy(status =
      TaskStatus.completed(A2AMessage.agentText("done", Some(contextId)).copy(taskId = Some(taskId))),
    )

    val effect =
      for
        backing  <- ZIO.succeed(A2ATaskStore.inMemory)
        registry <- A2ARuntimeRegistry.make
        loads    <- Ref.make(0)
        raced = new A2ATaskStore:
          def save(task: A2ATask, tenant: Option[String]): UIO[Unit] = backing.save(task, tenant)
          def list(params: A2ARequest.TasksList, tenant: Option[String]): Task[A2AResponse.ListTasksResult] =
            backing.list(params, tenant)
          def delete(id: TaskId, tenant: Option[String]): UIO[Unit] = backing.delete(id, tenant)
          // First load (getTask) sees `working`; the reconcile re-load sees the
          // terminal `completed` the concurrent run just persisted.
          def load(id: TaskId, tenant: Option[String]): UIO[Option[A2ATask]] =
            loads.updateAndGet(_ + 1).map(n => Some(if n <= 1 then working else completed))
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(raced)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("Toctou"),
          (_, _) => ZIO.unit,
        )
        loaded <- core.requestHandler.getTask(A2ARequest.TasksGet(taskId), ServerCallContext())
      yield loaded

    runTask(effect).map { loaded =>
      assertEquals(loaded.status.state, TaskState.Completed)
    }

  test("shared request handler can return message-only SendMessageResponse without creating a task"):
    val request = A2ARequest.MessageSend(A2AMessage.userText("hello"))
    val responseOverride: A2ARequest.MessageSend => Task[A2AMessage] =
      params =>
        ZIO.succeed(
          A2AMessage
            .agentText(s"echo: ${params.message.text}", params.message.contextId)
            .copy(taskId = Some(TaskId("should-not-leak")))
        )

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore), messageResponseOverride = Some(responseOverride)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("MessageOnly"),
          (_, _) => ZIO.fail(new RuntimeException("message-only SendMessage should not start task execution")),
        )
        result <- core.requestHandler.sendMessage(request, ServerCallContext())
        tasks  <- taskStore.list(A2ARequest.TasksList(), None)
      yield result -> tasks

    runTask(effect).map { case (result, tasks) =>
      result match
        case A2AResponse.SendMessageResult.MessageResult(message) =>
          assertEquals(message.role, A2ARole.Agent)
          assertEquals(message.text, "echo: hello")
          assertEquals(message.contextId, None)
          assertEquals(message.taskId, None)
        case other =>
          fail(s"expected message result, got $other")
      assertEquals(tasks.tasks, Nil)
    }

  test("shared request handler can stream ACTS message-only response without creating a task"):
    val request = A2ARequest.MessageSend(A2AMessage.userText("tck-message-response streaming"))
    val responseOverride: A2ARequest.MessageSend => Task[A2AMessage] =
      params =>
        ZIO.succeed(
          A2AMessage
            .agentText(s"echo: ${params.message.text}", params.message.contextId)
            .copy(taskId = Some(TaskId("should-not-leak")))
        )

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore), messageResponseOverride = Some(responseOverride)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("StreamingMessageOnly"),
          (_, _) => ZIO.fail(new RuntimeException("message-only SendStreamingMessage should not start task execution")),
        )
        stream <- core.requestHandler.sendMessageStream(request, ServerCallContext())
        events <- stream.runCollect.timeoutFail(new RuntimeException("message-only stream did not close"))(500.millis)
        tasks  <- taskStore.list(A2ARequest.TasksList(), None)
      yield events.toList -> tasks

    runTask(effect).map { case (events, tasks) =>
      assertEquals(events.size, 1)
      events.head match
        case A2AResponse.StreamEvent.Message(message) =>
          assertEquals(message.role, A2ARole.Agent)
          assertEquals(message.text, "echo: tck-message-response streaming")
          assertEquals(message.contextId, None)
          assertEquals(message.taskId, None)
          assert(events.head.isFinal)
          assert(events.head.closesStream)
        case other =>
          fail(s"expected direct message stream event, got $other")
      assertEquals(tasks.tasks, Nil)
    }

  test("shared request handler lets message response selector decline and fall through to task execution"):
    val directRequest = A2ARequest.MessageSend(A2AMessage.userText("direct"))
    val taskRequest   = A2ARequest.MessageSend(A2AMessage.userText("task"))
    val selector: A2ARequest.MessageSend => Task[Option[A2AMessage]] =
      params =>
        if params.message.text == "direct" then ZIO.some(A2AMessage.agentText("direct response"))
        else ZIO.none
    val execute: A2ARequestHandler.ExecuteRun =
      (prepared, publisher) =>
        publisher.publish(
          A2AResponse.StreamEvent.TaskStatusUpdate(
            prepared.task.id,
            prepared.task.contextId,
            TaskStatus.completed(A2AMessage.agentText("task response", Some(prepared.task.contextId)).copy(taskId = Some(prepared.task.id))),
            `final` = true,
          )
        )

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore), messageResponseSelectorOverride = Some(selector)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("MessageSelector"),
          execute,
        )
        direct <- core.requestHandler.sendMessage(directRequest, ServerCallContext())
        task   <- core.requestHandler.sendMessage(taskRequest, ServerCallContext())
        tasks  <- taskStore.list(A2ARequest.TasksList(), None)
      yield (direct, task, tasks.tasks)

    runTask(effect).map { case (direct, task, tasks) =>
      assert(direct.isInstanceOf[A2AResponse.SendMessageResult.MessageResult])
      task match
        case A2AResponse.SendMessageResult.TaskResult(value) =>
          assertEquals(value.status.state, TaskState.Completed)
          assertEquals(value.status.message.map(_.text), Some("task response"))
        case other =>
          fail(s"expected task result after selector declined, got $other")
      assertEquals(tasks.size, 1)
    }

  test("shared request handler honors ACTS top-level SendMessage taskId alias"):
    val missingTaskId = TaskId("00000000-0000-0000-0000-000000000000")
    val requestJson =
      s"""{
         |  "taskId": "${missingTaskId.value}",
         |  "message": {
         |    "role": "ROLE_USER",
         |    "parts": [{"text": "missing task should not create a new task"}],
         |    "messageId": "msg-missing-task"
         |  }
         |}""".stripMargin

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("ActsMissingTask"),
          (_, _) => ZIO.fail(new RuntimeException("missing top-level taskId must fail before execution")),
        )
        request <- ZIO.fromEither(requestJson.fromJson[A2ARequest.MessageSend].left.map(new RuntimeException(_)))
        result  <- core.requestHandler.sendMessage(request, ServerCallContext()).either
        tasks   <- taskStore.list(A2ARequest.TasksList(), None)
      yield result -> tasks

    runTask(effect).map { case (result, tasks) =>
      assert(result.left.exists {
        case error: A2AError => error.code == A2AErrorCode.TaskNotFound && error.message.contains(missingTaskId.value)
        case _               => false
      })
      assertEquals(tasks.tasks, Nil)
    }

  test("shared request handler rejects ACTS unsupported message part content type"):
    val requestJson =
      """{
        |  "message": {
        |    "role": "ROLE_USER",
        |    "parts": [
        |      {
        |        "data": {"value": "unsupported"},
        |        "metadata": {
        |          "contentType": "application/x-unsupported-type-12345"
        |        }
        |      }
        |    ],
        |    "messageId": "msg-unsupported-content-type"
        |  }
        |}""".stripMargin

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("ActsUnsupportedContentType"),
          (_, _) => ZIO.fail(new RuntimeException("unsupported content type must fail before execution")),
        )
        request <- ZIO.fromEither(requestJson.fromJson[A2ARequest.MessageSend].left.map(new RuntimeException(_)))
        result  <- core.requestHandler.sendMessage(request, ServerCallContext()).either
        tasks   <- taskStore.list(A2ARequest.TasksList(), None)
      yield result -> tasks

    runTask(effect).map { case (result, tasks) =>
      assert(result.left.exists {
        case error: A2AError =>
          error.code == A2AErrorCode.ContentTypeNotSupported &&
            error.message.contains("application/x-unsupported-type-12345")
        case _ => false
      })
      assertEquals(tasks.tasks, Nil)
    }

  test("shared request handler covers ACTS multi-turn context inference and mismatch rejection"):
    def taskResult(result: A2AResponse.SendMessageResult): A2ATask =
      result match
        case A2AResponse.SendMessageResult.TaskResult(task) => task
        case other                                         => fail(s"expected task result, got $other")

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("ActsMultiTurn"),
          (prepared, publisher) =>
            val taskId    = prepared.task.id
            val contextId = prepared.task.contextId
            if prepared.message.text == "done" then
              val message = A2AMessage.agentText("complete", Some(contextId)).copy(taskId = Some(taskId))
              publisher.publish(
                A2AResponse.StreamEvent.TaskStatusUpdate(
                  taskId,
                  contextId,
                  TaskStatus.completed(message),
                  `final` = true,
                )
              )
            else
              val prompt = A2AMessage.agentText("need input", Some(contextId)).copy(taskId = Some(taskId))
              publisher.publish(
                A2AResponse.StreamEvent.TaskStatusUpdate(
                  taskId,
                  contextId,
                  TaskStatus.inputRequired(prompt),
                )
              )
        )
        first <- core.requestHandler.sendMessage(
          A2ARequest.MessageSend(A2AMessage.userText("tck-multi-turn start")),
          ServerCallContext(),
        )
        firstTask = taskResult(first)
        mismatch <- core.requestHandler
          .sendMessage(
            A2ARequest.MessageSend(
              A2AMessage
                .userText("wrong context")
                .copy(taskId = Some(firstTask.id), contextId = Some(ContextId("wrong-context-id-12345")))
            ),
            ServerCallContext(),
          )
          .either
        second <- core.requestHandler.sendMessage(
          A2ARequest.MessageSend(A2AMessage.userText("done").copy(taskId = Some(firstTask.id))),
          ServerCallContext(),
        )
      yield (firstTask, mismatch, taskResult(second))

    runTask(effect).map { case (firstTask, mismatch, secondTask) =>
      assertEquals(firstTask.status.state, TaskState.InputRequired)
      assert(mismatch.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidParams && error.message.contains("contextId")
        case _               => false
      })
      assertEquals(secondTask.id, firstTask.id)
      assertEquals(secondTask.contextId, firstTask.contextId)
      assertEquals(secondTask.status.state, TaskState.Completed)
    }

  test("shared request handler covers ACTS multi-turn history order and projection"):
    def taskResult(result: A2AResponse.SendMessageResult): A2ATask =
      result match
        case A2AResponse.SendMessageResult.TaskResult(task) => task
        case other                                         => fail(s"expected task result, got $other")

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("ActsHistory"),
          (prepared, publisher) =>
            val taskId    = prepared.task.id
            val contextId = prepared.task.contextId
            val state =
              if prepared.message.text == "done" then
                TaskStatus.completed(A2AMessage.agentText("complete", Some(contextId)).copy(taskId = Some(taskId)))
              else TaskStatus.inputRequired(A2AMessage.agentText("need input", Some(contextId)).copy(taskId = Some(taskId)))
            publisher.publish(
              A2AResponse.StreamEvent.TaskStatusUpdate(
                taskId,
                contextId,
                state,
                `final` = state.state.isTerminal,
              )
            )
        )
        first <- core.requestHandler.sendMessage(
          A2ARequest.MessageSend(A2AMessage.userText("tck-multi-turn chronological first")),
          ServerCallContext(),
        )
        firstTask = taskResult(first)
        _ <- core.requestHandler.sendMessage(
          A2ARequest.MessageSend(
            A2AMessage
              .userText("tck-multi-turn chronological second")
              .copy(taskId = Some(firstTask.id), contextId = Some(firstTask.contextId))
          ),
          ServerCallContext(),
        )
        third <- core.requestHandler.sendMessage(
          A2ARequest.MessageSend(
            A2AMessage.userText("done").copy(taskId = Some(firstTask.id), contextId = Some(firstTask.contextId))
          ),
          ServerCallContext(),
        )
        full <- core.requestHandler.getTask(A2ARequest.TasksGet(firstTask.id), ServerCallContext())
        tail <- core.requestHandler.getTask(A2ARequest.TasksGet(firstTask.id, historyLength = Some(2)), ServerCallContext())
        none <- core.requestHandler.getTask(A2ARequest.TasksGet(firstTask.id, historyLength = Some(0)), ServerCallContext())
      yield (taskResult(third), full, tail, none)

    runTask(effect).map { case (third, full, tail, none) =>
      assertEquals(third.status.state, TaskState.Completed)
      assertEquals(
        full.history.map(_.text),
        List("tck-multi-turn chronological first", "tck-multi-turn chronological second", "done"),
      )
      assertEquals(tail.history.map(_.text), List("tck-multi-turn chronological second", "done"))
      assertEquals(none.history, Nil)
    }

  test("shared request handler rejects unknown client contextId but accepts server context continuation"):
    def taskResult(result: A2AResponse.SendMessageResult): A2ATask =
      result match
        case A2AResponse.SendMessageResult.TaskResult(task) => task
        case other                                         => fail(s"expected task result, got $other")

    val unknownContext = ContextId("client-generated-context-that-should-be-rejected")
    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("ActsContextIdentity"),
          (prepared, publisher) =>
            val message = A2AMessage
              .agentText(s"done: ${prepared.message.text}", Some(prepared.task.contextId))
              .copy(taskId = Some(prepared.task.id))
            publisher.publish(
              A2AResponse.StreamEvent.TaskStatusUpdate(
                prepared.task.id,
                prepared.task.contextId,
                TaskStatus.completed(message),
                `final` = true,
              )
            ),
        )
        unknown <- core.requestHandler
          .sendMessage(
            A2ARequest.MessageSend(A2AMessage.userText("tck-complete-task reject client context id", Some(unknownContext))),
            ServerCallContext(),
          )
          .either
        first <- core.requestHandler.sendMessage(
          A2ARequest.MessageSend(A2AMessage.userText("tck-complete-task first context owner")),
          ServerCallContext(),
        )
        firstTask = taskResult(first)
        second <- core.requestHandler.sendMessage(
          A2ARequest.MessageSend(A2AMessage.userText("tck-complete-task same context", Some(firstTask.contextId))),
          ServerCallContext(),
        )
        secondTask = taskResult(second)
        listed <- taskStore.list(A2ARequest.TasksList(), None)
      yield (unknown, firstTask, secondTask, listed.tasks)

    runTask(effect).map { case (unknown, firstTask, secondTask, tasks) =>
      assert(unknown.left.exists {
        case error: A2AError =>
          error.code == A2AErrorCode.InvalidParams &&
            error.message.contains("contextId") &&
            error.message.contains(unknownContext.value)
        case _ => false
      })
      assertEquals(firstTask.status.state, TaskState.Completed)
      assertEquals(secondTask.status.state, TaskState.Completed)
      assert(secondTask.id != firstTask.id)
      assertEquals(secondTask.contextId, firstTask.contextId)
      assertEquals(tasks.map(_.id).toSet, Set(firstTask.id, secondTask.id))
    }

  test("shared request handler treats repeated CancelTask for canceled tasks as idempotent"):
    val taskId    = TaskId("idempotent-cancel-task")
    val contextId = ContextId("idempotent-cancel-context")
    val task      = A2ATask(taskId, contextId, TaskStatus.working())
    val completed = A2ATask(TaskId("completed-cancel-task"), contextId, TaskStatus.completed(A2AMessage.agentText("done")))

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        _         <- taskStore.save(task, None)
        _         <- taskStore.save(completed, None)
        core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => card("CancelIdempotency"),
          (_, _) => ZIO.unit,
        )
        first      <- core.requestHandler.cancelTask(A2ARequest.TasksCancel(taskId), ServerCallContext())
        second     <- core.requestHandler.cancelTask(A2ARequest.TasksCancel(taskId), ServerCallContext())
        completedE <- core.requestHandler.cancelTask(A2ARequest.TasksCancel(completed.id), ServerCallContext()).either
      yield (first, second, completedE)

    runTask(effect).map { case (first, second, completedE) =>
      assertEquals(first.status.state, TaskState.Canceled)
      assertEquals(second.status.state, TaskState.Canceled)
      assertEquals(second.status.timestamp, first.status.timestamp)
      assert(completedE.left.exists {
        case error: A2AError => error.code == A2AErrorCode.TaskNotCancelable
        case _               => false
      })
    }

  test("shared request handler treats repeated push config deletion as idempotent"):
    val capabilities = AgentCapabilities.default.copy(pushNotifications = true)
    val taskId       = TaskId("idempotent-push-delete-task")
    val contextId    = ContextId("idempotent-push-delete-context")
    val task         = A2ATask(taskId, contextId, TaskStatus.working())
    val config       = TaskPushNotificationConfig("https://example.test/hook", taskId = Some(taskId), id = Some("cfg-1"))
    val request      = A2ARequest.PushNotificationConfigDelete(taskId, "cfg-1")

    val effect =
      for
        taskStore <- ZIO.succeed(A2ATaskStore.inMemory)
        pushStore <- ZIO.succeed(A2APushNotificationStore.inMemory)
        registry  <- A2ARuntimeRegistry.make
        _         <- taskStore.save(task, None)
        core = A2AServerCore.make(
          TestConfig(
            capabilities = capabilities,
            taskStore = Some(taskStore),
            pushNotificationStore = Some(pushStore),
          ),
          runtime,
          registry,
          NoopPushPoster,
          () => card("PushDeleteIdempotency", capabilities),
          (_, _) => ZIO.unit,
        )
        saved  <- core.requestHandler.createPushConfig(config, ServerCallContext())
        before <- core.requestHandler.listPushConfigs(A2ARequest.PushNotificationConfigList(taskId), ServerCallContext())
        first  <- core.requestHandler.deletePushConfig(request, ServerCallContext()).either
        second <- core.requestHandler.deletePushConfig(request, ServerCallContext()).either
        after  <- core.requestHandler.listPushConfigs(A2ARequest.PushNotificationConfigList(taskId), ServerCallContext())
      yield (saved, before, first, second, after)

    runTask(effect).map { case (saved, before, first, second, after) =>
      assertEquals(saved.id, Some("cfg-1"))
      assertEquals(before.configs, List(saved))
      assertEquals(first, Right(()))
      assertEquals(second, Right(()))
      assertEquals(after.configs, Nil)
    }

  test("shared request handler gates capability-required operations with spec error codes"):
    val noCapabilities = AgentCapabilities.default.copy(
      streaming = false,
      pushNotifications = false,
      extendedAgentCard = false,
    )
    val extendedNoCard = AgentCapabilities.default.copy(extendedAgentCard = true)
    val context        = ServerCallContext()
    val taskId         = TaskId("capability-task")
    val pushConfig     = TaskPushNotificationConfig("https://example.test/hook", taskId = Some(taskId), id = Some("push-1"))

    def code[A](result: Either[Throwable, A]): Option[Int] =
      result.left.toOption.collect { case error: A2AError => error.code }

    val effect =
      for
        registry <- A2ARuntimeRegistry.make
        disabledCore = A2AServerCore.make(
          TestConfig(capabilities = noCapabilities),
          runtime,
          registry,
          NoopPushPoster,
          () => card("NoCapabilities", noCapabilities),
          (_, _) => ZIO.unit,
        )
        noExtendedCore = A2AServerCore.make(
          TestConfig(capabilities = extendedNoCard),
          runtime,
          registry,
          NoopPushPoster,
          () => card("ExtendedNoCard", extendedNoCard),
          (_, _) => ZIO.unit,
        )
        streamSend <- disabledCore.requestHandler
          .sendMessageStream(A2ARequest.MessageSend(A2AMessage.userText("stream")), context)
          .either
        streamSubscribe <- disabledCore.requestHandler
          .resubscribe(A2ARequest.TasksResubscribe(taskId), context)
          .either
        pushCreate <- disabledCore.requestHandler.createPushConfig(pushConfig, context).either
        pushGet <- disabledCore.requestHandler
          .getPushConfig(A2ARequest.PushNotificationConfigGet(taskId, "push-1"), context)
          .either
        pushList <- disabledCore.requestHandler
          .listPushConfigs(A2ARequest.PushNotificationConfigList(taskId), context)
          .either
        pushDelete <- disabledCore.requestHandler
          .deletePushConfig(A2ARequest.PushNotificationConfigDelete(taskId, "push-1"), context)
          .either
        extendedDisabled <- disabledCore.requestHandler.getExtendedAgentCard(context).either
        extendedMissing <- noExtendedCore.requestHandler.getExtendedAgentCard(context).either
      yield (
        streamSend,
        streamSubscribe,
        pushCreate,
        pushGet,
        pushList,
        pushDelete,
        extendedDisabled,
        extendedMissing,
      )

    runTask(effect).map {
      case (
            streamSend,
            streamSubscribe,
            pushCreate,
            pushGet,
            pushList,
            pushDelete,
            extendedDisabled,
            extendedMissing,
          ) =>
        assertEquals(code(streamSend), Some(A2AErrorCode.UnsupportedOperation))
        assertEquals(code(streamSubscribe), Some(A2AErrorCode.UnsupportedOperation))
        assertEquals(code(pushCreate), Some(A2AErrorCode.PushNotificationNotSupported))
        assertEquals(code(pushGet), Some(A2AErrorCode.PushNotificationNotSupported))
        assertEquals(code(pushList), Some(A2AErrorCode.PushNotificationNotSupported))
        assertEquals(code(pushDelete), Some(A2AErrorCode.PushNotificationNotSupported))
        assertEquals(code(extendedDisabled), Some(A2AErrorCode.UnsupportedOperation))
        assertEquals(code(extendedMissing), Some(A2AErrorCode.AuthenticatedExtendedCardNotConfigured))
    }

  test("platform live server configs use shared non-host defaults"):
    val config = A2AServerLive.Config(
      name = "Defaults",
      description = "Shared default test",
    )

    assertEquals(config.port, A2AServerDefaults.Port)
    assertEquals(config.executionMode, ExecutionMode.Default)
    assertEquals(config.eventReplayLimit, A2AServerDefaults.EventReplayLimit)
    assertEquals(config.eventStoreAppendTimeout, A2AServerDefaults.EventStoreAppendTimeout)
    assertEquals(config.eventStoreLoadTimeout, A2AServerDefaults.EventStoreLoadTimeout)
    assertEquals(config.maxRequestBodyBytes, A2AServerDefaults.MaxRequestBodyBytes)
    assert(
      config.pushNotificationUrlPolicy.asInstanceOf[AnyRef] eq
        A2AServerDefaults.PushUrlPolicy.asInstanceOf[AnyRef]
    )
    assertEquals(config.url, A2AServerDefaults.url(config.host, config.port))
    assertEquals(config.toAgentCardAt("https://agent.example.test/a2a").supportedInterfaces.map(_.url), List.fill(2)("https://agent.example.test/a2a"))

  test("shared public URL helper and live config centralize advertised and bound-port precedence"):
    val advertised = "https://agent.example.test/a2a"
    val dynamicPortConfig = A2AServerLive.Config(
      name = "DynamicPort",
      description = "Dynamic port test",
      host = "127.0.0.1",
      port = 0,
    )

    assertEquals(
      A2AServerDefaults.publicUrl("127.0.0.1", 0, advertisedUrl = None, boundPort = None),
      "http://127.0.0.1:0",
    )
    assertEquals(
      A2AServerDefaults.publicUrl("127.0.0.1", 0, advertisedUrl = None, boundPort = Some(49152)),
      "http://127.0.0.1:49152",
    )
    assertEquals(
      A2AServerDefaults.publicUrl("127.0.0.1", 0, advertisedUrl = Some(advertised), boundPort = Some(49152)),
      advertised,
    )
    assertEquals(
      A2AServerDefaults.publicUrl("agent.example.test", 443, advertisedUrl = None, scheme = "https"),
      "https://agent.example.test:443",
    )
    assertEquals(dynamicPortConfig.publicUrl(Some(49152)), "http://127.0.0.1:49152")
    assertEquals(
      dynamicPortConfig.copy(advertisedUrl = Some(advertised)).publicUrl(Some(49152)),
      advertised,
    )

  test("platform live server configs can advertise an external HTTPS URL"):
    val publicUrl = "https://agent.example.test/a2a"
    val config = A2AServerLive.Config(
      name = "PublicUrl",
      description = "Public URL test",
      advertisedUrl = Some(publicUrl),
    )

    assertEquals(config.url, publicUrl)
    assertEquals(config.toAgentCard.supportedInterfaces.map(_.url), List.fill(2)(publicUrl))

  test("platform live server configs wrap execution overrides with shared timeout"):
    val taskId    = TaskId("override-timeout")
    val contextId = ContextId("override-context")
    val prepared = A2ARequestHandler.PreparedRun(
      A2AMessage.userText("work", Some(contextId)).copy(taskId = Some(taskId)),
      A2ATask(taskId, contextId, TaskStatus.working()),
      A2AEventBus(replayLimit = 0),
    )
    val config = A2AServerLive.Config(
      name = "OverrideTimeout",
      description = "Shared override timeout test",
      taskTimeout = Some(10.millis),
      executionOverride = Some((_, _, _, _) => ZIO.never),
    )

    runTask(config.runExecutionOverride(prepared, NoopPublisher).get.either).map { result =>
      assert(result.left.exists(_.isInstanceOf[TimeoutException]))
    }

  test("server core wiring reuses configured stores and agent card provider"):
    val taskStore = A2ATaskStore.inMemory
    val pushStore = A2APushNotificationStore.inMemory
    val public    = card("Core")
    val config    = TestConfig(taskStore = Some(taskStore), pushNotificationStore = Some(pushStore))

    val effect =
      A2ARuntimeRegistry.make.map { registry =>
        A2AServerCore.make(
          config,
          runtime,
          registry,
          NoopPushPoster,
          () => public,
          (_, _) => ZIO.unit,
        )
      }

    runTask(effect).map { core =>
      assert(core.taskStore.asInstanceOf[AnyRef] eq taskStore.asInstanceOf[AnyRef])
      assert(core.pushStore.asInstanceOf[AnyRef] eq pushStore.asInstanceOf[AnyRef])
      assertEquals(core.requestHandler.agentCard, public)
    }

  test("server core wiring requires auth before returning configured extended agent card"):
    val capabilities = AgentCapabilities.default.copy(extendedAgentCard = true)
    val extended     = card("Extended", capabilities)
    val config       = TestConfig(capabilities = capabilities, extendedAgentCard = Some(extended))

    val effect =
      A2ARuntimeRegistry.make.flatMap { registry =>
        val core = A2AServerCore.make(
          config,
          runtime,
          registry,
          NoopPushPoster,
          () => card("Public", capabilities),
          (_, _) => ZIO.unit,
        )
        for
          missing <- core.requestHandler.getExtendedAgentCard(ServerCallContext()).either
          loaded <- core.requestHandler.getExtendedAgentCard(ServerCallContext(authorization = Some("Bearer test-token")))
        yield (missing, loaded)
      }

    runTask(effect).map { case (missing, result) =>
      assert(missing.left.exists {
        case error: A2AError => error.code == A2AErrorCode.Unauthenticated
        case _               => false
      })
      assertEquals(result, extended)
    }

  test("request auth is enforced before scoped task operations when Agent Card advertises security"):
    val secureCard = card("Secure").copy(
      securitySchemes = Map("bearer" -> SecurityScheme.Http("Bearer")),
      securityRequirements = List(SecurityRequirement(Map("bearer" -> Nil))),
    )
    val contextId = ContextId("ctx-secure")
    val task = A2ATask(
      id = TaskId("task-secure"),
      contextId = contextId,
      status = TaskStatus.completed(A2AMessage.agentText("done", Some(contextId))),
    )

    val effect =
      A2ARuntimeRegistry.make.flatMap { registry =>
        val taskStore = A2ATaskStore.inMemory
        val core = A2AServerCore.make(
          TestConfig(taskStore = Some(taskStore)),
          runtime,
          registry,
          NoopPushPoster,
          () => secureCard,
          (_, _) => ZIO.unit,
        )
        for
          _       <- taskStore.save(task, None)
          missing <- core.requestHandler.getTask(A2ARequest.TasksGet(task.id), ServerCallContext()).either
          loaded <- core.requestHandler.getTask(
            A2ARequest.TasksGet(task.id),
            ServerCallContext(authorization = Some("Bearer test-token")),
          )
        yield (missing, loaded)
      }

    runTask(effect).map { case (missing, loaded) =>
      assert(missing.left.exists {
        case error: A2AError => error.code == A2AErrorCode.Unauthenticated
        case _               => false
      })
      assertEquals(loaded.id, task.id)
    }
