package com.tjclp.scalagent.a2a

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

  /** Check if task is still running */
  def isRunning: Boolean = !isTerminal

  /** Get the final message (if completed) */
  def finalMessage: Option[A2AMessage] = status.message

object A2ATask:
  given JsonEncoder[A2ATask] = DeriveJsonEncoder.gen[A2ATask]
  given JsonDecoder[A2ATask] = DeriveJsonDecoder.gen[A2ATask]

  /** Create a new task in submitted state */
  def submitted(contextId: ContextId = ContextId.generate): A2ATask =
    A2ATask(
      id = TaskId.generate,
      contextId = contextId,
      status = TaskStatus.submitted,
    )

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
  given JsonEncoder[TaskStatus] = DeriveJsonEncoder.gen[TaskStatus]
  given JsonDecoder[TaskStatus] = DeriveJsonDecoder.gen[TaskStatus]

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

object TaskState:
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

  given JsonDecoder[TaskState] = StringEnumJsonCodec.decoderOrFail {
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
  }
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
  given JsonEncoder[StateTransition] = DeriveJsonEncoder.gen[StateTransition]
  given JsonDecoder[StateTransition] = DeriveJsonDecoder.gen[StateTransition]

/** Authentication information for push notification delivery. */
final case class AuthenticationInfo(
  scheme: String,
  credentials: String = "")
object AuthenticationInfo:
  given JsonEncoder[AuthenticationInfo] = DeriveJsonEncoder.gen[AuthenticationInfo]
  given JsonDecoder[AuthenticationInfo] = DeriveJsonDecoder.gen[AuthenticationInfo]

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
      for url <- fields.get("url").flatMap(_.asString).toRight("Missing url")
      yield TaskPushNotificationConfig(
        url = url,
        tenant = fields.get("tenant").flatMap(_.asString).filter(_.nonEmpty),
        id = fields.get("id").flatMap(_.asString).filter(_.nonEmpty),
        taskId = fields
          .get("taskId")
          .orElse(fields.get("task_id"))
          .flatMap(_.asString)
          .filter(_.nonEmpty)
          .map(TaskId(_)),
        token = fields.get("token").flatMap(_.asString).filter(_.nonEmpty),
        authentication = fields.get("authentication").flatMap(_.as[AuthenticationInfo].toOption),
      )
    }
  }

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

  def unapply(config: TaskPushNotificationConfig): Some[(String, Option[String], Option[String], Option[AuthenticationInfo])] =
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
  given JsonEncoder[PushNotificationAuth] = DeriveJsonEncoder.gen[PushNotificationAuth]
  given JsonDecoder[PushNotificationAuth] = DeriveJsonDecoder.gen[PushNotificationAuth]

  def fromAuthenticationInfo(auth: AuthenticationInfo): PushNotificationAuth =
    PushNotificationAuth(List(auth.scheme), Option(auth.credentials).filter(_.nonEmpty))
