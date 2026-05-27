package com.tjclp.scalagent.messages

import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.config.FastModeState
import com.tjclp.scalagent.json.StringEnumJsonCodec
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{ApiMessageId, MessageUuid, SessionId, ToolUseId}

/**
 * All message types emitted by the Claude Agent SDK.
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
    sessionId: SessionId,
    /** Anthropic API request ID. Added in SDK 0.3.142. */
    requestId: Option[String] = None,
    /** Subagent type that produced this message. Added in SDK 0.3.142. */
    subagentType: Option[String] = None,
    /** Description of the subagent task that produced this message. Added in SDK 0.3.142. */
    taskDescription: Option[String] = None)

  /** User message (including synthetic tool results) */
  case User(
    message: ApiUserMessage,
    parentToolUseId: Option[ToolUseId],
    isSynthetic: Boolean,
    toolUseResult: Option[Json],
    uuid: Option[MessageUuid],
    sessionId: SessionId,
    timestamp: Option[String] = None,
    /** Structured origin of the message (for example `{ "kind": "peer" }`). Added in SDK 0.2.126. */
    origin: Option[Json] = None,
    /** Subagent type that produced this message. Added in SDK 0.3.142. */
    subagentType: Option[String] = None,
    /** Description of the subagent task that produced this message. Added in SDK 0.3.142. */
    taskDescription: Option[String] = None)

  /** User message replay (from session resume) */
  case UserReplay(
    message: ApiUserMessage,
    parentToolUseId: Option[ToolUseId],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Final result message */
  case Result(
    outcome: ResultOutcome,
    fastModeState: Option[FastModeState],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** System event message */
  case System(
    event: SystemEvent,
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Streaming event (partial response) */
  case StreamEvent(
    event: RawStreamEvent,
    parentToolUseId: Option[ToolUseId],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Tool execution progress */
  case ToolProgress(
    toolUseId: ToolUseId,
    toolName: ToolName,
    parentToolUseId: Option[ToolUseId],
    elapsedTimeSeconds: Double,
    taskId: Option[String],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Authentication status update */
  case AuthStatus(
    isAuthenticating: Boolean,
    output: List[String],
    error: Option[String],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Task/subagent completion notification */
  case TaskNotification(
    taskId: String,
    status: TaskStatus,
    outputFile: String,
    summary: String,
    toolUseId: Option[ToolUseId],
    usage: Option[ModelUsage],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Tool use summary (aggregate info) */
  case ToolUseSummary(
    summary: String,
    precedingToolUseIds: List[ToolUseId],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Prompt suggestion after a turn (when promptSuggestions is enabled) */
  case PromptSuggestion(
    suggestion: String,
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Rate limit event */
  case RateLimitEvent(
    retryAfterMs: Option[Long],
    model: Option[String],
    status: Option[String],
    resetsAt: Option[Long],
    rateLimitType: Option[String],
    utilization: Option[Double],
    uuid: MessageUuid,
    sessionId: SessionId,
    overageStatus: Option[String] = None,
    overageResetsAt: Option[String] = None,
    overageDisabledReason: Option[String] = None,
    isUsingOverage: Option[Boolean] = None,
    surpassedThreshold: Option[Boolean] = None)

  /** Local command output */
  case LocalCommandOutput(
    output: String,
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Elicitation complete */
  case ElicitationComplete(
    mcpServerName: String,
    elicitationId: String,
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Task started notification */
  case TaskStarted(
    taskId: String,
    description: String,
    uuid: MessageUuid,
    sessionId: SessionId,
    toolUseId: Option[String] = None,
    taskType: Option[String] = None,
    prompt: Option[String] = None,
    workflowName: Option[String] = None)

  /** Task progress update */
  case TaskProgress(
    taskId: String,
    progress: String,
    uuid: MessageUuid,
    sessionId: SessionId,
    summary: Option[String] = None,
    toolUseId: Option[String] = None,
    lastToolName: Option[String] = None)

  /** API retry notification - emitted when a retryable error occurs and the request will be retried */
  case ApiRetry(
    attempt: Int,
    maxRetries: Int,
    retryDelayMs: Long,
    errorStatus: Option[Int],
    error: AssistantMessageError,
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Bridge metadata message carrying slash commands */
  case BridgeMetadata(
    @jsonField("slash_commands") slashCommands: List[String],
    uuid: MessageUuid,
    sessionId: SessionId)

  /**
   * SessionStore mirror-error event (SDK 0.2.113).
   * Emitted when a transcript-mirror batch write to `SessionStore.append()`
   * fails or times out. The batch is dropped (at-most-once delivery); this
   * message surfaces the failure so consumers aren't silent on data loss.
   */
  case MirrorError(
    error: String,
    projectKey: String,
    mirroredSessionId: SessionId,
    subpath: Option[String],
    uuid: MessageUuid,
    sessionId: SessionId)

  /** Forward-compatible fallback for unknown top-level SDK messages */
  case Unknown(
    envelope: UnknownEnvelope)
end AgentMessage

object AgentMessage:
  given JsonDecoder[AgentMessage] = DeriveJsonDecoder.gen[AgentMessage]
  given JsonEncoder[AgentMessage] = DeriveJsonEncoder.gen[AgentMessage]

  // Extension methods for ergonomic message extraction
  extension (msg: AgentMessage)
    /** Extract all text content from this message */
    def text: Option[String] = msg match
      case assistant: Assistant =>
        val texts = assistant.message.content.collect { case ContentBlock.Text(t) => t }
        if texts.isEmpty then None else Some(texts.mkString)
      case user: User =>
        val texts = user.message.content.collect { case ContentBlock.Text(t) => t }
        if texts.isEmpty then None else Some(texts.mkString)
      case UserReplay(message, _, _, _) =>
        val texts = message.content.collect { case ContentBlock.Text(t) => t }
        if texts.isEmpty then None else Some(texts.mkString)
      case StreamEvent(event, _, _, _) =>
        event.delta.collect { case StreamDelta.TextDelta(t) => t }
      case PromptSuggestion(suggestion, _, _) =>
        Some(suggestion)
      case _ => None

    /** Extract all tool use requests from this message */
    def toolCalls: List[ContentBlock.ToolUse] = msg match
      case assistant: Assistant =>
        assistant.message.content.collect { case tu: ContentBlock.ToolUse => tu }
      case _ => Nil

    /** Extract all tool results from this message */
    def toolResults: List[ContentBlock.ToolResult] = msg match
      case user: User =>
        user.message.content.collect { case tr: ContentBlock.ToolResult => tr }
      case _ => Nil

    /** Check if this is a final result message */
    def isResult: Boolean = msg match
      case _: Result => true
      case _         => false

    /** Check if this message indicates completion */
    def isComplete: Boolean = msg match
      case _: Result => true
      case _         => false

    /** Get the result outcome if this is a Result message */
    def asResult: Option[ResultOutcome] = msg match
      case Result(outcome, _, _, _) => Some(outcome)
      case _                        => None

    /** Check if this is an assistant message */
    def isAssistant: Boolean = msg match
      case _: Assistant => true
      case _            => false

    /** Check if this is a user message */
    def isUser: Boolean = msg match
      case _: User | _: UserReplay => true
      case _                       => false

    /** Check if this is a task notification message */
    def isTaskNotification: Boolean = msg match
      case _: TaskNotification => true
      case _                   => false

    /** Check if this is a tool use summary message */
    def isToolUseSummary: Boolean = msg match
      case _: ToolUseSummary => true
      case _                 => false

    /** Check if this is a prompt suggestion */
    def isPromptSuggestion: Boolean = msg match
      case _: PromptSuggestion => true
      case _                   => false
  end extension

  // Extension methods for message lists
  extension (messages: List[AgentMessage])
    /** Extract all text from all messages */
    def allText: String =
      messages.flatMap(_.text).mkString("\n")

    /** Get the final result if present */
    def finalResult: Option[ResultOutcome] =
      messages.collectFirst { case Result(outcome, _, _, _) => outcome }

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

    /** Extract all task notifications from messages */
    def taskNotifications: List[AgentMessage.TaskNotification] =
      messages.collect { case tn: AgentMessage.TaskNotification => tn }

    /** Extract all tool use summaries from messages */
    def toolUseSummaries: List[AgentMessage.ToolUseSummary] =
      messages.collect { case tus: AgentMessage.ToolUseSummary => tus }

    /** Extract all prompt suggestions from messages */
    def promptSuggestions: List[AgentMessage.PromptSuggestion] =
      messages.collect { case ps: AgentMessage.PromptSuggestion => ps }
  end extension
end AgentMessage

/** API assistant message structure */
final case class ApiAssistantMessage(
  id: ApiMessageId,
  role: Role,
  content: List[ContentBlock],
  model: String,
  stopReason: Option[StopReason],
  stopSequence: Option[String],
  usage: Option[ModelUsage])

object ApiAssistantMessage:
  given JsonDecoder[ApiAssistantMessage] = DeriveJsonDecoder.gen[ApiAssistantMessage]
  given JsonEncoder[ApiAssistantMessage] = DeriveJsonEncoder.gen[ApiAssistantMessage]

/** API user message structure */
final case class ApiUserMessage(
  role: Role,
  content: List[ContentBlock])

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
  case MaxOutputTokens
  /** Requested model was not available. Added in SDK 0.3.144. */
  case ModelNotFound
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
    case "max_output_tokens"     => MaxOutputTokens
    case "model_not_found"       => ModelNotFound
    case _                       => Unknown

/** Raw streaming event from the API */
final case class RawStreamEvent(
  eventType: String,
  index: Option[Int],
  contentBlock: Option[ContentBlock],
  delta: Option[StreamDelta])

object RawStreamEvent:
  given JsonDecoder[RawStreamEvent] = DeriveJsonDecoder.gen[RawStreamEvent]
  given JsonEncoder[RawStreamEvent] = DeriveJsonEncoder.gen[RawStreamEvent]

/** Stream delta for incremental updates */
enum StreamDelta:
  case TextDelta(text: String)
  case InputJsonDelta(partialJson: String)
  case ThinkingDelta(thinking: String)
  case Unknown(envelope: UnknownEnvelope)

object StreamDelta:
  given JsonDecoder[StreamDelta] = DeriveJsonDecoder.gen[StreamDelta]
  given JsonEncoder[StreamDelta] = DeriveJsonEncoder.gen[StreamDelta]

/** Task status for task notifications */
enum TaskStatus:
  case Completed
  case Failed
  case Stopped
  case Custom(value: String)

  def toRaw: String = this match
    case Completed => "completed"
    case Failed    => "failed"
    case Stopped   => "stopped"
    case Custom(v) => v

object TaskStatus:
  given JsonEncoder[TaskStatus] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[TaskStatus] = StringEnumJsonCodec.decoder(fromString)

  def fromString(s: String): TaskStatus = s match
    case "completed" => Completed
    case "failed"    => Failed
    case "stopped"   => Stopped
    case other       => Custom(other)
