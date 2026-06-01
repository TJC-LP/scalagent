package com.tjclp.scalagent.a2a

import com.google.lf.a2a.v1.{
  GetTaskRequest as ProtoGetTaskRequest,
  SendMessageResponse as ProtoSendMessageResponse,
  StreamResponse as ProtoStreamResponse,
  Task as ProtoTask,
  TaskState as ProtoTaskState,
}
import munit.FunSuite

class A2AGrpcProtoCodecSpec extends FunSuite:
  test("decodes upstream binary GetTaskRequest into the shared gRPC request model"):
    val proto = ProtoGetTaskRequest
      .newBuilder()
      .setTenant("tenant-a")
      .setId("task-1")
      .setHistoryLength(2)
      .build()

    val decoded = A2AGrpcProtoCodec.decodeRequest(A2AOperation.TasksGet, proto.toByteArray)

    decoded match
      case Right(A2AGrpcRequest.TasksGet(request)) =>
        assertEquals(request.id, TaskId("task-1"))
        assertEquals(request.tenant, Some("tenant-a"))
        assertEquals(request.historyLength, Some(2))
      case other =>
        fail(s"expected decoded TasksGet request, got $other")

  test("encodes shared task responses as upstream binary protobuf messages"):
    val task = A2ATask(
      id = TaskId("task-1"),
      contextId = ContextId("ctx-1"),
      status = TaskStatus(TaskState.Completed),
    )

    val encoded = A2AGrpcProtoCodec.encodeUnary(A2AGrpcResponse.Task(task))

    encoded match
      case Right(bytes) =>
        val proto = ProtoTask.parseFrom(bytes)
        assertEquals(proto.getId, "task-1")
        assertEquals(proto.getContextId, "ctx-1")
        assertEquals(proto.getStatus.getState, ProtoTaskState.TASK_STATE_COMPLETED)
      case Left(error) =>
        fail(s"failed to encode task response: $error")

  test("encodes shared send-message responses as upstream protobuf oneofs"):
    val encoded = A2AGrpcProtoCodec.encodeUnary(
      A2AGrpcResponse.SendMessage(A2AResponse.SendMessageResult.MessageResult(A2AMessage.agentText("pong")))
    )

    encoded match
      case Right(bytes) =>
        val proto = ProtoSendMessageResponse.parseFrom(bytes)
        assert(proto.hasMessage)
        assertEquals(proto.getMessage.getParts(0).getText, "pong")
      case Left(error) =>
        fail(s"failed to encode send-message response: $error")

  test("encodes stream events as upstream StreamResponse protobuf oneofs"):
    val encoded = A2AGrpcProtoCodec.encodeStreamEvent(
      A2AResponse.StreamEvent.TaskStatusUpdate(
        TaskId("task-1"),
        ContextId("ctx-1"),
        TaskStatus(TaskState.Completed),
        `final` = true,
      )
    )

    encoded match
      case Right(bytes) =>
        val proto = ProtoStreamResponse.parseFrom(bytes)
        assert(proto.hasStatusUpdate)
        assertEquals(proto.getStatusUpdate.getTaskId, "task-1")
        assertEquals(proto.getStatusUpdate.getContextId, "ctx-1")
        assertEquals(proto.getStatusUpdate.getStatus.getState, ProtoTaskState.TASK_STATE_COMPLETED)
      case Left(error) =>
        fail(s"failed to encode stream response: $error")

  test("encodes and decodes uncompressed gRPC message frames"):
    val payload = Array[Byte](1, 2, 3, 4)
    val frame   = A2AGrpcProtoCodec.encodeFrame(payload)

    assertEquals(A2AGrpcProtoCodec.decodeFrame(frame).map(_.toList), Right(payload.toList))
    assert(A2AGrpcProtoCodec.decodeFrame(frame.updated(0, 1.toByte)).left.exists(_.message.contains("Compressed")))
end A2AGrpcProtoCodecSpec
