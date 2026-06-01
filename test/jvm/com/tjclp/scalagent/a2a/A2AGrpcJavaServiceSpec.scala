package com.tjclp.scalagent.a2a

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.google.lf.a2a.v1.{GetTaskRequest as ProtoGetTaskRequest, SendMessageRequest as ProtoSendMessageRequest}
import com.google.rpc.ErrorInfo
import com.google.protobuf.util.JsonFormat
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.protobuf.StatusProto
import io.grpc.stub.{ClientCalls, MetadataUtils}
import io.grpc.{CallOptions, Channel, ClientInterceptors, ManagedChannel, Metadata, Status, StatusRuntimeException}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import zio.*
import zio.json.*

class A2AGrpcJavaServiceSpec extends FunSuite:
  private val runtime = Runtime.default
  private val ExtensionsMetadataKey =
    Metadata.Key.of("a2a-extensions", Metadata.ASCII_STRING_MARSHALLER)

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

  test("grpc-java service maps shared handler errors to gRPC status codes"):
    withGrpcChannel() { channel =>
      val request = ProtoGetTaskRequest.newBuilder().setId("missing").build()
      val error = intercept[StatusRuntimeException] {
        ClientCalls.blockingUnaryCall(channel, A2AGrpcJavaService.GetTaskMethod, CallOptions.DEFAULT, request)
      }

      assertEquals(error.getStatus.getCode, Status.Code.NOT_FOUND)
      assert(error.getStatus.getDescription.contains("Task not found: missing"))
      assertErrorInfo(error, "TASK_NOT_FOUND")
    }

  test("grpc-java service extracts A2A-Version from gRPC metadata"):
    withGrpcChannel() { channel =>
      val metadataChannel = withMetadata(channel, "a2a-version" -> "9.9")
      val request         = ProtoGetTaskRequest.newBuilder().setId("missing").build()
      val error = intercept[StatusRuntimeException] {
        ClientCalls.blockingUnaryCall(metadataChannel, A2AGrpcJavaService.GetTaskMethod, CallOptions.DEFAULT, request)
      }

      assertEquals(error.getStatus.getCode, Status.Code.FAILED_PRECONDITION)
      assert(error.getStatus.getDescription.contains("Version not supported"))
      assertErrorInfo(error, "VERSION_NOT_SUPPORTED")
    }

  test("grpc-java service extracts requested extensions from gRPC metadata"):
    val extension = "https://example.test/extensions/grpc-required/v1"
    val responder: A2ARequest.MessageSend => Task[A2AMessage] =
      _ => ZIO.succeed(A2AMessage.agentText("pong"))

    withGrpcChannel(
      capabilities = AgentCapabilities(extensions = List(AgentExtension(uri = extension, required = true))),
      messageResponseOverride = Some(responder),
    ) { channel =>
      val responseHeaders = new AtomicReference[Metadata]()
      val metadataChannel = withMetadata(channel, "a2a-extensions" -> s"$extension,https://example.test/ignored")
      val captureChannel  = withCapturedMetadata(metadataChannel, responseHeaders)
      val iterator = ClientCalls.blockingServerStreamingCall(
        captureChannel,
        A2AGrpcJavaService.SendStreamingMessageMethod,
        CallOptions.DEFAULT,
        sendMessageRequest(A2AMessage.userText("ping")),
      )

      assert(iterator.hasNext)
      val first = iterator.next()
      assert(first.hasMessage)
      assertEquals(first.getMessage.getParts(0).getText, "pong")
      assert(!iterator.hasNext)
      val activatedExtensions = Option(responseHeaders.get()).flatMap(headers => Option(headers.get(ExtensionsMetadataKey)))
      assertEquals(activatedExtensions, Some(extension))
    }

  test("grpc-java service streams shared direct message responses over upstream protobufs"):
    val responder: A2ARequest.MessageSend => Task[A2AMessage] =
      _ => ZIO.succeed(A2AMessage.agentText("pong"))

    withGrpcChannel(messageResponseOverride = Some(responder)) { channel =>
      val iterator = ClientCalls.blockingServerStreamingCall(
        channel,
        A2AGrpcJavaService.SendStreamingMessageMethod,
        CallOptions.DEFAULT,
        sendMessageRequest(A2AMessage.userText("ping")),
      )

      assert(iterator.hasNext)
      val first = iterator.next()
      assert(first.hasMessage)
      assertEquals(first.getMessage.getParts(0).getText, "pong")
      assert(!iterator.hasNext)
    }

  private def withGrpcChannel[A](
    capabilities: AgentCapabilities = AgentCapabilities.default,
    messageResponseOverride: Option[A2ARequest.MessageSend => Task[A2AMessage]] = None,
  )(test: ManagedChannel => A): A =
    val card = AgentCard(
      name = "GrpcJava",
      description = "grpc-java binding test card",
      supportedInterfaces = List(AgentInterface.grpc("inprocess://a2a")),
      capabilities = capabilities,
    )
    val handler = unsafeRun {
      A2ARuntimeRegistry.make.map { registry =>
        A2AServerCore
          .make(
            TestConfig(capabilities = capabilities, messageResponseOverride = messageResponseOverride),
            runtime,
            registry,
            NoopPushPoster,
            () => card,
            (_, _) => ZIO.unit,
          )
          .requestHandler
      }
    }
    val name = s"a2a-${UUID.randomUUID()}"
    val server = InProcessServerBuilder
      .forName(name)
      .directExecutor()
      .addService(A2AGrpcJavaService.serviceDefinition(runtime, card, capabilities, handler))
      .build()
      .start()
    val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    try test(channel)
    finally
      channel.shutdownNow()
      server.shutdownNow()

  private def sendMessageRequest(message: A2AMessage): ProtoSendMessageRequest =
    val builder = ProtoSendMessageRequest.newBuilder()
    JsonFormat.parser().merge(A2ARequest.MessageSend(message).toJson, builder)
    builder.build()

  private def withMetadata(channel: Channel, values: (String, String)*): Channel =
    val headers = Metadata()
    values.foreach { case (name, value) =>
      headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
    }
    ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))

  private def withCapturedMetadata(channel: Channel, headers: AtomicReference[Metadata]): Channel =
    ClientInterceptors.intercept(
      channel,
      MetadataUtils.newCaptureMetadataInterceptor(headers, new AtomicReference[Metadata]()),
    )

  private def assertErrorInfo(error: StatusRuntimeException, reason: String): Unit =
    val richStatus = StatusProto.fromThrowable(error)
    assert(richStatus != null, "expected google.rpc.Status details")
    val infos = richStatus.getDetailsList.asScala.collect {
      case detail if detail.is(classOf[ErrorInfo]) => detail.unpack(classOf[ErrorInfo])
    }
    assert(infos.exists(info => info.getReason == reason && info.getDomain == A2AError.ErrorInfoDomain))

  private def unsafeRun[A](task: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(task).getOrThrowFiberFailure()
    }
end A2AGrpcJavaServiceSpec
