package com.tjclp.scalagent.a2a

import scala.util.Try

import zio.json.*
import zio.json.ast.Json

/**
 * A2A error codes following JSON-RPC 2.0 and A2A specification.
 *
 * Standard JSON-RPC errors: -32700 to -32603 A2A-specific errors: -32001 to -32009
 */
object A2AErrorCode:
  // Standard JSON-RPC 2.0 errors
  val ParseError: Int     = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int  = -32602
  val InternalError: Int  = -32603

  // A2A-specific errors
  val TaskNotFound: Int                           = -32001
  val TaskNotCancelable: Int                      = -32002
  val PushNotificationNotSupported: Int           = -32003
  val UnsupportedOperation: Int                   = -32004
  val ContentTypeNotSupported: Int                = -32005
  val InvalidAgentResponse: Int                   = -32006
  val AuthenticatedExtendedCardNotConfigured: Int = -32007
  val ExtensionSupportRequired: Int               = -32008
  val VersionNotSupported: Int                    = -32009
  val Unauthenticated: Int                        = -32010

  def message(code: Int): String = code match
    case ParseError                             => "Parse error"
    case InvalidRequest                         => "Invalid request"
    case MethodNotFound                         => "Method not found"
    case InvalidParams                          => "Invalid params"
    case InternalError                          => "Internal error"
    case TaskNotFound                           => "Task not found"
    case TaskNotCancelable                      => "Task not cancelable"
    case PushNotificationNotSupported           => "Push notification not supported"
    case UnsupportedOperation                   => "Unsupported operation"
    case ContentTypeNotSupported                => "Content type not supported"
    case InvalidAgentResponse                   => "Invalid agent response"
    case AuthenticatedExtendedCardNotConfigured => "Authenticated extended card not configured"
    case VersionNotSupported                    => "Version not supported"
    case ExtensionSupportRequired               => "Extension support required"
    case Unauthenticated                        => "Unauthenticated"
    case _                                      => "Unknown error"
end A2AErrorCode

enum A2AGrpcStatus:
  case INVALID_ARGUMENT
  case NOT_FOUND
  case FAILED_PRECONDITION
  case INTERNAL
  case UNAUTHENTICATED

  def wireName: String = productPrefix

object A2AGrpcStatus:
  def fromWireName(value: String): Option[A2AGrpcStatus] =
    A2AGrpcStatus.values.find(_.wireName == value)

/** A2A error exception */
final case class A2AError(
  code: Int,
  message: String,
  data: Option[String] = None)
    extends Exception(s"A2A Error $code: $message"):

  def toJsonRpcError: JsonRpcError =
    val jsonData =
      A2AError
        .errorInfoReason(code)
        .map { reason =>
          var errorInfo = Json.Obj(
            "@type"  -> Json.Str(A2AError.ErrorInfoType),
            "reason" -> Json.Str(reason),
            "domain" -> Json.Str(A2AError.ErrorInfoDomain),
          )
          data.foreach(detail => errorInfo = errorInfo.add("metadata", Json.Obj("detail" -> Json.Str(detail))))
          Json.Arr(errorInfo)
        }
        .orElse(data.map(Json.Str(_)))
    JsonRpcError(code, message, jsonData)
end A2AError

object A2AError:
  given JsonEncoder[A2AError] = JsonEncoder[Json].contramap { error =>
    var obj = Json.Obj(
      "code"    -> Json.Num(java.math.BigDecimal.valueOf(error.code.toLong)),
      "message" -> Json.Str(error.message),
    )
    error.data.filter(_.nonEmpty).foreach(value => obj = obj.add("data", Json.Str(value)))
    obj
  }

  given JsonDecoder[A2AError] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("A2AError must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        code <- fields
          .get("code")
          .toRight("Missing code")
          .flatMap(_.asNumber.toRight("code must be an int32"))
          .flatMap(number => Try(number.value.intValueExact).toEither.left.map(_ => "code must be an int32"))
        message <- fields
          .get("message")
          .toRight("Missing message")
          .flatMap(_.asString.toRight("message must be a string"))
          .flatMap {
            case value if value.nonEmpty => Right(value)
            case _                       => Left("Missing message")
          }
        data <- fields.get("data") match
          case Some(Json.Null) => Right(None)
          case Some(value)     => value.asString.toRight("data must be a string").map(Option(_).filter(_.nonEmpty))
          case None            => Right(None)
      yield A2AError(code, message, data)
    }
  }

  val ErrorInfoType   = "type.googleapis.com/google.rpc.ErrorInfo"
  val ErrorInfoDomain = "a2a-protocol.org"

  def errorInfoReason(code: Int): Option[String] =
    code match
      case A2AErrorCode.TaskNotFound                           => Some("TASK_NOT_FOUND")
      case A2AErrorCode.TaskNotCancelable                      => Some("TASK_NOT_CANCELABLE")
      case A2AErrorCode.PushNotificationNotSupported           => Some("PUSH_NOTIFICATION_NOT_SUPPORTED")
      case A2AErrorCode.UnsupportedOperation                   => Some("UNSUPPORTED_OPERATION")
      case A2AErrorCode.ContentTypeNotSupported                => Some("CONTENT_TYPE_NOT_SUPPORTED")
      case A2AErrorCode.InvalidAgentResponse                   => Some("INVALID_AGENT_RESPONSE")
      case A2AErrorCode.AuthenticatedExtendedCardNotConfigured => Some("EXTENDED_AGENT_CARD_NOT_CONFIGURED")
      case A2AErrorCode.ExtensionSupportRequired               => Some("EXTENSION_SUPPORT_REQUIRED")
      case A2AErrorCode.VersionNotSupported                    => Some("VERSION_NOT_SUPPORTED")
      case A2AErrorCode.Unauthenticated                        => Some("UNAUTHENTICATED")
      case _                                                   => None

  def httpStatus(error: A2AError): Int =
    error.code match
      case A2AErrorCode.TaskNotFound         => 404
      case A2AErrorCode.Unauthenticated      => 401
      case A2AErrorCode.InvalidAgentResponse => 500
      case A2AErrorCode.InternalError        => 500
      case _                                 => 400

  def grpcStatus(error: A2AError): A2AGrpcStatus =
    error.code match
      case A2AErrorCode.TaskNotFound =>
        A2AGrpcStatus.NOT_FOUND
      case A2AErrorCode.TaskNotCancelable | A2AErrorCode.PushNotificationNotSupported |
          A2AErrorCode.UnsupportedOperation | A2AErrorCode.AuthenticatedExtendedCardNotConfigured |
          A2AErrorCode.ExtensionSupportRequired | A2AErrorCode.VersionNotSupported =>
        A2AGrpcStatus.FAILED_PRECONDITION
      case A2AErrorCode.InvalidAgentResponse | A2AErrorCode.InternalError =>
        A2AGrpcStatus.INTERNAL
      case A2AErrorCode.Unauthenticated =>
        A2AGrpcStatus.UNAUTHENTICATED
      case _ =>
        A2AGrpcStatus.INVALID_ARGUMENT

  def httpStatusName(status: Int): String =
    status match
      case 401 => "UNAUTHENTICATED"
      case 404 => "NOT_FOUND"
      case 500 => "INTERNAL"
      case _   => "INVALID_ARGUMENT"

  def fromThrowable(error: Throwable): A2AError =
    error match
      case err: A2AError => err
      case other         => A2AError.internalError(Option(other.getMessage).getOrElse(other.getClass.getName))

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

  def pushNotificationConfigNotFound(configId: String): A2AError =
    A2AError(A2AErrorCode.TaskNotFound, s"Push notification config not found: $configId")

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

  def unauthenticated(detail: String): A2AError =
    A2AError(A2AErrorCode.Unauthenticated, s"Unauthenticated: $detail")

  def versionNotSupported(version: String): A2AError =
    A2AError(A2AErrorCode.VersionNotSupported, s"Version not supported: $version")

  def extensionSupportRequired(extension: String): A2AError =
    A2AError(A2AErrorCode.ExtensionSupportRequired, s"Extension support required: $extension")
end A2AError
