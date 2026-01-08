package com.tjclp.scalagent.session

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.stream.*
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.errors.*
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.streaming.{AsyncIterator, AsyncIteratorOps, MessageConverter}
import com.tjclp.scalagent.types.SessionId

/** A session-based interface for multi-turn conversations with Claude.
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

  /** Send a message to the agent and receive streaming responses.
    *
    * @param message
    *   The message to send
    * @return
    *   A stream of agent messages in response
    */
  def send(message: String)(using S =:= Open): ZStream[Any, AgentError, AgentMessage]

  /** Send a message and collect all responses.
    *
    * @param message
    *   The message to send
    * @return
    *   All response messages as a list
    */
  def sendComplete(message: String)(using S =:= Open): IO[AgentError, List[AgentMessage]]

  /** Send a message and get just the text response.
    *
    * Convenience method that extracts and concatenates all text content.
    */
  def ask(message: String)(using S =:= Open): IO[AgentError, String]

  /** Interrupt the current operation. */
  def interrupt(using S =:= Open): IO[AgentError, Unit]

  /** Close the session and release resources.
    *
    * @return
    *   A closed session (for type tracking purposes)
    */
  def close(using S =:= Open): IO[AgentError, ClaudeSession[Closed]]

object ClaudeSession:
  /** Type alias for an open session - the common case */
  type OpenSession = ClaudeSession[Open]

  /** Type alias for a closed session */
  type ClosedSession = ClaudeSession[Closed]

  /** Create a new session with the given options.
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
        val rawOptions = options.toRaw.asInstanceOf[js.Dynamic]

        // Wire up hooks if any are configured
        if options.hooks.nonEmpty then
          rawOptions.hooks = options.hooksToRaw(runtime)

        // Wire up canUseTool permission handler if configured
        options.canUseToolToRaw(runtime).foreach { handler =>
          rawOptions.canUseTool = handler
        }

        // V2 API returns SDKSession synchronously (not a Promise)
        SdkSessionModule.unstable_v2_createSession(rawOptions)
      }
    yield ClaudeSessionLive(session.asInstanceOf[RawSession], runtime)).mapError(AgentError.fromThrowable)

  /** Resume an existing session by ID.
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
        val rawOptions = options.toRaw.asInstanceOf[js.Dynamic]
        rawOptions.resume = sessionId.value

        // Wire up hooks if any are configured
        if options.hooks.nonEmpty then
          rawOptions.hooks = options.hooksToRaw(runtime)

        // Wire up canUseTool permission handler if configured
        options.canUseToolToRaw(runtime).foreach { handler =>
          rawOptions.canUseTool = handler
        }

        // V2 API returns SDKSession synchronously (not a Promise)
        SdkSessionModule.unstable_v2_resumeSession(sessionId.value, rawOptions)
      }
    yield ClaudeSessionLive(session.asInstanceOf[RawSession], runtime)).mapError(AgentError.fromThrowable)

/** Live implementation of ClaudeSession wrapping the JS session. */
private final class ClaudeSessionLive(
    raw: RawSession,
    runtime: Runtime[Any]
) extends ClaudeSession[Open]:

  // sessionId is a getter that may throw if called before first message
  // For simplicity, we access it lazily
  override lazy val sessionId: SessionId = SessionId(raw.sessionId)

  override def send(message: String)(using Open =:= Open): ZStream[Any, AgentError, AgentMessage] =
    ZStream.unwrap {
      // V2 API: send() returns Promise<void>, stream() returns AsyncGenerator
      ZIO.fromPromiseJS(raw.send(message))
        .as {
          // After sending, stream the response
          val asyncIter = raw.stream().asInstanceOf[AsyncIterator[js.Dynamic]]
          AsyncIteratorOps
            .toZStream(asyncIter)
            .map(MessageConverter.fromRaw)
            .mapError(AgentError.fromThrowable)
        }
        .mapError(AgentError.fromThrowable)
    }

  override def sendComplete(message: String)(using Open =:= Open): IO[AgentError, List[AgentMessage]] =
    send(message).runCollect.map(_.toList)

  override def ask(message: String)(using Open =:= Open): IO[AgentError, String] =
    sendComplete(message).map { messages =>
      messages.flatMap(_.text).mkString("\n")
    }

  override def interrupt(using Open =:= Open): IO[AgentError, Unit] =
    ZIO.attempt(raw.interrupt()).mapError(AgentError.fromThrowable)

  override def close(using Open =:= Open): IO[AgentError, ClaudeSession[Closed]] =
    // V2 API: close() returns void (synchronous)
    ZIO.attempt(raw.close())
      .as(ClosedSessionImpl(sessionId))
      .mapError(AgentError.fromThrowable)

/** Implementation for a closed session.
  *
  * All methods requiring Open state are implemented but will never be called
  * due to the evidence parameter requirement. The compiler prevents calling
  * these methods on a Closed session.
  */
private final class ClosedSessionImpl(
    override val sessionId: SessionId
) extends ClaudeSession[Closed]:

  // These methods are unreachable - the compiler prevents calling them on Closed sessions
  // due to the `using Closed =:= Open` evidence requirement (which can never be satisfied)

  override def send(message: String)(using Closed =:= Open): ZStream[Any, AgentError, AgentMessage] =
    throw new IllegalStateException("Cannot send on a closed session")

  override def sendComplete(message: String)(using Closed =:= Open): IO[AgentError, List[AgentMessage]] =
    throw new IllegalStateException("Cannot send on a closed session")

  override def ask(message: String)(using Closed =:= Open): IO[AgentError, String] =
    throw new IllegalStateException("Cannot send on a closed session")

  override def interrupt(using Closed =:= Open): IO[AgentError, Unit] =
    throw new IllegalStateException("Cannot interrupt a closed session")

  override def close(using Closed =:= Open): IO[AgentError, ClaudeSession[Closed]] =
    throw new IllegalStateException("Session is already closed")

/** Raw JavaScript session type matching SDKSession interface */
@js.native
private trait RawSession extends js.Object:
  // sessionId is a getter - available after first message, or immediately for resumed sessions
  def sessionId: String = js.native
  // send returns Promise<void>
  def send(message: String): js.Promise[Unit] = js.native
  // stream returns AsyncGenerator<SDKMessage>
  def stream(): js.Object = js.native
  // close returns void (synchronous)
  def close(): Unit = js.native
  // interrupt is synchronous
  def interrupt(): Unit = js.native

/** JavaScript module binding for the V2 session SDK functions.
  *
  * These are unstable APIs that may change.
  */
@js.native
@JSImport("@anthropic-ai/claude-agent-sdk", JSImport.Namespace)
private object SdkSessionModule extends js.Object:
  // V2 API returns SDKSession synchronously (not Promise)
  def unstable_v2_createSession(options: js.Dynamic): js.Object = js.native
  def unstable_v2_resumeSession(sessionId: String, options: js.Dynamic): js.Object = js.native
