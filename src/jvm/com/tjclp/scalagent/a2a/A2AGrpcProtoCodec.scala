package com.tjclp.scalagent.a2a

import java.nio.ByteBuffer

import scala.util.control.NonFatal

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
import com.google.protobuf.{Empty, Message as JavaMessage}
import com.google.protobuf.util.JsonFormat
import zio.json.*

/**
 * JVM-only bridge between the generated upstream A2A protobuf classes and
 * scalagent's shared protocol ADTs. The conversion intentionally goes through
 * ProtoJSON so the existing shared codecs remain the single source of semantic
 * validation while the JVM wire layer consumes and emits binary protobufs.
 */
private[a2a] object A2AGrpcProtoCodec:
  private val JsonParser  = JsonFormat.parser().ignoringUnknownFields()
  private val JsonPrinter = JsonFormat.printer().omittingInsignificantWhitespace()

  def decodeRequest(operation: A2AOperation, payload: Array[Byte]): Either[A2AError, A2AGrpcRequest] =
    operation match
      case A2AOperation.MessageSend =>
        parse(payload, bytes => ProtoSendMessageRequest.parseFrom(bytes), "SendMessageRequest")
          .flatMap(decode[A2ARequest.MessageSend]("SendMessageRequest", _))
          .map(A2AGrpcRequest.MessageSend(_))
      case A2AOperation.MessageStream =>
        parse(payload, bytes => ProtoSendMessageRequest.parseFrom(bytes), "SendMessageRequest")
          .flatMap(decode[A2ARequest.MessageSend]("SendMessageRequest", _))
          .map(A2AGrpcRequest.MessageStream(_))
      case A2AOperation.TasksGet =>
        parse(payload, bytes => ProtoGetTaskRequest.parseFrom(bytes), "GetTaskRequest")
          .flatMap(decode[A2ARequest.TasksGet]("GetTaskRequest", _))
          .map(A2AGrpcRequest.TasksGet(_))
      case A2AOperation.TasksList =>
        parse(payload, bytes => ProtoListTasksRequest.parseFrom(bytes), "ListTasksRequest")
          .flatMap(decode[A2ARequest.TasksList]("ListTasksRequest", _))
          .map(A2AGrpcRequest.TasksList(_))
      case A2AOperation.TasksCancel =>
        parse(payload, bytes => ProtoCancelTaskRequest.parseFrom(bytes), "CancelTaskRequest")
          .flatMap(decode[A2ARequest.TasksCancel]("CancelTaskRequest", _))
          .map(A2AGrpcRequest.TasksCancel(_))
      case A2AOperation.TasksResubscribe =>
        parse(payload, bytes => ProtoSubscribeToTaskRequest.parseFrom(bytes), "SubscribeToTaskRequest")
          .flatMap(decode[A2ARequest.TasksResubscribe]("SubscribeToTaskRequest", _))
          .map(A2AGrpcRequest.TasksResubscribe(_))
      case A2AOperation.PushNotificationConfigSet =>
        parse(payload, bytes => ProtoTaskPushNotificationConfig.parseFrom(bytes), "TaskPushNotificationConfig")
          .flatMap(decode[TaskPushNotificationConfig]("TaskPushNotificationConfig", _))
          .map(A2AGrpcRequest.PushNotificationConfigSet(_))
      case A2AOperation.PushNotificationConfigGet =>
        parse(
          payload,
          bytes => ProtoGetTaskPushNotificationConfigRequest.parseFrom(bytes),
          "GetTaskPushNotificationConfigRequest",
        )
          .flatMap(decode[A2ARequest.PushNotificationConfigGet]("GetTaskPushNotificationConfigRequest", _))
          .map(A2AGrpcRequest.PushNotificationConfigGet(_))
      case A2AOperation.PushNotificationConfigList =>
        parse(
          payload,
          bytes => ProtoListTaskPushNotificationConfigsRequest.parseFrom(bytes),
          "ListTaskPushNotificationConfigsRequest",
        )
          .flatMap(decode[A2ARequest.PushNotificationConfigList]("ListTaskPushNotificationConfigsRequest", _))
          .map(A2AGrpcRequest.PushNotificationConfigList(_))
      case A2AOperation.PushNotificationConfigDelete =>
        parse(
          payload,
          bytes => ProtoDeleteTaskPushNotificationConfigRequest.parseFrom(bytes),
          "DeleteTaskPushNotificationConfigRequest",
        )
          .flatMap(decode[A2ARequest.PushNotificationConfigDelete]("DeleteTaskPushNotificationConfigRequest", _))
          .map(A2AGrpcRequest.PushNotificationConfigDelete(_))
      case A2AOperation.GetAuthenticatedExtendedCard =>
        parse(payload, bytes => ProtoGetExtendedAgentCardRequest.parseFrom(bytes), "GetExtendedAgentCardRequest")
          .flatMap(decode[A2ARequest.GetAuthenticatedExtendedCard]("GetExtendedAgentCardRequest", _))
          .map(A2AGrpcRequest.GetAuthenticatedExtendedCard(_))

  def encodeUnary(response: A2AGrpcResponse): Either[A2AError, Array[Byte]] =
    response match
      case A2AGrpcResponse.SendMessage(result) =>
        encode(result.toJson, ProtoSendMessageResponse.newBuilder(), "SendMessageResponse")
      case A2AGrpcResponse.Task(task) =>
        encode(task.toJson, ProtoTask.newBuilder(), "Task")
      case A2AGrpcResponse.ListTasks(result) =>
        encode(result.toJson, ProtoListTasksResponse.newBuilder(), "ListTasksResponse")
      case A2AGrpcResponse.PushNotificationConfig(config) =>
        encode(config.toJson, ProtoTaskPushNotificationConfig.newBuilder(), "TaskPushNotificationConfig")
      case A2AGrpcResponse.PushNotificationConfigList(result) =>
        encode(
          result.toJson,
          ProtoListTaskPushNotificationConfigsResponse.newBuilder(),
          "ListTaskPushNotificationConfigsResponse",
        )
      case A2AGrpcResponse.Empty =>
        Right(Empty.getDefaultInstance.toByteArray)
      case A2AGrpcResponse.AgentCard(card) =>
        encode(card.toJson, ProtoAgentCard.newBuilder(), "AgentCard")

  def encodeStreamEvent(event: A2AResponse.StreamEvent): Either[A2AError, Array[Byte]] =
    encode(event.toJson, ProtoStreamResponse.newBuilder(), "StreamResponse")

  def encodeFrame(payload: Array[Byte]): Array[Byte] =
    ByteBuffer
      .allocate(5 + payload.length)
      .put(0.toByte)
      .putInt(payload.length)
      .put(payload)
      .array()

  def decodeFrame(frame: Array[Byte]): Either[A2AError, Array[Byte]] =
    if frame.length < 5 then Left(A2AError.invalidParams("Malformed gRPC frame: frame is shorter than 5 bytes"))
    else
      val buffer     = ByteBuffer.wrap(frame)
      val compressed = buffer.get()
      val length     = buffer.getInt()
      if compressed != 0 then Left(A2AError.invalidParams("Compressed gRPC frames are not supported"))
      else if length < 0 then Left(A2AError.invalidParams("Malformed gRPC frame: negative message length"))
      else if buffer.remaining() != length then
        Left(A2AError.invalidParams(s"Malformed gRPC frame: declared $length bytes but found ${buffer.remaining()}"))
      else
        val payload = Array.ofDim[Byte](length)
        buffer.get(payload)
        Right(payload)

  private def parse[A <: JavaMessage](
    payload: Array[Byte],
    parsePayload: Array[Byte] => A,
    label: String,
  ): Either[A2AError, A] =
    try Right(parsePayload(payload))
    catch
      case NonFatal(error) =>
        Left(A2AError.invalidParams(s"Malformed $label protobuf: ${error.getMessage}"))

  private def decode[A: JsonDecoder](label: String, message: JavaMessage): Either[A2AError, A] =
    try
      JsonPrinter.print(message).fromJson[A].left.map(error => A2AError.invalidParams(s"Malformed $label JSON: $error"))
    catch
      case NonFatal(error) =>
        Left(A2AError.invalidParams(s"Malformed $label JSON: ${error.getMessage}"))

  private def encode(
    json: String,
    builder: JavaMessage.Builder,
    label: String,
  ): Either[A2AError, Array[Byte]] =
    try
      JsonParser.merge(json, builder)
      Right(builder.build().toByteArray)
    catch
      case NonFatal(error) =>
        Left(A2AError.internalError(s"Failed to encode $label protobuf: ${error.getMessage}"))
end A2AGrpcProtoCodec
