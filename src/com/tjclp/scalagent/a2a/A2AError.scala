package com.tjclp.scalagent.a2a

import zio.json.*

/** A2A error codes following JSON-RPC 2.0 and A2A specification.
  *
  * Standard JSON-RPC errors: -32700 to -32603 A2A-specific errors: -32001 to -32007
  */
object A2AErrorCode:
  // Standard JSON-RPC 2.0 errors
  val ParseError: Int = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int = -32602
  val InternalError: Int = -32603

  // A2A-specific errors
  val TaskNotFound: Int = -32001
  val TaskNotCancelable: Int = -32002
  val PushNotificationNotSupported: Int = -32003
  val UnsupportedOperation: Int = -32004
  val ContentTypeNotSupported: Int = -32005
  val InvalidAgentResponse: Int = -32006
  val AuthenticatedExtendedCardNotConfigured: Int = -32007

  def message(code: Int): String = code match
    case ParseError                            => "Parse error"
    case InvalidRequest                        => "Invalid request"
    case MethodNotFound                        => "Method not found"
    case InvalidParams                         => "Invalid params"
    case InternalError                         => "Internal error"
    case TaskNotFound                          => "Task not found"
    case TaskNotCancelable                     => "Task not cancelable"
    case PushNotificationNotSupported          => "Push notification not supported"
    case UnsupportedOperation                  => "Unsupported operation"
    case ContentTypeNotSupported               => "Content type not supported"
    case InvalidAgentResponse                  => "Invalid agent response"
    case AuthenticatedExtendedCardNotConfigured => "Authenticated extended card not configured"
    case _                                     => "Unknown error"

/** A2A error exception */
final case class A2AError(
    code: Int,
    message: String,
    data: Option[String] = None
) extends Exception(s"A2A Error $code: $message"):

  def toJsonRpcError: JsonRpcError = JsonRpcError(code, message, data.map(d => zio.json.ast.Json.Str(d)))

object A2AError:
  given JsonEncoder[A2AError] = DeriveJsonEncoder.gen[A2AError]
  given JsonDecoder[A2AError] = DeriveJsonDecoder.gen[A2AError]

  // Factory methods for common errors
  def parseError(detail: String): A2AError =
    A2AError(A2AErrorCode.ParseError, s"Parse error: $detail")

  def invalidRequest(detail: String): A2AError =
    A2AError(A2AErrorCode.InvalidRequest, s"Invalid request: $detail")

  def methodNotFound(method: String): A2AError =
    A2AError(A2AErrorCode.MethodNotFound, s"Method not found: $method")

  def invalidParams(detail: String): A2AError =
    A2AError(A2AErrorCode.InvalidParams, s"Invalid params: $detail")

  def internalError(detail: String): A2AError =
    A2AError(A2AErrorCode.InternalError, s"Internal error: $detail")

  def taskNotFound(taskId: TaskId): A2AError =
    A2AError(A2AErrorCode.TaskNotFound, s"Task not found: ${taskId.value}")

  def taskNotCancelable(taskId: TaskId): A2AError =
    A2AError(A2AErrorCode.TaskNotCancelable, s"Task not cancelable: ${taskId.value}")

  def pushNotificationNotSupported: A2AError =
    A2AError(A2AErrorCode.PushNotificationNotSupported, "Push notifications not supported")

  def unsupportedOperation(operation: String): A2AError =
    A2AError(A2AErrorCode.UnsupportedOperation, s"Unsupported operation: $operation")

  def contentTypeNotSupported(contentType: String): A2AError =
    A2AError(A2AErrorCode.ContentTypeNotSupported, s"Content type not supported: $contentType")

  def invalidAgentResponse(detail: String): A2AError =
    A2AError(A2AErrorCode.InvalidAgentResponse, s"Invalid agent response: $detail")

  def authenticatedExtendedCardNotConfigured: A2AError =
    A2AError(A2AErrorCode.AuthenticatedExtendedCardNotConfigured, "Authenticated extended card not configured")
