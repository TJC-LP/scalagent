package com.tjclp.scalagent

import zio.*
import zio.stream.*
import scala.scalajs.js
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.errors.*
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.session.*
import com.tjclp.scalagent.types.SessionId

/** Simplified entry point for the Claude Agent SDK.
  *
  * This object provides ergonomic top-level functions for common Claude operations,
  * making it easy to get started without understanding the full service architecture.
  *
  * == Quick Start ==
  *
  * {{{
  * import com.tjclp.scalagent.Claude
  *
  * // Simple question/answer
  * val answer = Claude.ask("What is 2 + 2?")
  *
  * // Streaming query
  * Claude.query("Write a poem about Scala")
  *   .textOnly
  *   .tap(Console.printLine(_))
  *   .runDrain
  *
  * // Multi-turn conversation
  * Claude.conversation { session =>
  *   for
  *     _ <- session.send("Hello!")
  *     _ <- session.ask("What's your name?")
  *   yield ()
  * }
  * }}}
  *
  * For more control, use [[ClaudeAgent]] (service pattern) or [[ClaudeSession]] (V2 session API).
  */
object Claude:

  // ============================================================================
  // One-shot Operations
  // ============================================================================

  /** Send a query and get the complete text response.
    *
    * This is the simplest way to interact with Claude - send a prompt, get a text response.
    *
    * Example:
    * {{{
    * val answer = Claude.ask("What is the capital of France?")
    *   .flatMap(answer => Console.printLine(s"Answer: $answer"))
    * }}}
    *
    * @param prompt
    *   The user prompt to send
    * @param options
    *   Configuration options (optional)
    * @return
    *   The text response from Claude
    */
  def ask(prompt: String, options: AgentOptions = AgentOptions.default): IO[AgentError, String] =
    queryComplete(
      prompt,
      options,
      CollectionPolicy.BoundedRecent(limit = 12, includeStreamingDeltas = false, stopAtResult = true)
    ).flatMap(_.semanticTextOrFail)

  /** Send a query and stream the responses.
    *
    * Returns a stream of AgentMessage values as they arrive. Use stream combinators
    * like `.textOnly` or `.collectResult` for common patterns.
    *
    * Example:
    * {{{
    * Claude.query("Write a haiku")
    *   .textOnly
    *   .tap(text => Console.printLine(text))
    *   .runDrain
    * }}}
    *
    * @param prompt
    *   The user prompt to send
    * @param options
    *   Configuration options (optional)
    * @return
    *   A stream of agent messages
    */
  def query(prompt: String, options: AgentOptions = AgentOptions.default): ZStream[Any, AgentError, AgentMessage] =
    ClaudeAgent.query(prompt, options).provideLayer(ClaudeAgent.live)

  /** Send a query and collect the complete result.
    *
    * Returns a QueryResult containing all messages and the final outcome.
    *
    * Example:
    * {{{
    * Claude.queryComplete("Analyze this code")
    *   .flatMap { result =>
    *     Console.printLine(s"Success: ${result.isSuccess}") *>
    *     Console.printLine(s"Cost: ${result.cost}")
    *   }
    * }}}
    *
    * @param prompt
    *   The user prompt to send
    * @param options
    *   Configuration options (optional)
    * @return
    *   A QueryResult with all messages and outcome
    */
  def queryComplete(
      prompt: String,
      options: AgentOptions = AgentOptions.default,
      collectionPolicy: CollectionPolicy = CollectionPolicy.Full,
      sink: QueryCollector.MessageSink = QueryCollector.noSink
  ): IO[AgentError, QueryResult] =
    ClaudeAgent.queryComplete(prompt, options, collectionPolicy, sink).provideLayer(ClaudeAgent.live)

  // ============================================================================
  // Session Operations
  // ============================================================================

  /** Create a new session for multi-turn conversations.
    *
    * The session is scoped and will be automatically closed when the scope exits.
    * Returns a `ClaudeSession[Open]` to ensure compile-time safety - you cannot
    * accidentally use a closed session.
    *
    * Example:
    * {{{
    * ZIO.scoped {
    *   for
    *     session <- Claude.session()
    *     _ <- session.ask("What is 2 + 2?")
    *     _ <- session.ask("Now multiply that by 3")
    *   yield ()
    * }
    * }}}
    *
    * @param options
    *   Configuration options (optional)
    * @return
    *   A scoped open ClaudeSession
    */
  def session(options: AgentOptions = AgentOptions.default): ZIO[Scope, AgentError, ClaudeSession[Open]] =
    ClaudeSession.create(options).withFinalizer(s => s.close.ignoreLogged)

  /** Run a multi-turn conversation with automatic resource management.
    *
    * This is the most ergonomic way to have multi-turn conversations with Claude.
    * The session is automatically created and closed.
    *
    * Example:
    * {{{
    * val result = Claude.conversation { session =>
    *   for
    *     answer1 <- session.ask("What is 2 + 2?")
    *     _ <- Console.printLine(s"First answer: $answer1")
    *     answer2 <- session.ask(s"Now multiply $answer1 by 3")
    *     _ <- Console.printLine(s"Second answer: $answer2")
    *   yield answer2
    * }
    * }}}
    *
    * @param options
    *   Configuration options
    * @param f
    *   The conversation function (receives an open session)
    * @return
    *   The result of the conversation
    */
  def conversation[A](options: AgentOptions = AgentOptions.default)(
      f: ClaudeSession[Open] => IO[AgentError, A]
  ): IO[AgentError, A] =
    ZIO.scoped {
      session(options).flatMap(f)
    }

  /** Run a conversation with default options.
    *
    * Convenience overload that uses default configuration.
    *
    * Example:
    * {{{
    * Claude.chat { session =>
    *   session.ask("Hello, who are you?")
    * }
    * }}}
    */
  def chat[A](f: ClaudeSession[Open] => IO[AgentError, A]): IO[AgentError, A] =
    conversation(AgentOptions.default)(f)

  // ============================================================================
  // Session History Operations
  // ============================================================================

  /** List available sessions for a project directory.
    *
    * @param dir
    *   The project directory to list sessions for
    * @param limit
    *   Maximum number of sessions to return (default: 50)
    * @param includeWorktrees
    *   When dir is inside a git repo, include sessions from all worktree paths (default: true)
    * @return
    *   A list of session info objects
    */
  def listSessions(dir: String, limit: Int = 50, includeWorktrees: Boolean = true): IO[AgentError, List[SessionInfo]] =
    val opts = js.Dynamic.literal(dir = dir, limit = limit)
    if !includeWorktrees then opts.includeWorktrees = false
    ZIO
      .fromPromiseJS(SdkModule.listSessions(opts))
      .map(_.toList.map(SessionInfo.fromRaw))
      .mapError(AgentError.fromThrowable)

  /** Get the messages from a previous session's transcript.
    *
    * @param sessionId
    *   The session ID to retrieve messages from
    * @param dir
    *   The project directory
    * @return
    *   A list of session messages
    */
  /** Rename a session.
    *
    * @param sessionId The session UUID
    * @param title New title for the session
    * @param dir Optional project directory path; when omitted, all project directories are searched
    */
  def renameSession(sessionId: SessionId, title: String, dir: Option[String] = None): IO[AgentError, Unit] =
    val opts: js.UndefOr[js.Dynamic] = dir match
      case Some(d) => js.Dynamic.literal(dir = d)
      case None    => js.undefined
    ZIO
      .fromPromiseJS(SdkModule.renameSession(sessionId.value, title, opts))
      .mapError(AgentError.fromThrowable)

  /** Tag a session. Pass None to clear the tag.
    *
    * @param sessionId The session UUID
    * @param tag Tag string, or None to clear
    * @param dir Optional project directory path
    */
  def tagSession(sessionId: SessionId, tag: Option[String], dir: Option[String] = None): IO[AgentError, Unit] =
    val opts: js.UndefOr[js.Dynamic] = dir match
      case Some(d) => js.Dynamic.literal(dir = d)
      case None    => js.undefined
    ZIO
      .fromPromiseJS(SdkModule.tagSession(sessionId.value, tag.getOrElse(null), opts))
      .mapError(AgentError.fromThrowable)

  /** Get info about a specific session.
    *
    * @param sessionId The session UUID
    * @param dir Optional project directory path
    * @return Session info if found
    */
  def getSessionInfo(sessionId: SessionId, dir: Option[String] = None): IO[AgentError, Option[SessionInfo]] =
    val opts: js.UndefOr[js.Dynamic] = dir match
      case Some(d) => js.Dynamic.literal(dir = d)
      case None    => js.undefined
    ZIO
      .fromPromiseJS(SdkModule.getSessionInfo(sessionId.value, opts))
      .map(_.toOption.map(dyn => SessionInfo.fromRaw(dyn.asInstanceOf[js.Dynamic])))
      .mapError(AgentError.fromThrowable)

  def getSessionMessages(sessionId: SessionId, dir: String): IO[AgentError, List[SessionMessage]] =
    ZIO
      .fromPromiseJS(
        SdkModule.getSessionMessages(
          sessionId.value,
          js.Dynamic.literal(dir = dir)
        )
      )
      .map(_.toList.map(SessionMessage.fromRaw))
      .mapError(AgentError.fromThrowable)

/** Session information from listSessions */
final case class SessionInfo(
    sessionId: SessionId,
    summary: String,
    lastModified: Long,
    fileSize: Long,
    customTitle: Option[String],
    firstPrompt: Option[String],
    gitBranch: Option[String],
    cwd: Option[String],
)

object SessionInfo:
  def fromRaw(obj: js.Dynamic): SessionInfo =
    SessionInfo(
      sessionId = SessionId(getString(obj, "sessionId").orElse(getString(obj, "id")).getOrElse("")),
      summary = getString(obj, "summary")
        .orElse(getString(obj, "name"))
        .orElse(getString(obj, "firstPrompt"))
        .getOrElse(""),
      lastModified = getLong(obj, "lastModified").getOrElse(0L),
      fileSize = getLong(obj, "fileSize").getOrElse(0L),
      customTitle = getString(obj, "customTitle"),
      firstPrompt = getString(obj, "firstPrompt"),
      gitBranch = getString(obj, "gitBranch"),
      cwd = getString(obj, "cwd")
    )

  private def getField(obj: js.Dynamic, field: String): Option[js.Any] =
    val value = obj.selectDynamic(field).asInstanceOf[js.Any]
    if js.isUndefined(value) || value == null then None else Some(value)

  private def getString(obj: js.Dynamic, field: String): Option[String] =
    getField(obj, field).flatMap { value =>
      if js.typeOf(value) == "string" then
        val s = value.asInstanceOf[String]
        Option.when(s.nonEmpty)(s)
      else None
    }

  private def getLong(obj: js.Dynamic, field: String): Option[Long] =
    getField(obj, field).flatMap {
      value =>
        if js.typeOf(value) == "number" then
          val n = value.asInstanceOf[Double]
          Option.when(!n.isNaN)(n.toLong)
        else if js.typeOf(value) == "string" then
          scala.util.Try(value.asInstanceOf[String].toLong).toOption
        else None
    }

/** Message from a session transcript */
final case class SessionMessage(
    messageType: String,
    uuid: String,
    sessionId: SessionId,
    message: String,
    parentToolUseId: Option[String]
)

object SessionMessage:
  def fromRaw(obj: js.Dynamic): SessionMessage =
    val rawMessage = getField(obj, "message").getOrElse(getField(obj, "content").orNull)
    val messageStr =
      if rawMessage == null then ""
      else if js.typeOf(rawMessage) == "string" then rawMessage.asInstanceOf[String]
      else js.JSON.stringify(rawMessage)
    SessionMessage(
      messageType = getString(obj, "type").orElse(getString(obj, "role")).getOrElse("assistant"),
      uuid = getString(obj, "uuid").getOrElse(""),
      sessionId = SessionId(getString(obj, "session_id").orElse(getString(obj, "sessionId")).getOrElse("")),
      message = messageStr,
      parentToolUseId = getString(obj, "parent_tool_use_id").orElse(getString(obj, "parentToolUseId"))
    )

  private def getField(obj: js.Dynamic, field: String): Option[js.Any] =
    val value = obj.selectDynamic(field).asInstanceOf[js.Any]
    if js.isUndefined(value) || value == null then None else Some(value)

  private def getString(obj: js.Dynamic, field: String): Option[String] =
    getField(obj, field).flatMap { value =>
      if js.typeOf(value) == "string" then
        val s = value.asInstanceOf[String]
        Option.when(s.nonEmpty)(s)
      else None
    }
