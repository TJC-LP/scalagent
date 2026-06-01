package com.tjclp.scalagent.a2a

import scala.util.Try

import zio.json.*
import zio.json.ast.Json

private object JsonRpcJson:
  def objectFields(json: Json, label: String): Either[String, Map[String, Json]] =
    json.asObject.map(_.toMap).toRight(s"$label must be an object")

  def requiredString(fields: Map[String, Json], name: String): Either[String, String] =
    fields
      .get(name)
      .toRight(s"Missing $name")
      .flatMap(_.asString.toRight(s"$name must be a string"))
      .flatMap {
        case value if value.nonEmpty => Right(value)
        case _                       => Left(s"Missing $name")
      }

  def jsonRpcVersion(fields: Map[String, Json]): Either[String, String] =
    requiredString(fields, "jsonrpc").flatMap {
      case A2AProtocol.JsonRpcVersion => Right(A2AProtocol.JsonRpcVersion)
      case _                          => Left("""jsonrpc must be "2.0"""")
    }

  def optionalId(fields: Map[String, Json]): Either[String, Option[JsonRpcId]] =
    fields.get("id") match
      case Some(value) => value.as[JsonRpcId].map(Some(_))
      case None        => Right(None)

  def requiredInt(fields: Map[String, Json], name: String): Either[String, Int] =
    fields
      .get(name)
      .toRight(s"Missing $name")
      .flatMap(_.asNumber.toRight(s"$name must be an int32"))
      .flatMap(number => Try(number.value.intValueExact).toEither.left.map(_ => s"$name must be an int32"))
end JsonRpcJson

/** JSON-RPC 2.0 request structure */
final case class JsonRpcRequest(
  jsonrpc: String = A2AProtocol.JsonRpcVersion,
  method: String,
  params: Option[Json] = None,
  id: Option[JsonRpcId] = None):
  /** Check if this is a notification (no id) */
  def isNotification: Boolean = id.isEmpty

object JsonRpcRequest:
  def parse(body: String): Either[A2AError, JsonRpcRequest] =
    body
      .fromJson[Json]
      .left
      .map(A2AError.parseError)
      .flatMap(_.as[JsonRpcRequest].left.map(A2AError.invalidRequest))
      .flatMap { request =>
        if request.id.isDefined then Right(request)
        else Left(A2AError.invalidRequest("Missing id"))
      }

  given JsonEncoder[JsonRpcRequest] = JsonEncoder[Json].contramap { request =>
    var obj = Json.Obj(
      "jsonrpc" -> Json.Str(A2AProtocol.JsonRpcVersion),
      "method"  -> Json.Str(request.method),
    )
    request.params.foreach(value => obj = obj.add("params", value))
    request.id.foreach(value => obj = obj.add("id", value.toJson))
    obj
  }

  given JsonDecoder[JsonRpcRequest] = JsonDecoder[Json].mapOrFail { json =>
    JsonRpcJson.objectFields(json, "JsonRpcRequest").flatMap { fields =>
      for
        version <- JsonRpcJson.jsonRpcVersion(fields)
        method  <- JsonRpcJson.requiredString(fields, "method")
        id      <- JsonRpcJson.optionalId(fields)
      yield JsonRpcRequest(jsonrpc = version, method = method, params = fields.get("params"), id = id)
    }
  }

  /** Create a request with auto-generated ID */
  def apply(method: String, params: Json): JsonRpcRequest =
    JsonRpcRequest(method = method, params = Some(params), id = Some(JsonRpcId.generate))

  /** Create a notification (no response expected) */
  def notification(method: String, params: Json): JsonRpcRequest =
    JsonRpcRequest(method = method, params = Some(params), id = None)
end JsonRpcRequest

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
  given JsonEncoder[JsonRpcResponse] = JsonEncoder[Json].contramap { response =>
    var obj = Json.Obj("jsonrpc" -> Json.Str(A2AProtocol.JsonRpcVersion))
    response.error match
      case Some(error) =>
        obj = obj.add("error", error.toJsonAST.toOption.get)
      case None =>
        response.result.foreach(value => obj = obj.add("result", value))
    response.id.foreach(value => obj = obj.add("id", value.toJson))
    obj
  }

  given JsonDecoder[JsonRpcResponse] = JsonDecoder[Json].mapOrFail { json =>
    JsonRpcJson.objectFields(json, "JsonRpcResponse").flatMap { fields =>
      val result = fields.get("result")
      val error  = fields.get("error")

      for
        version <- JsonRpcJson.jsonRpcVersion(fields)
        _       <-
          if result.isDefined == error.isDefined then
            Left("JSON-RPC response must include exactly one of result or error")
          else Right(())
        decodedError <- error match
          case Some(value) => value.as[JsonRpcError].map(Some(_))
          case None        => Right(None)
        id <- JsonRpcJson.optionalId(fields)
      yield JsonRpcResponse(jsonrpc = version, result = result, error = decodedError, id = id)
    }
  }

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
  given JsonEncoder[JsonRpcError] = JsonEncoder[Json].contramap { error =>
    var obj = Json.Obj(
      "code"    -> Json.Num(java.math.BigDecimal.valueOf(error.code.toLong)),
      "message" -> Json.Str(error.message),
    )
    error.data.foreach(value => obj = obj.add("data", value))
    obj
  }

  given JsonDecoder[JsonRpcError] = JsonDecoder[Json].mapOrFail { json =>
    JsonRpcJson.objectFields(json, "JsonRpcError").flatMap { fields =>
      for
        code    <- JsonRpcJson.requiredInt(fields, "code")
        message <- JsonRpcJson.requiredString(fields, "message")
      yield JsonRpcError(code, message, fields.get("data"))
    }
  }

/** JSON-RPC ID - can be string, number, or null. */
enum JsonRpcId:
  case Str(value: String)
  case Num(value: Long)
  case RawNum(value: java.math.BigDecimal)
  case Null

  def toJson: Json = this match
    case Str(s)    => Json.Str(s)
    case Num(n)    => Json.Num(java.math.BigDecimal.valueOf(n))
    case RawNum(n) => Json.Num(n)
    case Null      => Json.Null

object JsonRpcId:
  // Atomic so concurrent fibers (JVM) can't produce duplicate/skipped ids.
  private val counter            = new java.util.concurrent.atomic.AtomicLong(0L)
  val Unknown: Option[JsonRpcId] = Some(Null)

  def generate: JsonRpcId =
    Num(counter.incrementAndGet())

  def apply(s: String): JsonRpcId               = Str(s)
  def apply(n: Long): JsonRpcId                 = Num(n)
  def apply(n: java.math.BigDecimal): JsonRpcId =
    // Whole numbers normalize to Num (so 1 == 1.0); the RawNum fallback strips
    // trailing zeros so its case-class equality is value-based, not scale-based
    // (a proxy re-serializing the id with a different scale won't trip the
    // response-id mismatch check).
    Try(n.longValueExact).toOption.fold(RawNum(n.stripTrailingZeros))(Num(_))

  given JsonEncoder[JsonRpcId] = JsonEncoder[Json].contramap(_.toJson)

  given JsonDecoder[JsonRpcId] = JsonDecoder[Json].mapOrFail {
    case Json.Str(s) => Right(Str(s))
    case Json.Num(n) => Right(JsonRpcId(n))
    case Json.Null   => Right(Null)
    case other       => Left(s"Invalid JSON-RPC id: $other")
  }
end JsonRpcId

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
