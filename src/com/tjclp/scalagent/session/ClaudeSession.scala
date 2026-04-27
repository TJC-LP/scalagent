package com.tjclp.scalagent.session

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.stream.*
import scala.util.Try
import com.tjclp.scalagent.{CollectionPolicy, QueryCollector, QueryResult}
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.errors.*
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.streaming.{AsyncGenerator, AsyncIteratorOps, CleanupFailure, MessageConverter}
import com.tjclp.scalagent.types.SessionId

/**
 * A session-based interface for multi-turn conversations with Claude.
 *
 * This provides the V2 session API with explicit send/receive semantics, which is more ergonomic for multi-turn
 * conversations compared to the V1 generator-based approach.
 *
 * The phantom type parameter `S` tracks session state at compile time:
 *   - `ClaudeSession[Open]` - active session that can send/receive messages
 *   - `ClaudeSession[Closed]` - closed session, communication methods are unavailable
 *
 * Example usage:
 * {{{
 * for
 *   session <- ClaudeSession.create()           // ClaudeSession[Open]
 *   response <- session.ask("Hello!")           // OK - session is Open
 *   closed <- session.close                     // ClaudeSession[Closed]
 *   // closed.ask("Hi") would be a compile error!
 * yield response
 * }}}
 *
 * @tparam S
 *   The session state (Open or Closed)
 * @note
 *   This wraps the unstable V2 API from the TypeScript SDK. The API may change.
 */
trait ClaudeSession[S <: SessionState]:
  /** The unique session ID (always available regardless of state) */
  def sessionId: SessionId

  /**
   * Send a message to the agent and receive streaming responses.
   *
   * @param message
   *   The message to send
   * @return
   *   A stream of agent messages in response
   */
  def send(message: String)(using S =:= Open): ZStream[Any, AgentError, AgentMessage]

  /**
   * Send a message and collect all responses.
   *
   * @param message
   *   The message to send
   * @return
   *   All response messages as a list
   */
  def sendComplete(message: String)(using S =:= Open): IO[AgentError, List[AgentMessage]]

  /** Send a message and collect a policy-driven query result. */
  def collect(
    message: String,
    collectionPolicy: CollectionPolicy = CollectionPolicy.Full,
    sink: QueryCollector.MessageSink = QueryCollector.noSink,
  )(using S =:= Open
  ): IO[AgentError, QueryResult]

  /**
   * Send a message and get just the text response.
   *
   * Convenience method that extracts and concatenates all text content.
   */
  def ask(message: String)(using S =:= Open): IO[AgentError, String]

  /** Interrupt the current operation. */
  def interrupt(using S =:= Open): IO[AgentError, Unit]

  /**
   * Close the session and release resources.
   *
   * @return
   *   A closed session (for type tracking purposes)
   */
  def close(using S =:= Open): IO[AgentError, ClaudeSession[Closed]]
end ClaudeSession

object ClaudeSession:
  /** Type alias for an open session - the common case */
  type OpenSession = ClaudeSession[Open]

  /** Type alias for a closed session */
  type ClosedSession = ClaudeSession[Closed]

  /**
   * Create a new session with the given options.
   *
   * @param options
   *   Configuration options for the session
   * @return
   *   A new open ClaudeSession instance
   */
  def create(options: AgentOptions = AgentOptions.default): IO[AgentError, ClaudeSession[Open]] =
    (for
      runtime <- ZIO.runtime[Any]
      session <- ZIO.attempt {
        val preparedOptions = AgentOptionsCompatibility.prepare(options)
        val rawOptions      = preparedOptions.toRaw.asInstanceOf[js.Dynamic]

        // Wire up hooks if any are configured
        if preparedOptions.hooks.nonEmpty then rawOptions.hooks = preparedOptions.hooksToRaw(runtime)

        // Wire up subagents with runtime hook callbacks if present
        if preparedOptions.agents.nonEmpty then rawOptions.agents = preparedOptions.agentsToRaw(runtime)

        // Wire up canUseTool permission handler if configured
        preparedOptions.canUseToolToRaw(runtime).foreach { handler => rawOptions.canUseTool = handler }

        // V2 API returns SDKSession synchronously (not a Promise)
        SdkSessionModule.unstable_v2_createSession(rawOptions)
      }
    yield ClaudeSessionLive(session.asInstanceOf[RawSession], runtime)).mapError(AgentError.fromThrowable)

  /**
   * Resume an existing session by ID.
   *
   * @param sessionId
   *   The session ID to resume
   * @param options
   *   Configuration options (optional overrides)
   * @return
   *   The resumed open ClaudeSession instance
   */
  def resume(sessionId: SessionId, options: AgentOptions = AgentOptions.default): IO[AgentError, ClaudeSession[Open]] =
    (for
      runtime <- ZIO.runtime[Any]
      session <- ZIO.attempt {
        val preparedOptions = AgentOptionsCompatibility.prepare(options)
        val rawOptions      = preparedOptions.toRaw.asInstanceOf[js.Dynamic]
        rawOptions.resume = sessionId.value

        // Wire up hooks if any are configured
        if preparedOptions.hooks.nonEmpty then rawOptions.hooks = preparedOptions.hooksToRaw(runtime)

        // Wire up subagents with runtime hook callbacks if present
        if preparedOptions.agents.nonEmpty then rawOptions.agents = preparedOptions.agentsToRaw(runtime)

        // Wire up canUseTool permission handler if configured
        preparedOptions.canUseToolToRaw(runtime).foreach { handler => rawOptions.canUseTool = handler }

        // V2 API returns SDKSession synchronously (not a Promise)
        SdkSessionModule.unstable_v2_resumeSession(sessionId.value, rawOptions)
      }
    yield ClaudeSessionLive(session.asInstanceOf[RawSession], runtime)).mapError(AgentError.fromThrowable)

  private[scalagent] def fromRaw(
    raw: RawSession,
    runtime: Runtime[Any] = Runtime.default,
  ): ClaudeSession[Open] =
    ClaudeSessionLive(raw, runtime)
end ClaudeSession

/** Live implementation of ClaudeSession wrapping the JS session. */
private final class ClaudeSessionLive(
  raw: RawSession,
  runtime: Runtime[Any])
    extends ClaudeSession[Open]:

  private final class ActiveTurn(
    val generator: AsyncGenerator[js.Dynamic, Unit, Unit]):
    var cleaned = false

  private var closed                                    = false
  private var activeTurn: Option[ActiveTurn]            = None
  private val cleanupFailuresBuffer                     = scala.collection.mutable.ListBuffer.empty[CleanupFailure]
  private lazy val closedSession: ClaudeSession[Closed] = ClosedSessionImpl(safeSessionId)

  private def safeSessionId: SessionId =
    Try(SessionId(raw.sessionId)).getOrElse(SessionId("<uninitialized-session>"))

  private def toAgentError(throwable: Throwable): AgentError =
    throwable match
      case parseError: MessageConverter.MessageParseException =>
        AgentError.MessageParseError(parseError.message, Some(parseError.raw), parseError.cause)
      case other => AgentError.fromThrowable(other)

  private def recordCleanupFailure(operation: String, throwable: Throwable): UIO[Unit] =
    ZIO.succeed {
      val message = Option(throwable.getMessage).getOrElse(throwable.toString)
      cleanupFailuresBuffer += CleanupFailure(operation, message)
    } *> ZIO.logWarning(s"ClaudeSession $operation cleanup failed: ${throwable.getMessage}")

  private def cleanupTurn(turn: ActiveTurn, operation: String): UIO[Unit] =
    ZIO.suspendSucceed {
      if turn.cleaned then ZIO.unit
      else
        turn.cleaned = true
        if activeTurn.contains(turn) then activeTurn = None
        ZIO
          .fromPromiseJS(turn.generator.`return`(js.undefined))
          .unit
          .catchAll(recordCleanupFailure(operation, _))
    }

  private def cleanupActiveTurn(operation: String): UIO[Unit] =
    activeTurn match
      case Some(turn) => cleanupTurn(turn, operation)
      case None       => ZIO.unit

  // sessionId is a getter that may throw if called before first message
  // For simplicity, we access it lazily
  override lazy val sessionId: SessionId = safeSessionId

  override def send(message: String)(using Open =:= Open): ZStream[Any, AgentError, AgentMessage] =
    ZStream.unwrap {
      ZIO.suspendSucceed {
        if closed then ZIO.fail(AgentError.SessionClosed(safeSessionId))
        else
          cleanupFailuresBuffer.clear()
          cleanupActiveTurn("send") *>
            ZIO
              .fromPromiseJS(raw.send(message))
              .as {
                val turn = new ActiveTurn(raw.stream().asInstanceOf[AsyncGenerator[js.Dynamic, Unit, Unit]])
                activeTurn = Some(turn)

                AsyncIteratorOps
                  .toZStreamWithCleanup(turn.generator, cleanupTurn(turn, "stream"))
                  .mapZIO(rawMessage => ZIO.attempt(MessageConverter.fromRaw(rawMessage)).mapError(toAgentError))
                  .mapError(AgentError.fromThrowable)
              }
              .mapError(AgentError.fromThrowable)
      }
    }

  override def sendComplete(message: String)(using Open =:= Open): IO[AgentError, List[AgentMessage]] =
    collect(message).map(_.messages)

  override def collect(
    message: String,
    collectionPolicy: CollectionPolicy,
    sink: QueryCollector.MessageSink,
  )(using Open =:= Open
  ): IO[AgentError, QueryResult] =
    for result <- QueryCollector.collect(send(message), collectionPolicy, sink)
    yield
      if cleanupFailuresBuffer.isEmpty then result
      else result.copy(warnings = result.warnings ++ cleanupFailuresBuffer.toList.map(_.description))

  override def ask(message: String)(using Open =:= Open): IO[AgentError, String] =
    collect(
      message,
      CollectionPolicy.BoundedRecent(limit = 12, includeStreamingDeltas = false, stopAtResult = true),
    ).flatMap(_.semanticTextOrFail)

  override def interrupt(using Open =:= Open): IO[AgentError, Unit] =
    ZIO.suspendSucceed {
      if closed then ZIO.unit
      else
        val maybeInterrupt = raw
          .asInstanceOf[js.Dynamic]
          .interrupt
          .asInstanceOf[js.UndefOr[js.Function0[js.Any]]]
          .toOption

        val interruptEffect = maybeInterrupt match
          case Some(interruptFn) =>
            ZIO.fromPromiseJS(js.Promise.resolve(interruptFn.apply())).unit
          case None => ZIO.unit

        interruptEffect.either
          .flatMap {
            case Left(error)  => cleanupActiveTurn("interrupt") *> ZIO.fail(AgentError.fromThrowable(error))
            case Right(value) => cleanupActiveTurn("interrupt").as(value)
          }
    }

  override def close(using Open =:= Open): IO[AgentError, ClaudeSession[Closed]] =
    ZIO.suspendSucceed {
      if closed then ZIO.succeed(closedSession)
      else
        closed = true
        cleanupActiveTurn("close") *>
          ZIO.attempt(raw.close()).unit.catchAll(recordCleanupFailure("close", _)) *>
          ZIO.succeed(closedSession)
    }
end ClaudeSessionLive

/**
 * Implementation for a closed session.
 *
 * All methods requiring Open state are implemented but will never be called
 * due to the evidence parameter requirement. The compiler prevents calling
 * these methods on a Closed session.
 */
private final class ClosedSessionImpl(
  override val sessionId: SessionId)
    extends ClaudeSession[Closed]:

  // These methods are unreachable - the compiler prevents calling them on Closed sessions
  // due to the `using Closed =:= Open` evidence requirement (which can never be satisfied)

  override def send(message: String)(using Closed =:= Open): ZStream[Any, AgentError, AgentMessage] =
    throw new IllegalStateException("Cannot send on a closed session")

  override def sendComplete(message: String)(using Closed =:= Open): IO[AgentError, List[AgentMessage]] =
    throw new IllegalStateException("Cannot send on a closed session")

  override def collect(
    message: String,
    collectionPolicy: CollectionPolicy,
    sink: QueryCollector.MessageSink,
  )(using Closed =:= Open
  ): IO[AgentError, QueryResult] =
    throw new IllegalStateException("Cannot send on a closed session")

  override def ask(message: String)(using Closed =:= Open): IO[AgentError, String] =
    throw new IllegalStateException("Cannot send on a closed session")

  override def interrupt(using Closed =:= Open): IO[AgentError, Unit] =
    throw new IllegalStateException("Cannot interrupt a closed session")

  override def close(using Closed =:= Open): IO[AgentError, ClaudeSession[Closed]] =
    throw new IllegalStateException("Session is already closed")
end ClosedSessionImpl

/** Raw JavaScript session type matching SDKSession interface */
@js.native
private[scalagent] trait RawSession extends js.Object:
  // sessionId is a getter - available after first message, or immediately for resumed sessions
  def sessionId: String = js.native
  // send returns Promise<void>
  def send(message: String): js.Promise[Unit] = js.native
  // stream returns AsyncGenerator<SDKMessage>
  def stream(): js.Object = js.native
  // close returns void (synchronous)
  def close(): Unit = js.native

/**
 * JavaScript module binding for the V2 session SDK functions.
 *
 * These are unstable APIs that may change.
 */
@js.native
@JSImport("@anthropic-ai/claude-agent-sdk", JSImport.Namespace)
private object SdkSessionModule extends js.Object:
  // V2 API returns SDKSession synchronously (not Promise)
  def unstable_v2_createSession(options: js.Dynamic): js.Object                    = js.native
  def unstable_v2_resumeSession(sessionId: String, options: js.Dynamic): js.Object = js.native
