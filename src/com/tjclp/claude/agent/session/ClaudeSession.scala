package com.tjclp.claude.agent.session

import scala.scalajs.js
import scala.scalajs.js.annotation._
import scala.scalajs.js.JSConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import zio._
import zio.stream._
import com.tjclp.claude.agent.config._
import com.tjclp.claude.agent.errors._
import com.tjclp.claude.agent.messages._
import com.tjclp.claude.agent.streaming.{AsyncIterator, AsyncIteratorOps, MessageConverter}
import com.tjclp.claude.agent.types.SessionId

/** A session-based interface for multi-turn conversations with Claude.
  *
  * This provides the V2 session API with explicit send/receive semantics, which is more ergonomic for multi-turn
  * conversations compared to the V1 generator-based approach.
  *
  * @note
  *   This wraps the unstable V2 API from the TypeScript SDK. The API may change.
  */
trait ClaudeSession:
  /** The unique session ID */
  def sessionId: SessionId

  /** Send a message to the agent and receive streaming responses.
    *
    * @param message
    *   The message to send
    * @return
    *   A stream of agent messages in response
    */
  def send(message: String): ZStream[Any, AgentError, AgentMessage]

  /** Send a message and collect all responses.
    *
    * @param message
    *   The message to send
    * @return
    *   All response messages as a list
    */
  def sendComplete(message: String): IO[AgentError, List[AgentMessage]]

  /** Send a message and get just the text response.
    *
    * Convenience method that extracts and concatenates all text content.
    */
  def ask(message: String): IO[AgentError, String]

  /** Close the session and release resources. */
  def close: IO[AgentError, Unit]

  /** Interrupt the current operation. */
  def interrupt: IO[AgentError, Unit]

object ClaudeSession:

  /** Create a new session with the given options.
    *
    * @param options
    *   Configuration options for the session
    * @return
    *   A new ClaudeSession instance
    */
  def create(options: AgentOptions = AgentOptions.default): IO[AgentError, ClaudeSession] =
    (for
      runtime <- ZIO.runtime[Any]
      session <- ZIO.fromPromiseJS {
        val rawOptions = options.toRaw.asInstanceOf[js.Dynamic]

        // Wire up hooks if any are configured
        if options.hooks.nonEmpty then
          rawOptions.hooks = options.hooksToRaw(runtime)

        // Wire up canUseTool permission handler if configured
        options.canUseToolToRaw(runtime).foreach { handler =>
          rawOptions.canUseTool = handler
        }

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
    *   The resumed ClaudeSession instance
    */
  def resume(sessionId: SessionId, options: AgentOptions = AgentOptions.default): IO[AgentError, ClaudeSession] =
    (for
      runtime <- ZIO.runtime[Any]
      session <- ZIO.fromPromiseJS {
        val rawOptions = options.toRaw.asInstanceOf[js.Dynamic]
        rawOptions.resume = sessionId.value

        // Wire up hooks if any are configured
        if options.hooks.nonEmpty then
          rawOptions.hooks = options.hooksToRaw(runtime)

        // Wire up canUseTool permission handler if configured
        options.canUseToolToRaw(runtime).foreach { handler =>
          rawOptions.canUseTool = handler
        }

        SdkSessionModule.unstable_v2_resumeSession(rawOptions)
      }
    yield ClaudeSessionLive(session.asInstanceOf[RawSession], runtime)).mapError(AgentError.fromThrowable)

/** Live implementation of ClaudeSession wrapping the JS session. */
private final class ClaudeSessionLive(
    raw: RawSession,
    runtime: Runtime[Any]
) extends ClaudeSession:

  override val sessionId: SessionId = SessionId(raw.session_id)

  override def send(message: String): ZStream[Any, AgentError, AgentMessage] =
    ZStream.unwrap {
      ZIO.attempt {
        val asyncIter = raw.send(message).asInstanceOf[AsyncIterator[js.Dynamic]]
        AsyncIteratorOps
          .toZStream(asyncIter)
          .map(MessageConverter.fromRaw)
          .mapError(AgentError.fromThrowable)
      }.mapError(AgentError.fromThrowable)
    }

  override def sendComplete(message: String): IO[AgentError, List[AgentMessage]] =
    send(message).runCollect.map(_.toList)

  override def ask(message: String): IO[AgentError, String] =
    sendComplete(message).map { messages =>
      messages.flatMap(_.text).mkString("\n")
    }

  override def close: IO[AgentError, Unit] =
    ZIO.fromPromiseJS(raw.close()).mapError(AgentError.fromThrowable)

  override def interrupt: IO[AgentError, Unit] =
    ZIO.attempt(raw.interrupt()).mapError(AgentError.fromThrowable)

/** Raw JavaScript session type */
@js.native
private trait RawSession extends js.Object:
  val session_id: String = js.native
  def send(message: String): js.Object = js.native
  def close(): js.Promise[Unit] = js.native
  def interrupt(): Unit = js.native

/** JavaScript module binding for the V2 session SDK functions.
  *
  * These are unstable APIs that may change.
  */
@js.native
@JSImport("@anthropic-ai/claude-agent-sdk", JSImport.Namespace)
private object SdkSessionModule extends js.Object:
  def unstable_v2_createSession(options: js.Dynamic): js.Promise[js.Object] = js.native
  def unstable_v2_resumeSession(options: js.Dynamic): js.Promise[js.Object] = js.native
