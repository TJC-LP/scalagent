package com.tjclp.scalagent.streaming

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import zio.stream.*
import com.tjclp.scalagent.config.PermissionMode
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.messages.AgentMessage

final case class CleanupFailure(
  operation: String,
  message: String):
  def description: String = s"$operation cleanup failed: $message"

/**
 * Raw Query interface from the SDK.
 *
 * This trait represents the SDK's Query object which extends AsyncGenerator and provides additional control methods.
 */
@js.native
trait RawQuery extends AsyncGenerator[js.Any, Unit, Unit]:
  /** Interrupt the current query execution */
  def interrupt(): js.Promise[Unit] = js.native

  /** Change the permission mode for this session */
  def setPermissionMode(mode: String): js.Promise[Unit] = js.native

  /** Change the model for subsequent responses */
  def setModel(model: js.UndefOr[String]): js.Promise[Unit] = js.native

  /** Set maximum thinking tokens */
  def setMaxThinkingTokens(tokens: js.Any): js.Promise[Unit] = js.native

  /** Set maximum thinking tokens and the thinking display mode (SDK 0.3.201) */
  def setMaxThinkingTokens(tokens: js.Any, thinkingDisplay: js.Any): js.Promise[Unit] = js.native

  /** Pin or clear a per-MCP-server permission-mode override (SDK 0.3.201) */
  def setMcpPermissionModeOverride(serverName: String, mode: js.Any): js.Promise[js.Dynamic] = js.native

  /** Re-send the initialize control request to an already-running CLI (SDK 0.3.201) */
  def reinitialize(): js.Promise[js.Dynamic] = js.native

  /** Get supported slash commands */
  def supportedCommands(): js.Promise[js.Array[js.Dynamic]] = js.native

  /** Get supported models */
  def supportedModels(): js.Promise[js.Array[js.Dynamic]] = js.native

  /** Get MCP server status */
  def mcpServerStatus(): js.Promise[js.Array[js.Dynamic]] = js.native

  /** Get account information */
  def accountInfo(): js.Promise[js.Dynamic] = js.native

  /** Stream additional input for multi-turn conversations */
  def streamInput(input: js.Any): js.Promise[Unit] = js.native

  /** Forcefully close the query and terminate the process */
  def close(): Unit = js.native

  /** Reconnect an MCP server by name */
  def reconnectMcpServer(serverName: String): js.Promise[Unit] = js.native

  /** Enable or disable an MCP server */
  def toggleMcpServer(serverName: String, enabled: Boolean): js.Promise[Unit] = js.native

  /** Rewind tracked files to a previous state */
  def rewindFiles(userMessageId: String, options: js.UndefOr[js.Dynamic]): js.Promise[js.Dynamic] = js.native

  /** Dynamically set MCP servers for this session */
  def setMcpServers(servers: js.Dictionary[js.Any]): js.Promise[js.Dynamic] = js.native

  /** Get the list of supported subagents */
  def supportedAgents(): js.Promise[js.Array[js.Dynamic]] = js.native

  /** Stop a running background task */
  def stopTask(taskId: String): js.Promise[Unit] = js.native

  /** Get the full initialization result including commands, models, account info */
  def initializationResult(): js.Promise[js.Dynamic] = js.native

  /** Apply settings mid-session (only available in streaming input mode) */
  def applyFlagSettings(settings: js.Dynamic): js.Promise[Unit] = js.native

  /** Seed the file read state cache to prevent stale-read errors after compaction */
  def seedReadState(path: String, mtime: Double): js.Promise[Unit] = js.native

  /** Get detailed context window usage breakdown */
  def getContextUsage(): js.Promise[js.Dynamic] = js.native

  /** Reload plugins, refreshing commands, agents, and MCP server status */
  def reloadPlugins(): js.Promise[js.Dynamic] = js.native
end RawQuery

/**
 * Wrapper for SDK Query that provides ZIO/ZStream interface.
 *
 * This class wraps the raw JavaScript Query object and provides:
 *   - A ZStream of AgentMessage for consuming responses
 *   - Control methods (interrupt, setPermissionMode, etc.) as ZIO effects
 *
 * @param rawQuery
 *   The underlying JavaScript Query object
 */
final class QueryStream private (rawQuery: RawQuery):

  private enum CleanupMode:
    case StreamTermination
    case Interrupt
    case Close

  private var cleanupStarted        = false
  private val cleanupFailuresBuffer = scala.collection.mutable.ListBuffer.empty[CleanupFailure]

  private def recordCleanupFailure(operation: String, throwable: Throwable): UIO[Unit] =
    ZIO.succeed {
      val message = Option(throwable.getMessage).getOrElse(throwable.toString)
      cleanupFailuresBuffer += CleanupFailure(operation, message)
    } *> ZIO.logWarning(s"QueryStream $operation cleanup failed: ${throwable.getMessage}")

  private def runCleanup(mode: CleanupMode): UIO[Unit] =
    ZIO.suspendSucceed {
      if cleanupStarted then ZIO.unit
      else
        cleanupStarted = true
        val returnEffect =
          ZIO
            .fromPromiseJS(rawQuery.`return`(js.undefined))
            .unit
            .catchAll(recordCleanupFailure("return", _))

        val closeEffect =
          mode match
            case CleanupMode.Close =>
              ZIO.attempt(rawQuery.close()).unit.catchAll(recordCleanupFailure("close", _))
            case _ => ZIO.unit

        returnEffect *> closeEffect
    }

  private def toAgentError(throwable: Throwable): AgentError =
    throwable match
      case parseError: MessageConverter.MessageParseException =>
        AgentError.MessageParseError(parseError.message, Some(parseError.raw), parseError.cause)
      case other => AgentError.fromThrowable(other)

  /**
   * Stream of agent messages from this query.
   *
   * This stream will emit messages as they arrive from the SDK, converting each raw JavaScript message to the Scala
   * ADT representation.
   */
  val messages: ZStream[Any, AgentError, AgentMessage] =
    AsyncIteratorOps
      .toZStreamWithCleanup(rawQuery, runCleanup(CleanupMode.StreamTermination))
      .mapZIO(raw => ZIO.attempt(MessageConverter.fromRaw(raw)).mapError(toAgentError))
      .mapError(AgentError.fromThrowable)

  /**
   * Interrupt the current query execution.
   *
   * This will stop the agent and cause the stream to complete.
   */
  def interrupt: Task[Unit] =
    ZIO.suspend {
      if cleanupStarted then ZIO.unit
      else
        ZIO
          .fromPromiseJS(rawQuery.interrupt())
          .either
          .flatMap {
            case Left(error)  => runCleanup(CleanupMode.Interrupt) *> ZIO.fail(error)
            case Right(value) => runCleanup(CleanupMode.Interrupt).as(value)
          }
    }

  /**
   * Change the permission mode for this session.
   *
   * @param mode
   *   The new permission mode
   */
  def setPermissionMode(mode: PermissionMode): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.setPermissionMode(mode.toRaw))

  /**
   * Change the model for subsequent responses.
   *
   * @param model
   *   The model to use, or None to use the default
   */
  def setModel(model: Option[String]): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.setModel(model.orUndefined))

  /**
   * Get the list of supported slash commands.
   *
   * @return
   *   List of SlashCommand info objects
   */
  def supportedCommands: Task[List[SlashCommand]] =
    ZIO
      .fromPromiseJS(rawQuery.supportedCommands())
      .map(_.toList.map(SlashCommand.fromRaw))

  /**
   * Get the list of supported models.
   *
   * @return
   *   List of ModelInfo objects
   */
  def supportedModels: Task[List[ModelInfo]] =
    ZIO
      .fromPromiseJS(rawQuery.supportedModels())
      .map(_.toList.map(ModelInfo.fromRaw))

  /**
   * Set maximum thinking tokens for extended thinking.
   *
   * @param tokens
   *   Maximum tokens, or None to disable limit
   * @param thinkingDisplay
   *   Thinking display mode for the rest of the session (SDK 0.3.201):
   *   `Keep` (default) preserves the display mode from session start, `Clear`
   *   resets to the API default, `Set(mode)` replaces it. Note a session
   *   started with thinking disabled has no display mode, so re-enabling
   *   thinking with `Keep` yields the API's default display.
   */
  def setMaxThinkingTokens(
    tokens: Option[Int],
    thinkingDisplay: ThinkingDisplayUpdate = ThinkingDisplayUpdate.Keep,
  ): Task[Unit] =
    val jsTokens: js.Any = tokens.map(_.asInstanceOf[js.Any]).getOrElse(null)
    thinkingDisplay match
      case ThinkingDisplayUpdate.Keep =>
        ZIO.fromPromiseJS(rawQuery.setMaxThinkingTokens(jsTokens))
      case ThinkingDisplayUpdate.Clear =>
        ZIO.fromPromiseJS(rawQuery.setMaxThinkingTokens(jsTokens, null))
      case ThinkingDisplayUpdate.Set(mode) =>
        ZIO.fromPromiseJS(rawQuery.setMaxThinkingTokens(jsTokens, mode.toRaw))

  /**
   * Pin (or clear) a per-MCP-server permission-mode override (SDK 0.3.201).
   *
   * Tighten-only: the override applies only when the session mode would
   * already auto-allow (bypassPermissions/auto), so it can never widen
   * privilege. Only available in streaming input mode.
   *
   * @param serverName
   *   The MCP server name (must match the name the server was registered under)
   * @param mode
   *   `Some(Default)` to force per-action prompts, `Some(Auto)` to route
   *   through the auto-mode classifier, or `None` to clear the override
   * @return
   *   An optional warning — set when `serverName` does not match any currently
   *   known MCP server. The override is stored regardless and applies once a
   *   server with that exact name connects.
   */
  def setMcpPermissionModeOverride(
    serverName: String,
    mode: Option[McpPermissionModeOverride],
  ): Task[Option[String]] =
    val jsMode: js.Any = mode.map(_.toRaw.asInstanceOf[js.Any]).getOrElse(null)
    ZIO
      .fromPromiseJS(rawQuery.setMcpPermissionModeOverride(serverName, jsMode))
      .map(result => result.warning.asInstanceOf[js.UndefOr[String]].toOption)

  /**
   * Re-send the `initialize` control request to an already-running CLI (SDK 0.3.201).
   *
   * Use after a transport gap (e.g. reattaching to a daemon): the CLI's
   * response redelivers any control requests the loop is still blocked on.
   * Unlike [[initializationResult]], this always sends a fresh request rather
   * than returning the cached first-connect result.
   */
  def reinitialize: Task[InitializationResult] =
    ZIO
      .fromPromiseJS(rawQuery.reinitialize())
      .map(InitializationResult.fromRaw)

  /**
   * Get MCP server connection status.
   *
   * @return
   *   List of MCP server status objects
   */
  def mcpServerStatus: Task[List[McpServerStatusInfo]] =
    ZIO
      .fromPromiseJS(rawQuery.mcpServerStatus())
      .map(_.toList.map(McpServerStatusInfo.fromRaw))

  /**
   * Get account information.
   *
   * @return
   *   Account info including email, organization, etc.
   */
  def accountInfo: Task[AccountInfo] =
    ZIO
      .fromPromiseJS(rawQuery.accountInfo())
      .map(AccountInfo.fromRaw)

  /**
   * Stream additional user input for multi-turn conversations.
   *
   * This allows adding more messages to an ongoing conversation.
   *
   * @param message
   *   The user message to add
   * @param priority
   *   Message priority: "now", "next", or "later" (optional)
   * @param shouldQuery
   *   When `Some(false)`, appends the message to the transcript without triggering an assistant turn;
   *   it merges into the next querying user message. Requires SDK 0.2.110+.
   */
  def streamUserMessage(
    message: String,
    priority: Option[MessagePriority] = None,
    shouldQuery: Option[Boolean] = None,
  ): Task[Unit] =
    val userMsg = js.Dynamic.literal(
      `type` = "user",
      message = js.Dynamic.literal(
        role = "user",
        content = js.Array(js.Dynamic.literal(`type` = "text", text = message)),
      ),
      parent_tool_use_id = null,
      session_id = "",
    )
    priority.foreach(p => userMsg.priority = p.toRaw)
    shouldQuery.foreach(b => userMsg.shouldQuery = b)
    ZIO.fromPromiseJS(rawQuery.streamInput(singleMessageStream(userMsg)))

  private def singleMessageStream(userMsg: js.Dynamic): js.Any =
    val iterator = js.Dynamic.literal()
    var emitted  = false
    iterator.next = () =>
      if !emitted then
        emitted = true
        js.Promise.resolve(
          js.Dynamic.literal(
            value = userMsg,
            done = false,
          )
        )
      else js.Promise.resolve(js.Dynamic.literal(done = true))

    val stream = js.Dynamic.literal()
    js.Dynamic.global.Reflect.set(stream, js.Symbol.asyncIterator, () => iterator)
    stream

  /**
   * Forcefully close the query and terminate the underlying process.
   *
   * This ends the query, cleaning up all resources including pending requests, MCP transports, and the CLI subprocess.
   * After calling close(), no further messages will be received.
   */
  def close(): UIO[Unit] =
    runCleanup(CleanupMode.Close)

  /** Cleanup failures observed while closing or terminating the underlying query. */
  def cleanupFailures: UIO[List[CleanupFailure]] =
    ZIO.succeed(cleanupFailuresBuffer.toList)

  /**
   * Reconnect an MCP server by name.
   *
   * @param serverName
   *   The name of the MCP server to reconnect
   */
  def reconnectMcpServer(serverName: String): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.reconnectMcpServer(serverName))

  /**
   * Enable or disable an MCP server by name.
   *
   * @param serverName
   *   The name of the MCP server to toggle
   * @param enabled
   *   Whether the server should be enabled
   */
  def toggleMcpServer(serverName: String, enabled: Boolean): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.toggleMcpServer(serverName, enabled))

  /**
   * Rewind tracked files to their state at a specific user message.
   *
   * Requires file checkpointing to be enabled via the `enableFileCheckpointing` option.
   *
   * @param userMessageId
   *   UUID of the user message to rewind to
   * @param dryRun
   *   If true, preview changes without modifying files
   * @return
   *   Result containing rewind status and file change statistics
   */
  def rewindFiles(userMessageId: String, dryRun: Boolean = false): Task[RewindFilesResult] =
    val options = if dryRun then js.Dynamic.literal(dryRun = true) else js.undefined
    ZIO
      .fromPromiseJS(rawQuery.rewindFiles(userMessageId, options))
      .map(RewindFilesResult.fromRaw)

  /**
   * Dynamically set the MCP servers for this session.
   *
   * This replaces the current set of dynamically-added MCP servers. Servers that are removed will be disconnected, and
   * new servers will be connected.
   *
   * Note: This only affects servers added dynamically via this method or the SDK. Servers configured via settings
   * files are not affected.
   *
   * @param servers
   *   Map of server name to configuration. Pass an empty map to remove all dynamic servers.
   * @return
   *   Information about which servers were added, removed, and any connection errors
   */
  def setMcpServers(servers: Map[String, js.Any]): Task[McpSetServersResult] =
    ZIO
      .fromPromiseJS(rawQuery.setMcpServers(servers.toJSDictionary))
      .map(McpSetServersResult.fromRaw)

  /**
   * Get the list of supported subagents.
   *
   * @return
   *   List of AgentInfo objects
   */
  def supportedAgents: Task[List[AgentInfo]] =
    ZIO
      .fromPromiseJS(rawQuery.supportedAgents())
      .map(_.toList.map(AgentInfo.fromRaw))

  /**
   * Stop a running background task.
   *
   * @param taskId
   *   The ID of the task to stop
   */
  def stopTask(taskId: String): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.stopTask(taskId))

  /**
   * Get the full initialization result from the SDK.
   *
   * This provides access to the complete initialization response including:
   * - Available slash commands
   * - Output style configuration
   * - Supported models
   * - Account information
   *
   * @return
   *   Full initialization result
   */
  def initializationResult: Task[InitializationResult] =
    ZIO
      .fromPromiseJS(rawQuery.initializationResult())
      .map(InitializationResult.fromRaw)

  /**
   * Apply settings mid-session, dynamically updating the active configuration.
   *
   * Equivalent to passing a `settings` object to `query()` but applies during an ongoing session.
   * Only available in streaming input mode.
   *
   * @param settings
   *   A raw JS settings object to merge into the flag settings layer
   */
  def applyFlagSettings(settings: js.Dynamic): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.applyFlagSettings(settings))

  /**
   * Seed the file read state cache. Call after compaction to prevent
   * "file not read yet" errors for files previously accessed.
   */
  def seedReadState(path: String, mtime: Double): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.seedReadState(path, mtime))

  /**
   * Get detailed context window usage breakdown including token counts
   * by category (user messages, assistant, tools, system, etc.).
   */
  def getContextUsage(): Task[js.Dynamic] =
    ZIO.fromPromiseJS(rawQuery.getContextUsage())

  /**
   * Reload plugins, refreshing available commands, agents, plugins,
   * and MCP server status.
   */
  def reloadPlugins(): Task[js.Dynamic] =
    ZIO.fromPromiseJS(rawQuery.reloadPlugins())
end QueryStream

object QueryStream:

  /**
   * Create a QueryStream from a raw SDK Query object.
   *
   * @param rawQuery
   *   The raw JavaScript Query object
   * @return
   *   A new QueryStream wrapper
   */
  def apply(rawQuery: RawQuery): QueryStream =
    new QueryStream(rawQuery)

/** Priority for user messages in multi-turn streaming */
enum MessagePriority:
  case Now, Next, Later

  def toRaw: String = this match
    case Now   => "now"
    case Next  => "next"
    case Later => "later"

/** Thinking display mode for [[QueryStream.setMaxThinkingTokens]] (SDK 0.3.201) */
enum ThinkingDisplay:
  case Summarized, Omitted

  def toRaw: String = this match
    case Summarized => "summarized"
    case Omitted    => "omitted"

/**
 * How [[QueryStream.setMaxThinkingTokens]] updates the session's thinking
 * display mode (SDK 0.3.201): `Keep` leaves the mode from session start
 * untouched, `Clear` resets to the API default, `Set` replaces it.
 */
enum ThinkingDisplayUpdate:
  case Keep
  case Clear
  case Set(mode: ThinkingDisplay)

/**
 * Per-MCP-server permission-mode override for
 * [[QueryStream.setMcpPermissionModeOverride]] (SDK 0.3.201). Tighten-only:
 * `Default` forces per-action prompts; `Auto` routes through the auto-mode
 * classifier.
 */
enum McpPermissionModeOverride:
  case Default, Auto

  def toRaw: String = this match
    case Default => "default"
    case Auto    => "auto"

/** Information about a slash command */
final case class SlashCommand(
  name: String,
  description: String,
  args: Option[String])

object SlashCommand:
  def fromRaw(obj: js.Dynamic): SlashCommand =
    SlashCommand(
      name = obj.name.asInstanceOf[String],
      description = obj.description.asInstanceOf[String],
      args = obj.argumentHint
        .asInstanceOf[js.UndefOr[String]]
        .toOption
        .orElse(obj.args.asInstanceOf[js.UndefOr[String]].toOption),
    )

/**
 * Information about a supported model.
 *
 * @param resolvedModel
 *   Canonical wire model id this row's `value` resolves to (e.g. `sonnet` →
 *   `claude-sonnet-5`), letting hosts match a persisted explicit id against
 *   the alias row that covers it (SDK 0.3.201).
 */
final case class ModelInfo(
  value: String,
  displayName: String,
  description: String,
  supportsEffort: Option[Boolean] = None,
  supportedEffortLevels: Option[List[String]] = None,
  supportsAdaptiveThinking: Option[Boolean] = None,
  supportsFastMode: Option[Boolean] = None,
  supportsAutoMode: Option[Boolean] = None,
  resolvedModel: Option[String] = None)

object ModelInfo:
  def fromRaw(obj: js.Dynamic): ModelInfo =
    ModelInfo(
      value = obj.value.asInstanceOf[String],
      displayName = obj.displayName.asInstanceOf[String],
      description = obj.description.asInstanceOf[js.UndefOr[String]].getOrElse(""),
      supportsEffort = obj.supportsEffort.asInstanceOf[js.UndefOr[Boolean]].toOption,
      supportedEffortLevels =
        obj.supportedEffortLevels.asInstanceOf[js.UndefOr[js.Array[String]]].toOption.map(_.toList),
      supportsAdaptiveThinking = obj.supportsAdaptiveThinking.asInstanceOf[js.UndefOr[Boolean]].toOption,
      supportsFastMode = obj.supportsFastMode.asInstanceOf[js.UndefOr[Boolean]].toOption,
      supportsAutoMode = obj.supportsAutoMode.asInstanceOf[js.UndefOr[Boolean]].toOption,
      resolvedModel = obj.resolvedModel.asInstanceOf[js.UndefOr[String]].toOption,
    )

/** Information about a supported subagent */
final case class AgentInfo(
  name: String,
  description: String,
  model: Option[String])

object AgentInfo:
  def fromRaw(obj: js.Dynamic): AgentInfo =
    AgentInfo(
      name = obj.name.asInstanceOf[String],
      description = obj.description.asInstanceOf[String],
      model = obj.model.asInstanceOf[js.UndefOr[String]].toOption,
    )

/**
 * MCP server connection status.
 *
 * @param status
 *   Raw status string. As of SDK 0.3.142, this can be `"pending"` while a
 *   server is connecting in the background, `"connected"` once ready, or
 *   `"failed"` on error. Use [[connectionStatus]] for a typed read.
 */
final case class McpServerStatusInfo(
  name: String,
  status: String,
  serverName: Option[String],
  serverVersion: Option[String],
  error: Option[String] = None,
  scope: Option[String] = None,
  tools: Option[List[McpToolStatusInfo]] = None):
  /** Parse [[status]] into the typed [[McpServerStatus]] enum. */
  def connectionStatus: McpServerStatus = McpServerStatus.fromString(status)

object McpServerStatusInfo:
  def fromRaw(obj: js.Dynamic): McpServerStatusInfo =
    val serverInfo = obj.serverInfo.asInstanceOf[js.UndefOr[js.Dynamic]]
    val toolsArray = obj.tools.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]]
    McpServerStatusInfo(
      name = obj.name.asInstanceOf[String],
      status = obj.status.asInstanceOf[String],
      serverName = serverInfo.toOption.flatMap(si => si.name.asInstanceOf[js.UndefOr[String]].toOption),
      serverVersion = serverInfo.toOption.flatMap(si => si.version.asInstanceOf[js.UndefOr[String]].toOption),
      error = obj.error.asInstanceOf[js.UndefOr[String]].toOption,
      scope = obj.scope.asInstanceOf[js.UndefOr[String]].toOption,
      tools = toolsArray.toOption.map(_.toList.map(McpToolStatusInfo.fromRaw)),
    )

/**
 * MCP server connection states surfaced by [[McpServerStatusInfo.connectionStatus]].
 *
 * As of SDK 0.3.142, MCP servers connect in the background by default — `Pending`
 * means the connect is still in flight. Set `MCP_CONNECTION_NONBLOCKING=0` in the
 * subprocess env to restore the old blocking behavior, or set `alwaysLoad = true`
 * on individual [[com.tjclp.scalagent.config.McpServerConfig]] entries.
 */
enum McpServerStatus(val raw: String):
  case Pending                          extends McpServerStatus("pending")
  case Connected                        extends McpServerStatus("connected")
  case Failed                           extends McpServerStatus("failed")
  case NeedsAuth                        extends McpServerStatus("needs-auth")
  case Custom(override val raw: String) extends McpServerStatus(raw)

object McpServerStatus:
  def fromString(s: String): McpServerStatus = s match
    case "pending"    => Pending
    case "connected"  => Connected
    case "failed"     => Failed
    case "needs-auth" => NeedsAuth
    case other        => Custom(other)

/** MCP tool status information */
final case class McpToolStatusInfo(
  name: String,
  description: Option[String],
  readOnly: Option[Boolean] = None,
  destructive: Option[Boolean] = None,
  openWorld: Option[Boolean] = None)

object McpToolStatusInfo:
  def fromRaw(obj: js.Dynamic): McpToolStatusInfo =
    val annotations = obj.annotations.asInstanceOf[js.UndefOr[js.Dynamic]]
    McpToolStatusInfo(
      name = obj.name.asInstanceOf[String],
      description = obj.description.asInstanceOf[js.UndefOr[String]].toOption,
      readOnly = annotations.toOption.flatMap(a => a.readOnly.asInstanceOf[js.UndefOr[Boolean]].toOption),
      destructive = annotations.toOption.flatMap(a => a.destructive.asInstanceOf[js.UndefOr[Boolean]].toOption),
      openWorld = annotations.toOption.flatMap(a => a.openWorld.asInstanceOf[js.UndefOr[Boolean]].toOption),
    )

/** Account information from the SDK */
final case class AccountInfo(
  email: Option[String],
  organization: Option[String],
  subscriptionType: Option[String],
  tokenSource: Option[String],
  apiKeySource: Option[String],
  apiProvider: Option[String] = None)

object AccountInfo:
  def fromRaw(obj: js.Dynamic): AccountInfo =
    AccountInfo(
      email = obj.email.asInstanceOf[js.UndefOr[String]].toOption,
      organization = obj.organization.asInstanceOf[js.UndefOr[String]].toOption,
      subscriptionType = obj.subscriptionType.asInstanceOf[js.UndefOr[String]].toOption,
      tokenSource = obj.tokenSource.asInstanceOf[js.UndefOr[String]].toOption,
      apiKeySource = obj.apiKeySource.asInstanceOf[js.UndefOr[String]].toOption,
      apiProvider = obj.apiProvider.asInstanceOf[js.UndefOr[String]].toOption,
    )

/** Result of a rewindFiles operation */
final case class RewindFilesResult(
  canRewind: Boolean,
  error: Option[String],
  filesChanged: Option[List[String]],
  insertions: Option[Int],
  deletions: Option[Int])

object RewindFilesResult:
  def fromRaw(obj: js.Dynamic): RewindFilesResult =
    RewindFilesResult(
      canRewind = obj.canRewind.asInstanceOf[Boolean],
      error = obj.error.asInstanceOf[js.UndefOr[String]].toOption,
      filesChanged = obj.filesChanged.asInstanceOf[js.UndefOr[js.Array[String]]].toOption.map(_.toList),
      insertions = obj.insertions.asInstanceOf[js.UndefOr[Int]].toOption,
      deletions = obj.deletions.asInstanceOf[js.UndefOr[Int]].toOption,
    )

/** Result of a setMcpServers operation */
final case class McpSetServersResult(
  added: List[String],
  removed: List[String],
  errors: Map[String, String])

object McpSetServersResult:
  def fromRaw(obj: js.Dynamic): McpSetServersResult =
    val errorsDict = obj.errors.asInstanceOf[js.Dictionary[String]]
    McpSetServersResult(
      added = obj.added.asInstanceOf[js.Array[String]].toList,
      removed = obj.removed.asInstanceOf[js.Array[String]].toList,
      errors = errorsDict.toMap,
    )

/** Full initialization result from SDK control response */
final case class InitializationResult(
  commands: List[SlashCommand],
  outputStyle: String,
  availableOutputStyles: List[String],
  models: List[ModelInfo],
  account: AccountInfo,
  agents: List[AgentInfo] = Nil,
  fastModeState: Option[String] = None)

object InitializationResult:
  def fromRaw(obj: js.Dynamic): InitializationResult =
    InitializationResult(
      commands = obj.commands.asInstanceOf[js.Array[js.Dynamic]].toList.map(SlashCommand.fromRaw),
      outputStyle = obj.output_style.asInstanceOf[String],
      availableOutputStyles = obj.available_output_styles.asInstanceOf[js.Array[String]].toList,
      models = obj.models.asInstanceOf[js.Array[js.Dynamic]].toList.map(ModelInfo.fromRaw),
      account = AccountInfo.fromRaw(obj.account),
      agents = obj.agents
        .asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]]
        .toOption
        .map(_.toList.map(AgentInfo.fromRaw))
        .getOrElse(Nil),
      fastModeState = obj.fast_mode_state.asInstanceOf[js.UndefOr[String]].toOption,
    )
