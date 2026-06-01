package com.tjclp.scalagent.a2a

import scala.util.Try

import com.tjclp.scalagent.json.StringEnumJsonCodec
import zio.json.*
import zio.json.ast.Json

/**
 * A2A Task - represents a long-running operation.
 *
 * Tasks track the lifecycle of an agent operation from submission through completion. They
 * maintain history, artifacts, and status information.
 *
 * @param id
 *   Unique task identifier (server-generated)
 * @param contextId
 *   Context/conversation identifier
 * @param status
 *   Current task status
 * @param artifacts
 *   Output artifacts produced by the agent
 * @param history
 *   Message history for this task
 * @param metadata
 *   Extension-specific metadata
 */
final case class A2ATask(
  id: TaskId,
  contextId: ContextId,
  status: TaskStatus,
  artifacts: List[Artifact] = Nil,
  history: List[A2AMessage] = Nil,
  metadata: Option[Json] = None):
  /** Check if task is in a terminal state */
  def isTerminal: Boolean = status.state.isTerminal

  /** Check if task is paused awaiting external input or authentication. */
  def isInterrupted: Boolean = status.state.isInterrupted

  /** Check if the current server stream should end for this task state. */
  def isStreamEnding: Boolean = status.state.isStreamEnding

  /** Check if task is still running */
  def isRunning: Boolean = !isTerminal

  /** Get the final message (if completed) */
  def finalMessage: Option[A2AMessage] = status.message
end A2ATask

object A2ATask:
  def toJsonObject(task: A2ATask, includeEmptyArtifacts: Boolean = false): Json.Obj =
    var obj = Json.Obj(
      "id"        -> Json.Str(task.id.value),
      "contextId" -> Json.Str(task.contextId.value),
      "status"    -> task.status.toJsonAST.toOption.get,
    )
    if includeEmptyArtifacts || task.artifacts.nonEmpty then
      obj = obj.add("artifacts", Json.Arr(task.artifacts.map(_.toJsonAST.toOption.get)*))
    if task.history.nonEmpty then obj = obj.add("history", Json.Arr(task.history.map(_.toJsonAST.toOption.get)*))
    task.metadata.foreach(metadata => obj = obj.add("metadata", metadata))
    obj

  given JsonEncoder[A2ATask] = JsonEncoder[Json].contramap(task => toJsonObject(task))

  given JsonDecoder[A2ATask] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("Task must be an object").flatMap { obj =>
      val fields                                                             = obj.toMap
      def decodeList[A: JsonDecoder](field: String): Either[String, List[A]] =
        fields.get(field) match
          case Some(Json.Null) => Right(Nil)
          case Some(value)     =>
            value.asArray.toRight(s"$field must be an array").flatMap { values =>
              values.toList.map(_.as[A]).foldRight[Either[String, List[A]]](Right(Nil)) {
                case (Right(value), Right(values)) => Right(value :: values)
                case (Left(error), _)              => Left(error)
                case (_, Left(error))              => Left(error)
              }
            }
          case None => Right(Nil)
      def optionalString(field: String, aliases: String*): Either[String, Option[String]] =
        (field +: aliases).iterator.flatMap(fields.get).nextOption() match
          case Some(Json.Null) => Right(None)
          case Some(value)     => value.asString.map(Some(_)).toRight(s"$field must be a string")
          case None            => Right(None)

      for
        id        <- fields.get("id").flatMap(_.asString).filter(_.nonEmpty).map(TaskId(_)).toRight("Missing id")
        contextId <- optionalString("contextId", "context_id").map {
          case Some(value) if value.nonEmpty => ContextId(value)
          case _                             => ContextId(id.value)
        }
        status    <- fields.get("status").toRight("Missing status").flatMap(_.as[TaskStatus])
        artifacts <- decodeList[Artifact]("artifacts")
        history   <- decodeList[A2AMessage]("history")
        metadata  <- A2AJson.optionalStruct(fields, "metadata")
      yield A2ATask(
        id = id,
        contextId = contextId,
        status = status,
        artifacts = artifacts,
        history = history,
        metadata = metadata,
      )
      end for
    }
  }

  /** Create a new task in submitted state */
  def submitted(contextId: ContextId = ContextId.generate): A2ATask =
    A2ATask(
      id = TaskId.generate,
      contextId = contextId,
      status = TaskStatus.submitted,
    )
end A2ATask

/**
 * Task status with state, optional message, and timestamp.
 *
 * SDK 0.3.12 no longer includes `stateTransitionHistory` on `TaskStatus`.
 */
final case class TaskStatus(
  state: TaskState,
  message: Option[A2AMessage] = None,
  timestamp: Option[String] = None)
object TaskStatus:
  given JsonEncoder[TaskStatus] = JsonEncoder[Json].contramap { status =>
    var obj = Json.Obj("state" -> status.state.toJsonAST.toOption.get)
    status.message.foreach(value => obj = obj.add("message", value.toJsonAST.toOption.get))
    status.timestamp.filter(_.nonEmpty).foreach(value => obj = obj.add("timestamp", Json.Str(value)))
    obj
  }
  given JsonDecoder[TaskStatus] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("TaskStatus must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        state <- fields
          .get("state")
          .toRight("Missing state")
          .flatMap(_.as[TaskState])
          .flatMap(TaskState.requireSpecified(_, "state"))
        message <- fields.get("message") match
          case Some(Json.Null) => Right(None)
          case Some(value)     => value.as[A2AMessage].map(Some(_))
          case None            => Right(None)
        timestamp <- fields.get("timestamp") match
          case Some(Json.Null) => Right(None)
          case Some(value)     =>
            value.asString
              .toRight("timestamp must be a string")
              .flatMap(validateTimestamp)
              .map(Some(_))
          case None =>
            Right(None)
      yield TaskStatus(state, message, timestamp)
      end for
    }
  }

  def validateTimestamp(value: String): Either[String, String] =
    if value.endsWith("Z") && Try(java.time.Instant.parse(value)).isSuccess then Right(value)
    else Left(s"timestamp must be an ISO 8601 UTC timestamp ending in Z, got $value")

  private def now: String = java.time.Instant.now().toString

  def submitted: TaskStatus =
    TaskStatus(state = TaskState.Submitted, timestamp = Some(now))

  def working(message: Option[A2AMessage] = None): TaskStatus =
    TaskStatus(state = TaskState.Working, message = message, timestamp = Some(now))

  def inputRequired(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.InputRequired, message = Some(message), timestamp = Some(now))

  def completed(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.Completed, message = Some(message), timestamp = Some(now))

  def canceled: TaskStatus =
    TaskStatus(state = TaskState.Canceled, timestamp = Some(now))

  def failed(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.Failed, message = Some(message), timestamp = Some(now))

  def rejected(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.Rejected, message = Some(message), timestamp = Some(now))

  def authRequired(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.AuthRequired, message = Some(message), timestamp = Some(now))
end TaskStatus

/** Task state enumeration (A2A spec) */
enum TaskState derives CanEqual:
  case Submitted
  case Working
  case InputRequired
  case Completed
  case Canceled
  case Failed
  case Rejected
  case AuthRequired
  case Unknown

  /** Check if this is a terminal state */
  def isTerminal: Boolean = this match
    case Completed | Canceled | Failed | Rejected => true
    case _                                        => false

  /** Check if this is an interrupted state awaiting external input. */
  def isInterrupted: Boolean = this match
    case InputRequired | AuthRequired => true
    case _                            => false

  /** Check if this state ends the current stream. */
  def isStreamEnding: Boolean =
    isTerminal || isInterrupted

  /** Lower-kebab value used by the JS SDK facade and accepted as a legacy JSON alias. */
  def lowerKebabValue: String = this match
    case Submitted     => "submitted"
    case Working       => "working"
    case InputRequired => "input-required"
    case Completed     => "completed"
    case Canceled      => "canceled"
    case Failed        => "failed"
    case Rejected      => "rejected"
    case AuthRequired  => "auth-required"
    case Unknown       => "unknown"
end TaskState

object TaskState:
  private val specifiedJsonValues = List(
    "TASK_STATE_SUBMITTED",
    "TASK_STATE_WORKING",
    "TASK_STATE_COMPLETED",
    "TASK_STATE_FAILED",
    "TASK_STATE_CANCELED",
    "TASK_STATE_REJECTED",
    "TASK_STATE_INPUT_REQUIRED",
    "TASK_STATE_AUTH_REQUIRED",
  )

  private[a2a] def specifiedValuesMessage: String =
    specifiedJsonValues.mkString(", ")

  private[a2a] def requireSpecified(state: TaskState, field: String): Either[String, TaskState] =
    state match
      case Unknown => Left(s"$field must be one of: $specifiedValuesMessage")
      case other   => Right(other)

  given JsonEncoder[TaskState] = StringEnumJsonCodec.encoder {
    case Submitted     => "TASK_STATE_SUBMITTED"
    case Working       => "TASK_STATE_WORKING"
    case InputRequired => "TASK_STATE_INPUT_REQUIRED"
    case Completed     => "TASK_STATE_COMPLETED"
    case Canceled      => "TASK_STATE_CANCELED"
    case Failed        => "TASK_STATE_FAILED"
    case Rejected      => "TASK_STATE_REJECTED"
    case AuthRequired  => "TASK_STATE_AUTH_REQUIRED"
    case Unknown       => "TASK_STATE_UNSPECIFIED"
  }

  def fromWireValue(value: String): Either[String, TaskState] = value match
    case "TASK_STATE_SUBMITTED" | "submitted"           => Right(Submitted)
    case "TASK_STATE_WORKING" | "working"               => Right(Working)
    case "TASK_STATE_INPUT_REQUIRED" | "input-required" => Right(InputRequired)
    case "TASK_STATE_COMPLETED" | "completed"           => Right(Completed)
    case "TASK_STATE_CANCELED" | "canceled"             => Right(Canceled)
    case "TASK_STATE_FAILED" | "failed"                 => Right(Failed)
    case "TASK_STATE_REJECTED" | "rejected"             => Right(Rejected)
    case "TASK_STATE_AUTH_REQUIRED" | "auth-required"   => Right(AuthRequired)
    case "TASK_STATE_UNSPECIFIED" | "unknown"           => Right(Unknown)
    case other                                          => Left(s"Unknown task state: $other")

  given JsonDecoder[TaskState] = StringEnumJsonCodec.decoderOrFail(fromWireValue)
end TaskState

/**
 * State transition record retained for callers that persist transition history separately.
 *
 * The A2A 0.3.12 SDK does not emit these on `TaskStatus`, but keeping the model avoids an
 * unnecessary source break for downstream code that still stores transition history.
 */
final case class StateTransition(
  state: TaskState,
  timestamp: String,
  message: Option[A2AMessage] = None)
object StateTransition:
  given JsonEncoder[StateTransition] = JsonEncoder[Json].contramap { transition =>
    var obj = Json.Obj(
      "state"     -> transition.state.toJsonAST.toOption.get,
      "timestamp" -> Json.Str(transition.timestamp),
    )
    transition.message.foreach(value => obj = obj.add("message", value.toJsonAST.toOption.get))
    obj
  }

  given JsonDecoder[StateTransition] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("StateTransition must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        state     <- fields.get("state").toRight("Missing state").flatMap(_.as[TaskState])
        timestamp <- fields
          .get("timestamp")
          .toRight("Missing timestamp")
          .flatMap(_.asString.toRight("timestamp must be a string"))
          .flatMap {
            case value if value.nonEmpty => Right(value)
            case _                       => Left("Missing timestamp")
          }
        message <- fields.get("message") match
          case Some(Json.Null) => Right(None)
          case Some(value)     => value.as[A2AMessage].map(Some(_))
          case None            => Right(None)
      yield StateTransition(state, timestamp, message)
    }
  }
end StateTransition

/** Authentication information for push notification delivery. */
final case class AuthenticationInfo(
  scheme: String,
  credentials: String = "")
object AuthenticationInfo:
  given JsonEncoder[AuthenticationInfo] = JsonEncoder[Json].contramap { auth =>
    var obj = Json.Obj("scheme" -> Json.Str(auth.scheme))
    if auth.credentials.nonEmpty then obj = obj.add("credentials", Json.Str(auth.credentials))
    obj
  }
  given JsonDecoder[AuthenticationInfo] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("AuthenticationInfo must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        scheme      <- fields.get("scheme").flatMap(_.asString).filter(_.nonEmpty).toRight("Missing scheme")
        credentials <- fields.get("credentials") match
          case Some(Json.Null) => Right("")
          case Some(value)     => value.asString.toRight("credentials must be a string")
          case None            => Right("")
      yield AuthenticationInfo(
        scheme = scheme,
        credentials = credentials,
      )
    }
  }
end AuthenticationInfo

/** Push notification configuration for task updates (A2A v1 TaskPushNotificationConfig). */
final case class TaskPushNotificationConfig(
  url: String,
  tenant: Option[String] = None,
  id: Option[String] = None,
  taskId: Option[TaskId] = None,
  token: Option[String] = None,
  authentication: Option[AuthenticationInfo] = None)
object TaskPushNotificationConfig:
  given JsonEncoder[TaskPushNotificationConfig] = JsonEncoder[Json].contramap { config =>
    var obj = Json.Obj("url" -> Json.Str(config.url))
    config.tenant.filter(_.nonEmpty).foreach(value => obj = obj.add("tenant", Json.Str(value)))
    config.id.filter(_.nonEmpty).foreach(value => obj = obj.add("id", Json.Str(value)))
    config.taskId.filter(_.nonEmpty).foreach(value => obj = obj.add("taskId", Json.Str(value.value)))
    config.token.filter(_.nonEmpty).foreach(value => obj = obj.add("token", Json.Str(value)))
    config.authentication.foreach(value => obj = obj.add("authentication", value.toJsonAST.toOption.get))
    obj
  }

  given JsonDecoder[TaskPushNotificationConfig] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("TaskPushNotificationConfig must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        url            <- fields.get("url").flatMap(_.asString).filter(_.nonEmpty).toRight("Missing url")
        tenant         <- A2AJson.optionalString(fields, "tenant")
        id             <- A2AJson.optionalString(fields, "id")
        taskId         <- A2AJson.optionalString(fields, "taskId", "task_id")
        token          <- A2AJson.optionalString(fields, "token")
        authentication <- fields.get("authentication") match
          case Some(Json.Null) => Right(None)
          case Some(value)     => value.as[AuthenticationInfo].map(Some(_))
          case None            => Right(None)
      yield TaskPushNotificationConfig(
        url = url,
        tenant = tenant.filter(_.nonEmpty),
        id = id.filter(_.nonEmpty),
        taskId = taskId.filter(_.nonEmpty).map(TaskId(_)),
        token = token.filter(_.nonEmpty),
        authentication = authentication,
      )
    }
  }
end TaskPushNotificationConfig

/** Backwards-compatible name retained while v1 callers move to TaskPushNotificationConfig. */
type PushNotificationConfig = TaskPushNotificationConfig
object PushNotificationConfig:
  def apply(
    url: String,
    id: Option[String] = None,
    token: Option[String] = None,
    authentication: Option[PushNotificationAuth] = None,
  ): TaskPushNotificationConfig =
    TaskPushNotificationConfig(
      url = url,
      id = id,
      token = token,
      authentication = authentication.map(_.toAuthenticationInfo),
    )

  def unapply(config: TaskPushNotificationConfig)
    : Some[(String, Option[String], Option[String], Option[AuthenticationInfo])] =
    Some((config.url, config.id, config.token, config.authentication))

/** Legacy authentication shape accepted by the compatibility constructor. */
final case class PushNotificationAuth(
  schemes: List[String],
  credentials: Option[String] = None):
  def toAuthenticationInfo: AuthenticationInfo =
    AuthenticationInfo(
      scheme = schemes.headOption.getOrElse("Bearer"),
      credentials = credentials.getOrElse(""),
    )
object PushNotificationAuth:
  given JsonEncoder[PushNotificationAuth] = JsonEncoder[Json].contramap { auth =>
    var obj = Json.Obj("schemes" -> Json.Arr(auth.schemes.map(Json.Str(_))*))
    auth.credentials.filter(_.nonEmpty).foreach(value => obj = obj.add("credentials", Json.Str(value)))
    obj
  }

  given JsonDecoder[PushNotificationAuth] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("PushNotificationAuth must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        schemes <- fields
          .get("schemes")
          .toRight("Missing schemes")
          .flatMap { value =>
            value.asArray
              .toRight("schemes must be an array")
              .flatMap(values =>
                values.toList.zipWithIndex.foldRight[Either[String, List[String]]](Right(Nil)) {
                  case ((value, index), Right(values)) =>
                    value.asString.map(_ :: values).toRight(s"schemes[$index] must be a string")
                  case ((_, _), Left(error)) => Left(error)
                }
              )
          }
        credentials <- fields.get("credentials") match
          case Some(Json.Null) => Right(None)
          case Some(value) => value.asString.toRight("credentials must be a string").map(Option(_).filter(_.nonEmpty))
          case None        => Right(None)
      yield PushNotificationAuth(schemes, credentials)
      end for
    }
  }

  def fromAuthenticationInfo(auth: AuthenticationInfo): PushNotificationAuth =
    PushNotificationAuth(List(auth.scheme), Option(auth.credentials).filter(_.nonEmpty))
end PushNotificationAuth
