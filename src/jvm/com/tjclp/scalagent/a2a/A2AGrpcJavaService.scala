package com.tjclp.scalagent.a2a

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

import com.google.rpc.ErrorInfo
import com.google.lf.a2a.v1.{
  AgentCard as ProtoAgentCard,
  CancelTaskRequest as ProtoCancelTaskRequest,
  DeleteTaskPushNotificationConfigRequest as ProtoDeleteTaskPushNotificationConfigRequest,
  GetExtendedAgentCardRequest as ProtoGetExtendedAgentCardRequest,
  GetTaskPushNotificationConfigRequest as ProtoGetTaskPushNotificationConfigRequest,
  GetTaskRequest as ProtoGetTaskRequest,
  ListTaskPushNotificationConfigsRequest as ProtoListTaskPushNotificationConfigsRequest,
  ListTaskPushNotificationConfigsResponse as ProtoListTaskPushNotificationConfigsResponse,
  ListTasksRequest as ProtoListTasksRequest,
  ListTasksResponse as ProtoListTasksResponse,
  SendMessageRequest as ProtoSendMessageRequest,
  SendMessageResponse as ProtoSendMessageResponse,
  StreamResponse as ProtoStreamResponse,
  SubscribeToTaskRequest as ProtoSubscribeToTaskRequest,
  Task as ProtoTask,
  TaskPushNotificationConfig as ProtoTaskPushNotificationConfig,
}
import com.google.protobuf.{Any as ProtoAny, Empty, Message as JavaMessage}
import io.grpc.protobuf.{ProtoUtils, StatusProto}
import io.grpc.stub.{ServerCalls, ServerCallStreamObserver, StreamObserver}
import io.grpc.{
  Context,
  Contexts,
  ForwardingServerCall,
  Metadata,
  MethodDescriptor,
  ServerCall,
  ServerCallHandler,
  ServerInterceptor,
  ServerInterceptors,
  ServerServiceDefinition,
  Status,
}
import zio.*

/** Low-level grpc-java service binding for the upstream `lf.a2a.v1.A2AService`. */
private[a2a] object A2AGrpcJavaService:
  val ServiceName = "lf.a2a.v1.A2AService"

  private val ContextKey: Context.Key[ServerCallContext] =
    Context.key("a2a-server-call-context")
  private val ResponseMetadataKey: Context.Key[ResponseMetadata] =
    Context.key("a2a-response-metadata")
  private val AuthorizationKey: Metadata.Key[String] =
    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
  private val VersionKey: Metadata.Key[String] =
    Metadata.Key.of("a2a-version", Metadata.ASCII_STRING_MARSHALLER)
  private val ExtensionsKey: Metadata.Key[String] =
    Metadata.Key.of("a2a-extensions", Metadata.ASCII_STRING_MARSHALLER)

  val SendMessageMethod: MethodDescriptor[ProtoSendMessageRequest, ProtoSendMessageResponse] =
    unaryMethod("SendMessage", ProtoSendMessageRequest.getDefaultInstance, ProtoSendMessageResponse.getDefaultInstance)
  val SendStreamingMessageMethod: MethodDescriptor[ProtoSendMessageRequest, ProtoStreamResponse] =
    serverStreamingMethod(
      "SendStreamingMessage",
      ProtoSendMessageRequest.getDefaultInstance,
      ProtoStreamResponse.getDefaultInstance,
    )
  val GetTaskMethod: MethodDescriptor[ProtoGetTaskRequest, ProtoTask] =
    unaryMethod("GetTask", ProtoGetTaskRequest.getDefaultInstance, ProtoTask.getDefaultInstance)
  val ListTasksMethod: MethodDescriptor[ProtoListTasksRequest, ProtoListTasksResponse] =
    unaryMethod("ListTasks", ProtoListTasksRequest.getDefaultInstance, ProtoListTasksResponse.getDefaultInstance)
  val CancelTaskMethod: MethodDescriptor[ProtoCancelTaskRequest, ProtoTask] =
    unaryMethod("CancelTask", ProtoCancelTaskRequest.getDefaultInstance, ProtoTask.getDefaultInstance)
  val SubscribeToTaskMethod: MethodDescriptor[ProtoSubscribeToTaskRequest, ProtoStreamResponse] =
    serverStreamingMethod(
      "SubscribeToTask",
      ProtoSubscribeToTaskRequest.getDefaultInstance,
      ProtoStreamResponse.getDefaultInstance,
    )
  val CreateTaskPushNotificationConfigMethod
    : MethodDescriptor[ProtoTaskPushNotificationConfig, ProtoTaskPushNotificationConfig] =
    unaryMethod(
      "CreateTaskPushNotificationConfig",
      ProtoTaskPushNotificationConfig.getDefaultInstance,
      ProtoTaskPushNotificationConfig.getDefaultInstance,
    )
  val GetTaskPushNotificationConfigMethod
    : MethodDescriptor[ProtoGetTaskPushNotificationConfigRequest, ProtoTaskPushNotificationConfig] =
    unaryMethod(
      "GetTaskPushNotificationConfig",
      ProtoGetTaskPushNotificationConfigRequest.getDefaultInstance,
      ProtoTaskPushNotificationConfig.getDefaultInstance,
    )
  val ListTaskPushNotificationConfigsMethod
    : MethodDescriptor[ProtoListTaskPushNotificationConfigsRequest, ProtoListTaskPushNotificationConfigsResponse] =
    unaryMethod(
      "ListTaskPushNotificationConfigs",
      ProtoListTaskPushNotificationConfigsRequest.getDefaultInstance,
      ProtoListTaskPushNotificationConfigsResponse.getDefaultInstance,
    )
  val GetExtendedAgentCardMethod: MethodDescriptor[ProtoGetExtendedAgentCardRequest, ProtoAgentCard] =
    unaryMethod(
      "GetExtendedAgentCard",
      ProtoGetExtendedAgentCardRequest.getDefaultInstance,
      ProtoAgentCard.getDefaultInstance,
    )
  val DeleteTaskPushNotificationConfigMethod: MethodDescriptor[ProtoDeleteTaskPushNotificationConfigRequest, Empty] =
    unaryMethod(
      "DeleteTaskPushNotificationConfig",
      ProtoDeleteTaskPushNotificationConfigRequest.getDefaultInstance,
      Empty.getDefaultInstance,
    )

  def serviceDefinition(
    runtime: Runtime[Any],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): ServerServiceDefinition =
    ServerInterceptors.intercept(
      ServerServiceDefinition
        .builder(ServiceName)
        .addMethod(
          SendMessageMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.MessageSend,
              bytes => ProtoSendMessageResponse.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          SendStreamingMessageMethod,
          ServerCalls.asyncServerStreamingCall(
            streamingHandler(A2AOperation.MessageStream, runtime, agentCard, capabilities, requestHandler)
          ),
        )
        .addMethod(
          GetTaskMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.TasksGet,
              bytes => ProtoTask.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          ListTasksMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.TasksList,
              bytes => ProtoListTasksResponse.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          CancelTaskMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.TasksCancel,
              bytes => ProtoTask.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          SubscribeToTaskMethod,
          ServerCalls.asyncServerStreamingCall(
            streamingHandler(A2AOperation.TasksResubscribe, runtime, agentCard, capabilities, requestHandler)
          ),
        )
        .addMethod(
          CreateTaskPushNotificationConfigMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.PushNotificationConfigSet,
              bytes => ProtoTaskPushNotificationConfig.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          GetTaskPushNotificationConfigMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.PushNotificationConfigGet,
              bytes => ProtoTaskPushNotificationConfig.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          ListTaskPushNotificationConfigsMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.PushNotificationConfigList,
              bytes => ProtoListTaskPushNotificationConfigsResponse.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          GetExtendedAgentCardMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.GetAuthenticatedExtendedCard,
              bytes => ProtoAgentCard.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .addMethod(
          DeleteTaskPushNotificationConfigMethod,
          ServerCalls.asyncUnaryCall(
            unaryHandler(
              A2AOperation.PushNotificationConfigDelete,
              bytes => Empty.parseFrom(bytes),
              runtime,
              agentCard,
              capabilities,
              requestHandler,
            )
          ),
        )
        .build(),
      MetadataInterceptor,
    )

  private def unaryHandler[Req <: JavaMessage, Resp <: JavaMessage](
    operation: A2AOperation,
    parseResponse: Array[Byte] => Resp,
    runtime: Runtime[Any],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): ServerCalls.UnaryMethod[Req, Resp] =
    (request, observer) =>
      val callContext      = currentCallContext
      val responseMetadata = currentResponseMetadata
      val effect           =
        for
          grpcRequest <- ZIO.fromEither(A2AGrpcProtoCodec.decodeRequest(operation, request.toByteArray))
          dispatch    <- A2AGrpcBinding.dispatch(grpcRequest, callContext, agentCard, capabilities, requestHandler)
          response    <- dispatch match
            case A2AGrpcDispatch.Unary(value, extensions) =>
              ZIO.succeed(responseMetadata.setActivatedExtensions(extensions)) *>
                ZIO
                  .fromEither(A2AGrpcProtoCodec.encodeUnary(value))
                  .flatMap(bytes =>
                    ZIO.attempt(parseResponse(bytes)).mapError(protoEncodeError(operation.grpcMethodName, _))
                  )
            case A2AGrpcDispatch.Error(error, _) =>
              ZIO.fail(error)
            case A2AGrpcDispatch.Stream(_, _) =>
              ZIO.fail(A2AError.internalError(s"${operation.grpcMethodName} returned a stream for unary gRPC dispatch"))
          // `onCompleted` is owned by runObserverEffect (exactly-once + cancel-safe).
          _ <- ZIO.attempt(observer.onNext(response))
        yield ()
      runObserverEffect(runtime, effect, observer)

  private def streamingHandler[Req <: JavaMessage](
    operation: A2AOperation,
    runtime: Runtime[Any],
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
  ): ServerCalls.ServerStreamingMethod[Req, ProtoStreamResponse] =
    (request, observer) =>
      val callContext      = currentCallContext
      val responseMetadata = currentResponseMetadata
      val effect           =
        for
          grpcRequest <- ZIO.fromEither(A2AGrpcProtoCodec.decodeRequest(operation, request.toByteArray))
          dispatch    <- A2AGrpcBinding.dispatch(grpcRequest, callContext, agentCard, capabilities, requestHandler)
          _           <- dispatch match
            case A2AGrpcDispatch.Stream(events, extensions) =>
              ZIO.succeed(responseMetadata.setActivatedExtensions(extensions)) *>
                events
                  .mapZIO(event =>
                    ZIO
                      .fromEither(A2AGrpcProtoCodec.encodeStreamEvent(event))
                      .flatMap(bytes =>
                        ZIO
                          .attempt(ProtoStreamResponse.parseFrom(bytes))
                          .mapError(protoEncodeError(operation.grpcMethodName, _))
                      )
                      .flatMap(response => ZIO.attempt(observer.onNext(response)))
                  )
                  .runDrain // `onCompleted` is owned by runObserverEffect (exactly-once + cancel-safe)
            case A2AGrpcDispatch.Error(error, _) =>
              ZIO.fail(error)
            case A2AGrpcDispatch.Unary(_, _) =>
              ZIO.fail(
                A2AError.internalError(s"${operation.grpcMethodName} returned unary data for streaming gRPC dispatch")
              )
        yield ()
      runObserverEffect(runtime, effect, observer)

  /**
   * Run the dispatch `effect` (which performs `onNext` only) and own the single
   * terminal signal. Terminal `onComplete`/`onError` fire AT MOST ONCE (guarded
   * by `terminated`), so a failure after a partial stream can't double-signal a
   * gRPC call. Client cancellation is wired via `setOnCancelHandler`: it
   * interrupts the running ZIO fiber and suppresses any later terminal callback,
   * so a dropped connection doesn't leak the fiber/stream or throw on a
   * cancelled observer.
   *
   * Backpressure: this does not yet gate `onNext` on `ServerCallStreamObserver.
   * isReady` — a very slow client on a long stream can buffer unboundedly in
   * grpc-java. Tracked as a known limitation (no JVM gRPC streaming consumer
   * today); revisit before relying on gRPC for high-volume streams.
   */
  private def runObserverEffect[A](
    runtime: Runtime[Any],
    effect: Task[Unit],
    observer: StreamObserver[A],
  ): Unit =
    Unsafe.unsafe { implicit unsafe =>
      val terminated           = new java.util.concurrent.atomic.AtomicBoolean(false)
      def completeOnce(): Unit =
        if terminated.compareAndSet(false, true) then observer.onCompleted()
      def errorOnce(status: Throwable): Unit =
        if terminated.compareAndSet(false, true) then observer.onError(status)

      val running = runtime.unsafe.runToFuture(effect.either)

      observer match
        case cancelable: ServerCallStreamObserver[?] =>
          cancelable.setOnCancelHandler { () =>
            // Client gone: interrupt the run and suppress any *future* terminal
            // callback. This claims the terminal slot via the same CAS as
            // complete/errorOnce, so a terminal that hasn't fired yet is
            // skipped. (If the effect's terminal already won the CAS at the
            // instant of cancel, that onComplete/onError lands on a cancelled
            // observer — grpc-java treats it as a no-op, which is fine.)
            terminated.set(true)
            running.cancel()
          }
        case _ => ()

      running.foreach {
        case Right(_)              => completeOnce()
        case Left(error: A2AError) => errorOnce(statusRuntimeException(error))
        case Left(error)           =>
          errorOnce(
            Status.INTERNAL
              .withDescription(Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName))
              .withCause(error)
              .asRuntimeException()
          )
      }(using ExecutionContext.global)
    }

  private def protoEncodeError(methodName: String, error: Throwable): A2AError =
    A2AError.internalError(s"Failed to encode $methodName gRPC response: ${error.getMessage}")

  private def statusRuntimeException(error: A2AError) =
    val rpcStatus =
      com.google.rpc.Status
        .newBuilder()
        .setCode(status(error).getCode.value())
        .setMessage(error.message)
    errorInfo(error).foreach(rpcStatus.addDetails)
    StatusProto.toStatusRuntimeException(rpcStatus.build())

  private def errorInfo(error: A2AError): Option[ProtoAny] =
    A2AError.errorInfoReason(error.code).map { reason =>
      val builder = ErrorInfo
        .newBuilder()
        .setReason(reason)
        .setDomain(A2AError.ErrorInfoDomain)
      error.data.filter(_.nonEmpty).foreach(value => builder.putMetadata("detail", value))
      ProtoAny.pack(builder.build())
    }

  private def status(error: A2AError): Status =
    A2AError.grpcStatus(error) match
      case A2AGrpcStatus.INVALID_ARGUMENT    => Status.INVALID_ARGUMENT
      case A2AGrpcStatus.NOT_FOUND           => Status.NOT_FOUND
      case A2AGrpcStatus.FAILED_PRECONDITION => Status.FAILED_PRECONDITION
      case A2AGrpcStatus.INTERNAL            => Status.INTERNAL
      case A2AGrpcStatus.UNAUTHENTICATED     => Status.UNAUTHENTICATED

  private def unaryMethod[Req <: JavaMessage, Resp <: JavaMessage](
    methodName: String,
    requestDefault: Req,
    responseDefault: Resp,
  ): MethodDescriptor[Req, Resp] =
    method(MethodDescriptor.MethodType.UNARY, methodName, requestDefault, responseDefault)

  private def serverStreamingMethod[Req <: JavaMessage, Resp <: JavaMessage](
    methodName: String,
    requestDefault: Req,
    responseDefault: Resp,
  ): MethodDescriptor[Req, Resp] =
    method(MethodDescriptor.MethodType.SERVER_STREAMING, methodName, requestDefault, responseDefault)

  private def method[Req <: JavaMessage, Resp <: JavaMessage](
    methodType: MethodDescriptor.MethodType,
    methodName: String,
    requestDefault: Req,
    responseDefault: Resp,
  ): MethodDescriptor[Req, Resp] =
    MethodDescriptor
      .newBuilder[Req, Resp]()
      .setType(methodType)
      .setFullMethodName(MethodDescriptor.generateFullMethodName(ServiceName, methodName))
      .setSampledToLocalTracing(true)
      .setRequestMarshaller(ProtoUtils.marshaller(requestDefault))
      .setResponseMarshaller(ProtoUtils.marshaller(responseDefault))
      .build()

  private object MetadataInterceptor extends ServerInterceptor:
    override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT],
    ): ServerCall.Listener[ReqT] =
      val responseMetadata = ResponseMetadata()
      val forwardingCall   =
        new ForwardingServerCall.SimpleForwardingServerCall[ReqT, RespT](call):
          override def sendHeaders(responseHeaders: Metadata): Unit =
            val extensions = responseMetadata.activatedExtensions
            if extensions.nonEmpty then responseHeaders.put(ExtensionsKey, extensions.mkString(","))
            super.sendHeaders(responseHeaders)

      Contexts.interceptCall(
        Context
          .current()
          .withValue(ContextKey, metadataContext(headers))
          .withValue(ResponseMetadataKey, responseMetadata),
        forwardingCall,
        headers,
        next,
      )
    end interceptCall
  end MetadataInterceptor

  private def currentCallContext: ServerCallContext =
    Option(ContextKey.get()).getOrElse(ServerCallContext())

  private def currentResponseMetadata: ResponseMetadata =
    Option(ResponseMetadataKey.get()).getOrElse(ResponseMetadata())

  private def metadataContext(headers: Metadata): ServerCallContext =
    ServerCallContext(
      requestedVersion = metadataValue(headers, VersionKey),
      requestedExtensions = metadataValues(headers, ExtensionsKey)
        .flatMap(_.split(",").toList)
        .map(_.trim)
        .filter(_.nonEmpty),
      authorization = metadataValue(headers, AuthorizationKey).filter(_.trim.nonEmpty),
    )

  private def metadataValue(headers: Metadata, key: Metadata.Key[String]): Option[String] =
    metadataValues(headers, key).lastOption

  private def metadataValues(headers: Metadata, key: Metadata.Key[String]): List[String] =
    Option(headers.getAll(key)).map(_.asScala.toList).getOrElse(Nil)

  private final class ResponseMetadata:
    @volatile private var extensions: List[String] = Nil

    def activatedExtensions: List[String] =
      extensions

    def setActivatedExtensions(values: List[String]): Unit =
      extensions = values.distinct
end A2AGrpcJavaService
