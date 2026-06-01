package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import munit.FunSuite
import zio.*

class A2AGrpcBindingSpec extends FunSuite:
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
    override val agentCardAuth: A2AAgentCardAuth = A2AAgentCardAuth.permitAll,
    extendedAgentCardAuth: A2AExtendedAgentCardAuth = A2AExtendedAgentCardAuth.requireAuthorizationHeader,
    requestAuth: A2ARequestAuth = A2ARequestAuth.requireAuthorizationWhenAdvertised,
    override val messageResponseOverride: Option[A2ARequest.MessageSend => Task[A2AMessage]] = None)
      extends A2AServerCoreConfig

  private object NoopPushPoster extends A2APushNotificationPoster:
    def post(
      event: A2AResponse.StreamEvent,
      config: TaskPushNotificationConfig,
      headers: List[(String, String)],
    ): Task[Unit] =
      ZIO.unit

  private def runTask[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
    }

  private def testCard(capabilities: AgentCapabilities = AgentCapabilities.default): AgentCard =
    AgentCard(
      name = "GrpcBinding",
      description = "Shared gRPC binding test card",
      supportedInterfaces = List(AgentInterface.grpc("https://agent.example.test/a2a.A2AService")),
      capabilities = capabilities,
    )

  private def requestHandler(config: TestConfig, card: AgentCard): UIO[A2ARequestHandler] =
    A2ARuntimeRegistry.make.map { registry =>
      A2AServerCore
        .make(config, runtime, registry, NoopPushPoster, () => card, (_, _) => ZIO.unit)
        .requestHandler
    }

  test("shared gRPC dispatch maps handler errors to canonical gRPC statuses"):
    val capabilities = AgentCapabilities.default
    val card         = testCard(capabilities)
    val effect =
      requestHandler(TestConfig(capabilities = capabilities), card).flatMap { handler =>
        A2AGrpcBinding.dispatch(
          A2AGrpcRequest.TasksGet(A2ARequest.TasksGet(TaskId("missing"))),
          ServerCallContext(),
          card,
          capabilities,
          handler,
        )
      }

    runTask(effect).map {
      case A2AGrpcDispatch.Error(error, extensions) =>
        assertEquals(error.code, A2AErrorCode.TaskNotFound)
        assertEquals(A2AError.grpcStatus(error), A2AGrpcStatus.NOT_FOUND)
        assertEquals(extensions, Nil)
      case other =>
        fail(s"expected gRPC error dispatch, got $other")
    }

  test("shared gRPC dispatch uses the same streaming capability checks"):
    val capabilities = AgentCapabilities(streaming = false)
    val card         = testCard(capabilities)
    val request = A2AGrpcRequest.MessageStream(
      A2ARequest.MessageSend(A2AMessage.userText("stream please"))
    )
    val effect =
      requestHandler(TestConfig(capabilities = capabilities), card).flatMap { handler =>
        A2AGrpcBinding.dispatch(request, ServerCallContext(), card, capabilities, handler)
      }

    runTask(effect).map {
      case A2AGrpcDispatch.Error(error, _) =>
        assertEquals(error.code, A2AErrorCode.UnsupportedOperation)
        assertEquals(A2AError.grpcStatus(error), A2AGrpcStatus.FAILED_PRECONDITION)
      case other =>
        fail(s"expected gRPC unsupported-operation dispatch, got $other")
    }

  test("shared gRPC dispatch streams direct message responses"):
    val capabilities = AgentCapabilities.default
    val card         = testCard(capabilities)
    val responder: A2ARequest.MessageSend => Task[A2AMessage] =
      _ => ZIO.succeed(A2AMessage.agentText("pong"))
    val request = A2AGrpcRequest.MessageStream(
      A2ARequest.MessageSend(A2AMessage.userText("ping"))
    )
    val effect =
      requestHandler(TestConfig(capabilities = capabilities, messageResponseOverride = Some(responder)), card)
        .flatMap { handler =>
          A2AGrpcBinding.dispatch(request, ServerCallContext(), card, capabilities, handler)
        }
        .flatMap {
          case A2AGrpcDispatch.Stream(events, extensions) =>
            events.runCollect.map(_.toList -> extensions)
          case other =>
            ZIO.fail(new RuntimeException(s"expected gRPC stream dispatch, got $other"))
        }

    runTask(effect).map { case (events, extensions) =>
      assertEquals(extensions, Nil)
      assertEquals(events.size, 1)
      events.head match
        case A2AResponse.StreamEvent.Message(message) =>
          assertEquals(message.text, "pong")
        case other =>
          fail(s"expected streamed message event, got $other")
    }
end A2AGrpcBindingSpec
