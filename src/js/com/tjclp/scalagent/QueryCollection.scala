package com.tjclp.scalagent

import zio.*
import zio.stream.*
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.messages.*

/** Policy controlling how streamed messages are retained while collecting a query result. */
final case class CollectionPolicy(
  retainMessages: Boolean = true,
  maxRetainedMessages: Option[Int] = None,
  includeStreamingDeltas: Boolean = true,
  stopAtResult: Boolean = false)

object CollectionPolicy:
  val Full: CollectionPolicy = CollectionPolicy()

  val NoStreamingDeltas: CollectionPolicy = CollectionPolicy(includeStreamingDeltas = false)

  val UntilResult: CollectionPolicy = CollectionPolicy(stopAtResult = true)

  val ResultOnly: CollectionPolicy = CollectionPolicy(retainMessages = false, stopAtResult = true)

  val SummaryOnly: CollectionPolicy = CollectionPolicy(retainMessages = false)

  val Disabled: CollectionPolicy = CollectionPolicy(retainMessages = false, includeStreamingDeltas = false)

  def BoundedRecent(
    limit: Int,
    includeStreamingDeltas: Boolean = true,
    stopAtResult: Boolean = false,
  ): CollectionPolicy =
    CollectionPolicy(
      maxRetainedMessages = Some(math.max(0, limit)),
      includeStreamingDeltas = includeStreamingDeltas,
      stopAtResult = stopAtResult,
    )
end CollectionPolicy

/** Warnings collected while parsing or reducing streamed SDK messages. */
final case class CollectedWarnings(messages: List[String]):
  def ++(more: Iterable[String]): CollectedWarnings =
    CollectedWarnings(messages ++ more)

object CollectedWarnings:
  val empty: CollectedWarnings = CollectedWarnings(Nil)

final case class OutcomeOnly(outcome: ResultOutcome)

final case class UsageSummary(
  totalCostUsd: Double,
  numTurns: Int,
  usage: ModelUsage,
  modelUsage: Map[String, PerModelUsage])

final case class QuerySummary(
  outcome: ResultOutcome,
  totalMessages: Int,
  retainedMessages: Int,
  warnings: CollectedWarnings)

object QueryCollector:
  type MessageSink = AgentMessage => IO[AgentError, Unit]

  val noSink: MessageSink = _ => ZIO.unit

  def collect[R](
    stream: ZStream[R, AgentError, AgentMessage],
    policy: CollectionPolicy = CollectionPolicy.Full,
    sink: MessageSink = noSink,
  ): ZIO[R, AgentError, QueryResult] =
    val collectedStream = if policy.stopAtResult then stream.takeUntil(_.isResult) else stream

    collectedStream
      .runFoldZIO(State.empty) { (state, message) =>
        for
          _ <- sink(message)
          nextState = state.record(message, policy)
        yield nextState
      }
      .map { state =>
        QueryResult(
          messages = state.retained,
          outcome = state.outcome.getOrElse(noResultOutcome),
          totalMessages = state.totalMessages,
          sawResult = state.sawResult,
          warnings = CollectedWarnings(warningMessages(state.retained)),
        )
      }
  end collect

  def semanticText(result: QueryResult): Either[AgentError, String] =
    result.outcome match
      case success: ResultOutcome.Success                       => Right(success.result)
      case error: ResultOutcome.Error if result.hasFormalResult =>
        Left(AgentError.ApiError(500, error.reason.toString, Some(error.errors.mkString("; "))))
      case _ =>
        assistantFallback(result.messages).toRight(
          AgentError.ApiError(500, "No result message received", Some("No assistant response was retained"))
        )

  def semanticTextOrFail(result: QueryResult): IO[AgentError, String] =
    ZIO.fromEither(semanticText(result))

  private def assistantFallback(messages: List[AgentMessage]): Option[String] =
    val text = messages
      .collect {
        case AgentMessage.Assistant(message, _, _, _, _, _, _, _) =>
          message.content.collect { case ContentBlock.Text(value) => value }.mkString
      }
      .filter(_.nonEmpty)
      .mkString("\n")

    Option.when(text.nonEmpty)(text)

  private def warningMessages(messages: List[AgentMessage]): List[String] =
    messages.flatMap {
      case AgentMessage.Unknown(envelope) =>
        List(s"Unknown SDK message type '${envelope.rawType}'")
      case AgentMessage.System(SystemEvent.Unknown(envelope), _, _) =>
        List(s"Unknown system event subtype '${envelope.rawSubtype.getOrElse(envelope.rawType)}'")
      case AgentMessage.Assistant(message, _, _, _, _, _, _, _) =>
        message.content.collect {
          case ContentBlock.Unknown(envelope) =>
            s"Unknown content block type '${envelope.rawType}'"
        }
      case AgentMessage.User(message, _, _, _, _, _, _, _, _, _) =>
        message.content.collect {
          case ContentBlock.Unknown(envelope) =>
            s"Unknown content block type '${envelope.rawType}'"
        }
      case AgentMessage.UserReplay(message, _, _, _) =>
        message.content.collect {
          case ContentBlock.Unknown(envelope) =>
            s"Unknown content block type '${envelope.rawType}'"
        }
      case AgentMessage.StreamEvent(event, _, _, _) =>
        val blockWarnings = event.contentBlock.collect {
          case ContentBlock.Unknown(envelope) =>
            s"Unknown content block type '${envelope.rawType}'"
        }
        val deltaWarnings = event.delta.collect {
          case StreamDelta.Unknown(envelope) =>
            s"Unknown stream delta type '${envelope.rawType}'"
        }
        blockWarnings.toList ++ deltaWarnings.toList
      case _ => Nil
    }

  private val noResultOutcome: ResultOutcome.Error =
    ResultOutcome.Error(
      reason = ErrorReason.DuringExecution,
      durationMs = 0,
      durationApiMs = 0,
      numTurns = 0,
      totalCostUsd = 0.0,
      usage = ModelUsage.empty,
      modelUsage = Map.empty,
      permissionDenials = Nil,
      errors = List("No result message received"),
    )

  private final case class State(
    retained: List[AgentMessage],
    outcome: Option[ResultOutcome],
    totalMessages: Int,
    sawResult: Boolean):
    def record(message: AgentMessage, policy: CollectionPolicy): State =
      val retainedMessages =
        if shouldRetain(message, policy) then limitMessages(retained :+ message, policy.maxRetainedMessages)
        else retained

      copy(
        retained = retainedMessages,
        outcome = outcome.orElse(message.asResult),
        totalMessages = totalMessages + 1,
        sawResult = sawResult || message.isResult,
      )

    private def shouldRetain(message: AgentMessage, policy: CollectionPolicy): Boolean =
      policy.retainMessages &&
        (policy.includeStreamingDeltas || !message.isInstanceOf[AgentMessage.StreamEvent])

    private def limitMessages(messages: List[AgentMessage], maxRetained: Option[Int]): List[AgentMessage] =
      maxRetained match
        case Some(limit) => messages.takeRight(limit)
        case None        => messages
  end State

  private object State:
    val empty: State = State(Nil, None, 0, false)
end QueryCollector
