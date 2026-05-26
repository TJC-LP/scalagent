package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/** JSON-RPC 2.0 request structure */
final case class JsonRpcRequest(
  jsonrpc: String = A2AProtocol.JsonRpcVersion,
  method: String,
  params: Option[Json] = None,
  id: Option[JsonRpcId] = None):
  /** Check if this is a notification (no id) */
  def isNotification: Boolean = id.isEmpty

object JsonRpcRequest:
  given JsonEncoder[JsonRpcRequest] = DeriveJsonEncoder.gen[JsonRpcRequest]
  given JsonDecoder[JsonRpcRequest] = DeriveJsonDecoder.gen[JsonRpcRequest]

  /** Create a request with auto-generated ID */
  def apply(method: String, params: Json): JsonRpcRequest =
    JsonRpcRequest(method = method, params = Some(params), id = Some(JsonRpcId.generate))

  /** Create a notification (no response expected) */
  def notification(method: String, params: Json): JsonRpcRequest =
    JsonRpcRequest(method = method, params = Some(params), id = None)

/** JSON-RPC 2.0 response structure */
final case class JsonRpcResponse(
  jsonrpc: String = A2AProtocol.JsonRpcVersion,
  result: Option[Json] = None,
  error: Option[JsonRpcError] = None,
  id: Option[JsonRpcId] = None):
  /** Check if this response is an error */
  def isError: Boolean = error.isDefined

  /** Check if this response is successful */
  def isSuccess: Boolean = result.isDefined && error.isEmpty

  /** Get the result or fail */
  def getResult: Either[JsonRpcError, Json] =
    error match
      case Some(err) => Left(err)
      case None      => result.toRight(JsonRpcError(A2AErrorCode.InternalError, "No result"))

object JsonRpcResponse:
  given JsonEncoder[JsonRpcResponse] = DeriveJsonEncoder.gen[JsonRpcResponse]
  given JsonDecoder[JsonRpcResponse] = DeriveJsonDecoder.gen[JsonRpcResponse]

  /** Create a success response */
  def success(id: Option[JsonRpcId], result: Json): JsonRpcResponse =
    JsonRpcResponse(result = Some(result), id = id)

  /** Create a success response from encodable value */
  def success[A: JsonEncoder](id: Option[JsonRpcId], value: A): JsonRpcResponse =
    JsonRpcResponse(result = value.toJsonAST.toOption, id = id)

  /** Create an error response */
  def error(id: Option[JsonRpcId], error: JsonRpcError): JsonRpcResponse =
    JsonRpcResponse(error = Some(error), id = id)

  /** Create an error response from code and message */
  def error(
    id: Option[JsonRpcId],
    code: Int,
    message: String,
    data: Option[Json] = None,
  ): JsonRpcResponse =
    JsonRpcResponse(error = Some(JsonRpcError(code, message, data)), id = id)

  /** Create an error response from A2AError */
  def fromA2AError(id: Option[JsonRpcId], err: A2AError): JsonRpcResponse =
    error(id, err.toJsonRpcError)
end JsonRpcResponse

/** JSON-RPC error structure */
final case class JsonRpcError(
  code: Int,
  message: String,
  data: Option[Json] = None):
  def toA2AError: A2AError = A2AError(code, message, data.map(_.toString))

object JsonRpcError:
  given JsonEncoder[JsonRpcError] = DeriveJsonEncoder.gen[JsonRpcError]
  given JsonDecoder[JsonRpcError] = DeriveJsonDecoder.gen[JsonRpcError]

/** JSON-RPC ID - can be string or number */
enum JsonRpcId:
  case Str(value: String)
  case Num(value: Long)

  def toJson: Json = this match
    case Str(s) => Json.Str(s)
    case Num(n) => Json.Num(java.math.BigDecimal.valueOf(n))

object JsonRpcId:
  private var counter = 0L

  def generate: JsonRpcId =
    counter += 1
    Num(counter)

  def apply(s: String): JsonRpcId = Str(s)
  def apply(n: Long): JsonRpcId   = Num(n)

  given JsonEncoder[JsonRpcId] = JsonEncoder[Json].contramap(_.toJson)

  given JsonDecoder[JsonRpcId] = JsonDecoder[Json].mapOrFail {
    case Json.Str(s) => Right(Str(s))
    case Json.Num(n) => Right(Num(n.longValue))
    case other       => Left(s"Invalid JSON-RPC id: $other")
  }

/** A2A protocol methods */
object A2AMethod:
  // Message methods
  val MessageSend   = "SendMessage"
  val MessageStream = "SendStreamingMessage"

  // Task methods
  val TasksGet         = "GetTask"
  val TasksList        = "ListTasks"
  val TasksCancel      = "CancelTask"
  val TasksResubscribe = "SubscribeToTask"

  // Push notification methods
  val PushNotificationConfigSet    = "CreateTaskPushNotificationConfig"
  val PushNotificationConfigGet    = "GetTaskPushNotificationConfig"
  val PushNotificationConfigList   = "ListTaskPushNotificationConfigs"
  val PushNotificationConfigDelete = "DeleteTaskPushNotificationConfig"

  // Agent methods
  val GetAuthenticatedExtendedCard = "GetExtendedAgentCard"
