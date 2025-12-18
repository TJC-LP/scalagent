package com.tjclp.scalagent.messages

import zio.json._
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{ApiMessageId, MessageUuid, SessionId, ToolUseId}

/** All message types emitted by the Claude Agent SDK.
  *
  * This sealed hierarchy mirrors the TypeScript SDK's `SDKMessage` discriminated union.
  */
enum AgentMessage:
  /** Assistant response message */
  case Assistant(
      message: ApiAssistantMessage,
      parentToolUseId: Option[ToolUseId],
      error: Option[AssistantMessageError],
      uuid: MessageUuid,
      sessionId: SessionId
  )

  /** User message (including synthetic tool results) */
  case User(
      message: ApiUserMessage,
      parentToolUseId: Option[ToolUseId],
      isSynthetic: Boolean,
      toolUseResult: Option[zio.json.ast.Json],
      uuid: Option[MessageUuid],
      sessionId: SessionId
  )

  /** User message replay (from session resume) */
  case UserReplay(
      message: ApiUserMessage,
      parentToolUseId: Option[ToolUseId],
      uuid: MessageUuid,
      sessionId: SessionId
  )

  /** Final result message */
  case Result(
      outcome: ResultOutcome,
      uuid: MessageUuid,
      sessionId: SessionId
  )

  /** System event message */
  case System(
      event: SystemEvent,
      uuid: MessageUuid,
      sessionId: SessionId
  )

  /** Streaming event (partial response) */
  case StreamEvent(
      event: RawStreamEvent,
      parentToolUseId: Option[ToolUseId],
      uuid: MessageUuid,
      sessionId: SessionId
  )

  /** Tool execution progress */
  case ToolProgress(
      toolUseId: ToolUseId,
      toolName: ToolName,
      parentToolUseId: Option[ToolUseId],
      elapsedTimeSeconds: Double,
      uuid: MessageUuid,
      sessionId: SessionId
  )

  /** Authentication status update */
  case AuthStatus(
      isAuthenticating: Boolean,
      output: List[String],
      error: Option[String],
      uuid: MessageUuid,
      sessionId: SessionId
  )

object AgentMessage:
  given JsonDecoder[AgentMessage] = DeriveJsonDecoder.gen[AgentMessage]
  given JsonEncoder[AgentMessage] = DeriveJsonEncoder.gen[AgentMessage]

  // Extension methods for ergonomic message extraction
  extension (msg: AgentMessage)
    /** Extract all text content from this message */
    def text: Option[String] = msg match
      case Assistant(message, _, _, _, _) =>
        val texts = message.content.collect { case ContentBlock.Text(t) => t }
        if texts.isEmpty then None else Some(texts.mkString)
      case User(message, _, _, _, _, _) =>
        val texts = message.content.collect { case ContentBlock.Text(t) => t }
        if texts.isEmpty then None else Some(texts.mkString)
      case UserReplay(message, _, _, _) =>
        val texts = message.content.collect { case ContentBlock.Text(t) => t }
        if texts.isEmpty then None else Some(texts.mkString)
      case StreamEvent(event, _, _, _) =>
        event.delta.collect { case StreamDelta.TextDelta(t) => t }
      case _ => None

    /** Extract all tool use requests from this message */
    def toolCalls: List[ContentBlock.ToolUse] = msg match
      case Assistant(message, _, _, _, _) =>
        message.content.collect { case tu: ContentBlock.ToolUse => tu }
      case _ => Nil

    /** Extract all tool results from this message */
    def toolResults: List[ContentBlock.ToolResult] = msg match
      case User(message, _, _, _, _, _) =>
        message.content.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil

    /** Check if this is a final result message */
    def isResult: Boolean = msg match
      case _: Result => true
      case _         => false

    /** Check if this message indicates completion */
    def isComplete: Boolean = msg match
      case Result(ResultOutcome.Success(_, _, _, _, _, _, _, _, _), _, _) => true
      case Result(ResultOutcome.Error(_, _, _, _, _, _, _, _, _), _, _)   => true
      case _                                                              => false

    /** Get the result outcome if this is a Result message */
    def asResult: Option[ResultOutcome] = msg match
      case Result(outcome, _, _) => Some(outcome)
      case _                     => None

    /** Check if this is an assistant message */
    def isAssistant: Boolean = msg match
      case _: Assistant => true
      case _            => false

    /** Check if this is a user message */
    def isUser: Boolean = msg match
      case _: User | _: UserReplay => true
      case _                       => false

  // Extension methods for message lists
  extension (messages: List[AgentMessage])
    /** Extract all text from all messages */
    def allText: String =
      messages.flatMap(_.text).mkString("\n")

    /** Get the final result if present */
    def finalResult: Option[ResultOutcome] =
      messages.collectFirst { case Result(outcome, _, _) => outcome }

    /** Extract all tool calls from all messages */
    def allToolCalls: List[ContentBlock.ToolUse] =
      messages.flatMap(_.toolCalls)

    /** Extract all tool results from all messages */
    def allToolResults: List[ContentBlock.ToolResult] =
      messages.flatMap(_.toolResults)

    /** Get only assistant messages */
    def assistantMessages: List[AgentMessage.Assistant] =
      messages.collect { case a: AgentMessage.Assistant => a }

    /** Check if the conversation completed successfully */
    def isSuccess: Boolean =
      finalResult.exists {
        case _: ResultOutcome.Success => true
        case _                        => false
      }

/** API assistant message structure */
final case class ApiAssistantMessage(
    id: ApiMessageId,
    role: Role,
    content: List[ContentBlock],
    model: String,
    stopReason: Option[StopReason],
    stopSequence: Option[String],
    usage: Option[ModelUsage]
)

object ApiAssistantMessage:
  given JsonDecoder[ApiAssistantMessage] = DeriveJsonDecoder.gen[ApiAssistantMessage]
  given JsonEncoder[ApiAssistantMessage] = DeriveJsonEncoder.gen[ApiAssistantMessage]

/** API user message structure */
final case class ApiUserMessage(
    role: Role,
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
