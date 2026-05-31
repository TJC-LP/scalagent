package com.tjclp.scalagent.a2a

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.util.concurrent.TimeoutException

import munit.FunSuite

import zio.*

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
    pushNotificationUrlPolicy: PushNotificationUrlPolicy = A2AServerDefaults.PushUrlPolicy)
      extends A2AServerCoreConfig

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

  test("platform live server configs use shared non-host defaults"):
    val config = A2AServerLive.Config(
      name = "Defaults",
      description = "Shared default test",
    )

    assertEquals(config.port, A2AServerDefaults.Port)
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

  test("server core wiring supplies configured extended agent card"):
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
        core.requestHandler.getExtendedAgentCard(ServerCallContext())
      }

    runTask(effect).map { result =>
      assertEquals(result, extended)
    }
