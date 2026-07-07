package com.tjclp.scalagent

import zio.*
import zio.stream.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.errors.*
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.session.*
import com.tjclp.scalagent.streaming.QueryStream
import com.tjclp.scalagent.types.{MessageUuid, SessionId}

/**
 * Simplified entry point for the Claude Agent SDK.
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

  /**
   * Send a query and get the complete text response.
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
      CollectionPolicy.BoundedRecent(limit = 12, includeStreamingDeltas = false, stopAtResult = true),
    ).flatMap(_.semanticTextOrFail)

  /**
   * Send a query and stream the responses.
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

  /**
   * Send a query and collect the complete result.
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
    sink: QueryCollector.MessageSink = QueryCollector.noSink,
  ): IO[AgentError, QueryResult] =
    ClaudeAgent.queryComplete(prompt, options, collectionPolicy, sink).provideLayer(ClaudeAgent.live)

  // ============================================================================
  // Session Operations
  // ============================================================================

  /**
   * Create a new session for multi-turn conversations.
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

  /**
   * Run a multi-turn conversation with automatic resource management.
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
  def conversation[A](
    options: AgentOptions = AgentOptions.default
  )(f: ClaudeSession[Open] => IO[AgentError, A]
  ): IO[AgentError, A] =
    ZIO.scoped {
      session(options).flatMap(f)
    }

  /**
   * Run a conversation with default options.
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

  /**
   * List available sessions for a project directory.
   *
   * @param dir
   *   The project directory to list sessions for
   * @param limit
   *   Maximum number of sessions to return (default: 50)
   * @param includeWorktrees
   *   When dir is inside a git repo, include sessions from all worktree paths (default: true)
   * @param includeProgrammatic
   *   Include programmatic/headless sessions (SDK entrypoints) and
   *   daemon/daemon-worker sessions (default: true). Session pickers pass
   *   false for parity with terminal `/resume`. SDK 0.3.201.
   * @return
   *   A list of session info objects
   */
  def listSessions(
    dir: String,
    limit: Int = 50,
    includeWorktrees: Boolean = true,
    includeProgrammatic: Boolean = true,
  ): IO[AgentError, List[SessionInfo]] =
    ZIO
      .fromPromiseJS(SdkModule.listSessions(listSessionsOptions(dir, limit, includeWorktrees, includeProgrammatic)))
      .map(_.toList.map(SessionInfo.fromRaw))
      .mapError(AgentError.fromThrowable)

  private[scalagent] def listSessionsOptions(
    dir: String,
    limit: Int,
    includeWorktrees: Boolean,
    includeProgrammatic: Boolean,
  ): js.Dynamic =
    val opts = js.Dynamic.literal(dir = dir, limit = limit)
    if !includeWorktrees then opts.includeWorktrees = false
    if !includeProgrammatic then opts.includeProgrammatic = false
    opts

  /**
   * Get the messages from a previous session's transcript.
   *
   * @param sessionId
   *   The session ID to retrieve messages from
   * @param dir
   *   The project directory
   * @return
   *   A list of session messages
   */
  /**
   * Rename a session.
   *
   * @param sessionId The session UUID
   * @param title New title for the session
   * @param dir Optional project directory path; when omitted, all project directories are searched
   */
  def renameSession(
    sessionId: SessionId,
    title: String,
    dir: Option[String] = None,
  ): IO[AgentError, Unit] =
    val opts: js.UndefOr[js.Dynamic] = dir match
      case Some(d) => js.Dynamic.literal(dir = d)
      case None    => js.undefined
    ZIO
      .fromPromiseJS(SdkModule.renameSession(sessionId.value, title, opts))
      .mapError(AgentError.fromThrowable)

  /**
   * Tag a session. Pass None to clear the tag.
   *
   * @param sessionId The session UUID
   * @param tag Tag string, or None to clear
   * @param dir Optional project directory path
   */
  def tagSession(
    sessionId: SessionId,
    tag: Option[String],
    dir: Option[String] = None,
  ): IO[AgentError, Unit] =
    val opts: js.UndefOr[js.Dynamic] = dir match
      case Some(d) => js.Dynamic.literal(dir = d)
      case None    => js.undefined
    ZIO
      .fromPromiseJS(SdkModule.tagSession(sessionId.value, tag.orNull, opts))
      .mapError(AgentError.fromThrowable)

  /**
   * Get info about a specific session.
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

  /**
   * Fork a session into a new branch with fresh UUIDs.
   *
   * The forked session can be resumed with [[ClaudeSession.resume]] or via the `resume` session mode.
   *
   * @param sessionId
   *   UUID of the source session to fork
   * @param upToMessageId
   *   Slice the transcript up to this message UUID (inclusive). If omitted, the full transcript is copied.
   * @param title
   *   Custom title for the fork. If omitted, derives from the original title + " (fork)".
   * @param dir
   *   Optional project directory path
   * @return
   *   The session ID of the newly forked session
   */
  def forkSession(
    sessionId: SessionId,
    upToMessageId: Option[MessageUuid] = None,
    title: Option[String] = None,
    dir: Option[String] = None,
  ): IO[AgentError, SessionId] =
    val hasOptions                     = upToMessageId.isDefined || title.isDefined || dir.isDefined
    val jsOpts: js.UndefOr[js.Dynamic] =
      if hasOptions then
        val opts = js.Dynamic.literal()
        upToMessageId.foreach(id => opts.upToMessageId = id.value)
        title.foreach(t => opts.title = t)
        dir.foreach(d => opts.dir = d)
        opts
      else js.undefined
    ZIO
      .fromPromiseJS(SdkModule.forkSession(sessionId.value, jsOpts))
      .flatMap { result =>
        val sid = result.sessionId.asInstanceOf[js.UndefOr[String]].toOption
        ZIO.fromOption(sid).mapError(_ => new RuntimeException("forkSession: missing sessionId in response"))
      }
      .map(SessionId(_))
      .mapError(AgentError.fromThrowable)
  end forkSession

  def getSessionMessages(
    sessionId: SessionId,
    dir: String,
    includeSystemMessages: Boolean = false,
  ): IO[AgentError, List[SessionMessage]] =
    val opts = js.Dynamic.literal(dir = dir)
    if includeSystemMessages then opts.includeSystemMessages = true
    ZIO
      .fromPromiseJS(
        SdkModule.getSessionMessages(
          sessionId.value,
          opts,
        )
      )
      .map(_.toList.map(SessionMessage.fromRaw))
      .mapError(AgentError.fromThrowable)

  /**
   * List subagents for a session.
   *
   * @param sessionId The session UUID
   * @param dir Optional project directory path
   * @return A list of subagent IDs
   */
  def listSubagents(sessionId: SessionId, dir: Option[String] = None): IO[AgentError, List[String]] =
    val opts: js.UndefOr[js.Dynamic] = dir match
      case Some(d) => js.Dynamic.literal(dir = d)
      case None    => js.undefined
    ZIO
      .fromPromiseJS(SdkModule.listSubagents(sessionId.value, opts))
      .map(_.toList.map(_.asInstanceOf[js.Any]).collect {
        case v if js.typeOf(v) == "string" => v.asInstanceOf[String]
      })
      .mapError(AgentError.fromThrowable)

  /**
   * Get messages from a subagent within a session.
   *
   * @param sessionId The parent session UUID
   * @param agentId The subagent ID
   * @param dir Optional project directory path
   * @return A list of session messages from the subagent
   */
  def getSubagentMessages(
    sessionId: SessionId,
    agentId: String,
    dir: Option[String] = None,
  ): IO[AgentError, List[SessionMessage]] =
    val opts: js.UndefOr[js.Dynamic] = dir match
      case Some(d) => js.Dynamic.literal(dir = d)
      case None    => js.undefined
    ZIO
      .fromPromiseJS(SdkModule.getSubagentMessages(sessionId.value, agentId, opts))
      .map(_.toList.map(SessionMessage.fromRaw))
      .mapError(AgentError.fromThrowable)

  // ============================================================================
  // Pre-warmed startup + settings inspection (SDK 0.3.x)
  // ============================================================================

  /**
   * Pre-warm the Claude Code subprocess so the first `query()` resolves
   * immediately. Returns a scoped [[WarmQueryHandle]] whose close runs when
   * the surrounding ZIO scope releases — discards the warm process if it
   * was never used.
   *
   * Available in SDK 0.3.x.
   *
   * Example:
   * {{{
   * ZIO.scoped {
   *   for
   *     warm   <- Claude.startup(AgentOptions.default)
   *     answer <- warm.ask("What is 2 + 2?")
   *   yield answer
   * }
   * }}}
   */
  def startup(
    options: AgentOptions = AgentOptions.default,
    initializeTimeoutMs: Option[Int] = None,
  ): ZIO[Scope, AgentError, WarmQueryHandle] =
    val params = js.Dynamic.literal()
    params.options = options.toRaw
    initializeTimeoutMs.foreach(t => params.initializeTimeoutMs = t)
    val acquire = ZIO
      .fromPromiseJS(SdkModule.startup(params))
      .map(raw => WarmQueryHandle(raw))
      .mapError(AgentError.fromThrowable)
    val release = (handle: WarmQueryHandle) => handle.discard
    ZIO.acquireRelease(acquire)(release)

  /**
   * Resolve the effective Claude Code settings cascade without spawning a
   * subprocess. Reports merged settings, per-key provenance, and per-source
   * raw settings (low → high precedence).
   *
   * Available in SDK 0.3.x (alpha). The result includes the policy tier
   * but does not invoke any configured `policyHelper` subprocess — see the
   * SDK docs for caveats.
   *
   * Pass `settingSources = Some(Nil)` to inspect settings in isolation. Leave
   * it as `None` to use the SDK's resolveSettings default cascade.
   */
  def resolveSettings(
    cwd: Option[String] = None,
    settingSources: Option[List[SettingSource]] = None,
    managedSettings: Option[ManagedSettings] = None,
  ): IO[AgentError, ResolvedSettings] =
    ZIO
      .fromPromiseJS(SdkModule.resolveSettings(resolveSettingsOptions(cwd, settingSources, managedSettings)))
      .map(ResolvedSettings.fromRaw)
      .mapError(AgentError.fromThrowable)

  private[scalagent] def resolveSettingsOptions(
    cwd: Option[String],
    settingSources: Option[List[SettingSource]],
    managedSettings: Option[ManagedSettings],
  ): js.UndefOr[js.Dynamic] =
    val hasOpts = cwd.isDefined || settingSources.isDefined || managedSettings.isDefined
    if hasOpts then
      val o = js.Dynamic.literal()
      cwd.foreach(c => o.cwd = c)
      settingSources.foreach(sources => o.settingSources = sources.map(_.raw).toJSArray)
      managedSettings.foreach(ms => o.managedSettings = ms.toRaw)
      o
    else js.undefined
end Claude

/**
 * Handle for a pre-warmed Claude subprocess returned by [[Claude.startup]].
 *
 * The underlying SDK guarantees one `query()` per WarmQuery — once used,
 * the handle is consumed. The ZIO scope guarantees the warm process is
 * released (via the SDK's `close()`) even if it was never used.
 */
final class WarmQueryHandle(private[scalagent] val raw: RawWarmQuery):
  private val consumedOrClosed: Ref[Boolean] =
    Unsafe.unsafe { implicit unsafe => Ref.unsafe.make(false) }

  private def markConsumedOrClosed: UIO[Boolean] =
    consumedOrClosed.modify(consumed => (!consumed, true))

  /**
   * Start the one query backed by this warmed subprocess.
   *
   * After `query()`, the SDK owns the spawned subprocess through the returned
   * query stream and the scope release will not call `close()` on this warm
   * handle again.
   */
  def query(prompt: String): ZStream[Any, AgentError, AgentMessage] =
    ZStream.fromZIO(queryRaw(prompt)).flatMap(_.messages)

  /**
   * Start the one query and return the controllable stream wrapper.
   *
   * After `queryRaw()`, the SDK owns the spawned subprocess through the
   * returned [[QueryStream]] and the scope release will not call `close()` on
   * this warm handle again.
   */
  def queryRaw(prompt: String): IO[AgentError, QueryStream] =
    markConsumedOrClosed.flatMap {
      case false =>
        ZIO.fail(AgentError.ConfigurationError("WarmQueryHandle has already been consumed or discarded"))
      case true =>
        ZIO
          .attempt(QueryStream(raw.query(prompt).asInstanceOf[com.tjclp.scalagent.streaming.RawQuery]))
          .mapError(AgentError.fromThrowable)
    }

  /** Start the one query and collect the complete result. */
  def queryComplete(
    prompt: String,
    collectionPolicy: CollectionPolicy = CollectionPolicy.Full,
    sink: QueryCollector.MessageSink = QueryCollector.noSink,
  ): IO[AgentError, QueryResult] =
    for
      stream          <- queryRaw(prompt)
      result          <- QueryCollector.collect(stream.messages, collectionPolicy, sink)
      cleanupWarnings <- stream.cleanupFailures
    yield
      if cleanupWarnings.isEmpty then result
      else result.copy(warnings = result.warnings ++ cleanupWarnings.map(_.description))

  /** Start the one query and return the semantic final answer text. */
  def ask(prompt: String): IO[AgentError, String] =
    queryComplete(
      prompt,
      CollectionPolicy.BoundedRecent(limit = 12, includeStreamingDeltas = false, stopAtResult = true),
    ).flatMap(_.semanticTextOrFail)

  /** Drop the warm subprocess without sending a prompt. */
  def discard: UIO[Unit] =
    markConsumedOrClosed.flatMap {
      case false => ZIO.unit
      case true  =>
        ZIO
          .attempt(raw.close())
          .unit
          .catchAll(t => ZIO.logWarning(s"WarmQueryHandle close failed: ${Option(t.getMessage).getOrElse(t.toString)}"))
    }
end WarmQueryHandle

/**
 * Result of [[Claude.resolveSettings]]. Mirrors the SDK's `ResolvedSettings`
 * shape — the merged effective settings, per-key provenance, and the raw
 * cascade. Structured JSON fields are exposed for normal use; [[raw]] remains
 * available as an escape hatch for SDK-specific nested fields.
 */
final case class ResolvedSettings(
  effective: Json,
  provenance: Json,
  sources: List[Json],
  raw: js.Dynamic):
  def effectiveModel: Option[String] =
    ResolvedSettings.stringField(effective, "model")

  def provenanceFor(key: String): Option[Json] =
    ResolvedSettings.field(provenance, key)

object ResolvedSettings:
  def fromRaw(obj: js.Dynamic): ResolvedSettings =
    val srcsAny = obj.sources.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]]
    val srcs    = srcsAny.toOption.fold(List.empty[Json])(_.toList.map(dyn => jsToJson(dyn.asInstanceOf[js.Any])))
    ResolvedSettings(
      effective = jsToJson(obj.effective.asInstanceOf[js.Any]),
      provenance = jsToJson(obj.provenance.asInstanceOf[js.Any]),
      sources = srcs,
      raw = obj,
    )

  private[scalagent] def field(json: Json, key: String): Option[Json] =
    json match
      case obj: Json.Obj => obj.get(key)
      case _             => None

  private[scalagent] def stringField(json: Json, key: String): Option[String] =
    field(json, key).collect { case Json.Str(value) => value }

  private def jsToJson(value: js.Any): Json =
    if value == null || js.isUndefined(value) then Json.Null
    else
      try
        js.JSON.stringify(value).asInstanceOf[js.UndefOr[String]].toOption match
          case None             => Json.Null
          case Some(jsonString) =>
            jsonString.fromJson[Json] match
              case Right(json) => json
              case Left(error) =>
                scala.Console.err.println(s"[ResolvedSettings] Failed to parse SDK JSON payload: $error")
                Json.Str(jsonString)
      catch
        case t: Throwable =>
          scala.Console.err.println(
            s"[ResolvedSettings] Failed to stringify SDK settings payload: ${Option(t.getMessage).getOrElse(t.toString)}"
          )
          Json.Null
end ResolvedSettings

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
  tag: Option[String] = None,
  createdAt: Option[Long] = None)

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
      cwd = getString(obj, "cwd"),
      tag = getString(obj, "tag"),
      createdAt = getLong(obj, "createdAt"),
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
    getField(obj, field).flatMap { value =>
      if js.typeOf(value) == "number" then
        val n = value.asInstanceOf[Double]
        Option.when(!n.isNaN)(n.toLong)
      else if js.typeOf(value) == "string" then scala.util.Try(value.asInstanceOf[String].toLong).toOption
      else None
    }
end SessionInfo

/** Message from a session transcript */
final case class SessionMessage(
  messageType: String,
  uuid: String,
  sessionId: SessionId,
  message: String,
  parentToolUseId: Option[String])

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
      parentToolUseId = getString(obj, "parent_tool_use_id").orElse(getString(obj, "parentToolUseId")),
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
end SessionMessage
