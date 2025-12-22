package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/** A2A Task - represents a long-running operation.
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
    contextId: Option[ContextId],
    status: TaskStatus,
    artifacts: List[Artifact] = Nil,
    history: List[A2AMessage] = Nil,
    metadata: Option[Json] = None
):
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
  def submitted(contextId: Option[ContextId] = None): A2ATask =
    A2ATask(
      id = TaskId.generate,
      contextId = contextId,
      status = TaskStatus.submitted
    )

/** Task status with state, optional message, and timestamps */
final case class TaskStatus(
    state: TaskState,
    message: Option[A2AMessage] = None,
    createdAt: Option[String] = None,
    updatedAt: Option[String] = None,
    stateTransitionHistory: List[StateTransition] = Nil
)
object TaskStatus:
  given JsonEncoder[TaskStatus] = DeriveJsonEncoder.gen[TaskStatus]
  given JsonDecoder[TaskStatus] = DeriveJsonDecoder.gen[TaskStatus]

  private def now: String = java.time.Instant.now().toString

  def submitted: TaskStatus =
    TaskStatus(state = TaskState.Submitted, createdAt = Some(now), updatedAt = Some(now))

  def working(message: Option[A2AMessage] = None): TaskStatus =
    TaskStatus(state = TaskState.Working, message = message, updatedAt = Some(now))

  def inputRequired(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.InputRequired, message = Some(message), updatedAt = Some(now))

  def completed(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.Completed, message = Some(message), updatedAt = Some(now))

  def canceled: TaskStatus =
    TaskStatus(state = TaskState.Canceled, updatedAt = Some(now))

  def failed(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.Failed, message = Some(message), updatedAt = Some(now))

  def rejected(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.Rejected, message = Some(message), updatedAt = Some(now))

  def authRequired(message: A2AMessage): TaskStatus =
    TaskStatus(state = TaskState.AuthRequired, message = Some(message), updatedAt = Some(now))

/** Task state enumeration (A2A spec) */
enum TaskState:
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
  given JsonEncoder[TaskState] = JsonEncoder.string.contramap {
    case Submitted     => "submitted"
    case Working       => "working"
    case InputRequired => "input-required"
    case Completed     => "completed"
    case Canceled      => "canceled"
    case Failed        => "failed"
    case Rejected      => "rejected"
    case AuthRequired  => "auth-required"
    case Unknown       => "unknown"
  }

  given JsonDecoder[TaskState] = JsonDecoder.string.mapOrFail {
    case "submitted"      => Right(Submitted)
    case "working"        => Right(Working)
    case "input-required" => Right(InputRequired)
    case "completed"      => Right(Completed)
    case "canceled"       => Right(Canceled)
    case "failed"         => Right(Failed)
    case "rejected"       => Right(Rejected)
    case "auth-required"  => Right(AuthRequired)
    case "unknown"        => Right(Unknown)
    case other            => Left(s"Unknown task state: $other")
  }

/** State transition record for history */
final case class StateTransition(
    state: TaskState,
    timestamp: String,
    message: Option[A2AMessage] = None
)
object StateTransition:
  given JsonEncoder[StateTransition] = DeriveJsonEncoder.gen[StateTransition]
  given JsonDecoder[StateTransition] = DeriveJsonDecoder.gen[StateTransition]

/** Push notification configuration for task updates */
final case class PushNotificationConfig(
    url: String,
    token: Option[String] = None,
    authentication: Option[PushNotificationAuth] = None
)
object PushNotificationConfig:
  given JsonEncoder[PushNotificationConfig] = DeriveJsonEncoder.gen[PushNotificationConfig]
  given JsonDecoder[PushNotificationConfig] = DeriveJsonDecoder.gen[PushNotificationConfig]

/** Authentication for push notifications */
final case class PushNotificationAuth(
    schemes: List[String],
    credentials: Option[String] = None
)
object PushNotificationAuth:
  given JsonEncoder[PushNotificationAuth] = DeriveJsonEncoder.gen[PushNotificationAuth]
  given JsonDecoder[PushNotificationAuth] = DeriveJsonDecoder.gen[PushNotificationAuth]
