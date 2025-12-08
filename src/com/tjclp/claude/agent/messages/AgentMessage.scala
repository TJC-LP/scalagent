package com.tjclp.claude.agent.messages

import zio.json._

/** All message types emitted by the Claude Agent SDK.
  *
  * This sealed hierarchy mirrors the TypeScript SDK's `SDKMessage` discriminated union.
  */
enum AgentMessage:
  /** Assistant response message */
  case Assistant(
      message: ApiAssistantMessage,
      parentToolUseId: Option[String],
      error: Option[AssistantMessageError],
      uuid: String,
      sessionId: String
  )

  /** User message (including synthetic tool results) */
  case User(
      message: ApiUserMessage,
      parentToolUseId: Option[String],
      isSynthetic: Boolean,
      toolUseResult: Option[zio.json.ast.Json],
      uuid: Option[String],
      sessionId: String
  )

  /** User message replay (from session resume) */
  case UserReplay(
      message: ApiUserMessage,
      parentToolUseId: Option[String],
      uuid: String,
      sessionId: String
  )

  /** Final result message */
  case Result(
      outcome: ResultOutcome,
      uuid: String,
      sessionId: String
  )

  /** System event message */
  case System(
      event: SystemEvent,
      uuid: String,
      sessionId: String
  )

  /** Streaming event (partial response) */
  case StreamEvent(
      event: RawStreamEvent,
      parentToolUseId: Option[String],
      uuid: String,
      sessionId: String
  )

  /** Tool execution progress */
  case ToolProgress(
      toolUseId: String,
      toolName: String,
      parentToolUseId: Option[String],
      elapsedTimeSeconds: Double,
      uuid: String,
      sessionId: String
  )

  /** Authentication status update */
  case AuthStatus(
      isAuthenticating: Boolean,
      output: List[String],
      error: Option[String],
      uuid: String,
      sessionId: String
  )

object AgentMessage:
  given JsonDecoder[AgentMessage] = DeriveJsonDecoder.gen[AgentMessage]
  given JsonEncoder[AgentMessage] = DeriveJsonEncoder.gen[AgentMessage]

/** API assistant message structure */
final case class ApiAssistantMessage(
    id: String,
    role: String,
    content: List[ContentBlock],
    model: String,
    stopReason: Option[String],
    stopSequence: Option[String],
    usage: Option[ModelUsage]
)

object ApiAssistantMessage:
  given JsonDecoder[ApiAssistantMessage] = DeriveJsonDecoder.gen[ApiAssistantMessage]
  given JsonEncoder[ApiAssistantMessage] = DeriveJsonEncoder.gen[ApiAssistantMessage]

/** API user message structure */
final case class ApiUserMessage(
    role: String,
    content: List[ContentBlock]
)

object ApiUserMessage:
  given JsonDecoder[ApiUserMessage] = DeriveJsonDecoder.gen[ApiUserMessage]
  given JsonEncoder[ApiUserMessage] = DeriveJsonEncoder.gen[ApiUserMessage]

/** Assistant message error types */
enum AssistantMessageError:
  case AuthenticationFailed
  case BillingError
  case RateLimit
  case InvalidRequest
  case ServerError
  case Unknown

object AssistantMessageError:
  given JsonDecoder[AssistantMessageError] = DeriveJsonDecoder.gen[AssistantMessageError]
  given JsonEncoder[AssistantMessageError] = DeriveJsonEncoder.gen[AssistantMessageError]

  def fromString(s: String): AssistantMessageError = s match
    case "authentication_failed" => AuthenticationFailed
    case "billing_error"         => BillingError
    case "rate_limit"            => RateLimit
    case "invalid_request"       => InvalidRequest
    case "server_error"          => ServerError
    case _                       => Unknown

/** Raw streaming event from the API */
final case class RawStreamEvent(
    eventType: String,
    index: Option[Int],
    contentBlock: Option[ContentBlock],
    delta: Option[StreamDelta]
)

object RawStreamEvent:
  given JsonDecoder[RawStreamEvent] = DeriveJsonDecoder.gen[RawStreamEvent]
  given JsonEncoder[RawStreamEvent] = DeriveJsonEncoder.gen[RawStreamEvent]

/** Stream delta for incremental updates */
enum StreamDelta:
  case TextDelta(text: String)
  case InputJsonDelta(partialJson: String)
  case ThinkingDelta(thinking: String)

object StreamDelta:
  given JsonDecoder[StreamDelta] = DeriveJsonDecoder.gen[StreamDelta]
  given JsonEncoder[StreamDelta] = DeriveJsonEncoder.gen[StreamDelta]
