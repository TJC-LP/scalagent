package com.tjclp.scalagent.streaming

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import zio.stream.*
import com.tjclp.scalagent.config.PermissionMode
import com.tjclp.scalagent.errors.AgentError
import com.tjclp.scalagent.messages.AgentMessage

/** Raw Query interface from the SDK.
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

/** Wrapper for SDK Query that provides ZIO/ZStream interface.
  *
  * This class wraps the raw JavaScript Query object and provides:
  *   - A ZStream of AgentMessage for consuming responses
  *   - Control methods (interrupt, setPermissionMode, etc.) as ZIO effects
  *
  * @param rawQuery
  *   The underlying JavaScript Query object
  */
final class QueryStream private (rawQuery: RawQuery):

  /** Stream of agent messages from this query.
    *
    * This stream will emit messages as they arrive from the SDK, converting each raw JavaScript message to the Scala
    * ADT representation.
    */
  val messages: ZStream[Any, AgentError, AgentMessage] =
    AsyncIteratorOps
      .toZStream(rawQuery)
      .map(MessageConverter.fromRaw)
      .mapError(AgentError.fromThrowable)

  /** Interrupt the current query execution.
    *
    * This will stop the agent and cause the stream to complete.
    */
  def interrupt: Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.interrupt())

  /** Change the permission mode for this session.
    *
    * @param mode
    *   The new permission mode
    */
  def setPermissionMode(mode: PermissionMode): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.setPermissionMode(mode.toRaw))

  /** Change the model for subsequent responses.
    *
    * @param model
    *   The model to use, or None to use the default
    */
  def setModel(model: Option[String]): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.setModel(model.orUndefined))

  /** Get the list of supported slash commands.
    *
    * @return
    *   List of SlashCommand info objects
    */
  def supportedCommands: Task[List[SlashCommand]] =
    ZIO
      .fromPromiseJS(rawQuery.supportedCommands())
      .map(_.toList.map(SlashCommand.fromRaw))

  /** Get the list of supported models.
    *
    * @return
    *   List of ModelInfo objects
    */
  def supportedModels: Task[List[ModelInfo]] =
    ZIO
      .fromPromiseJS(rawQuery.supportedModels())
      .map(_.toList.map(ModelInfo.fromRaw))

  /** Set maximum thinking tokens for extended thinking.
    *
    * @param tokens
    *   Maximum tokens, or None to disable limit
    */
  def setMaxThinkingTokens(tokens: Option[Int]): Task[Unit] =
    val jsTokens: js.Any = tokens.map(_.asInstanceOf[js.Any]).getOrElse(null)
    ZIO.fromPromiseJS(rawQuery.setMaxThinkingTokens(jsTokens))

  /** Get MCP server connection status.
    *
    * @return
    *   List of MCP server status objects
    */
  def mcpServerStatus: Task[List[McpServerStatusInfo]] =
    ZIO
      .fromPromiseJS(rawQuery.mcpServerStatus())
      .map(_.toList.map(McpServerStatusInfo.fromRaw))

  /** Get account information.
    *
    * @return
    *   Account info including email, organization, etc.
    */
  def accountInfo: Task[AccountInfo] =
    ZIO
      .fromPromiseJS(rawQuery.accountInfo())
      .map(AccountInfo.fromRaw)

  /** Stream additional user input for multi-turn conversations.
    *
    * This allows adding more messages to an ongoing conversation.
    *
    * @param message
    *   The user message to add
    */
  def streamUserMessage(message: String): Task[Unit] =
    val userMsg = js.Dynamic.literal(
      role = "user",
      content = message
    )
    ZIO.fromPromiseJS(rawQuery.streamInput(userMsg))

  /** Forcefully close the query and terminate the underlying process.
    *
    * This ends the query, cleaning up all resources including pending requests, MCP transports, and the CLI subprocess.
    * After calling close(), no further messages will be received.
    */
  def close(): UIO[Unit] =
    ZIO.succeed(rawQuery.close())

  /** Reconnect an MCP server by name.
    *
    * @param serverName
    *   The name of the MCP server to reconnect
    */
  def reconnectMcpServer(serverName: String): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.reconnectMcpServer(serverName))

  /** Enable or disable an MCP server by name.
    *
    * @param serverName
    *   The name of the MCP server to toggle
    * @param enabled
    *   Whether the server should be enabled
    */
  def toggleMcpServer(serverName: String, enabled: Boolean): Task[Unit] =
    ZIO.fromPromiseJS(rawQuery.toggleMcpServer(serverName, enabled))

  /** Rewind tracked files to their state at a specific user message.
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

  /** Dynamically set the MCP servers for this session.
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

object QueryStream:

  /** Create a QueryStream from a raw SDK Query object.
    *
    * @param rawQuery
    *   The raw JavaScript Query object
    * @return
    *   A new QueryStream wrapper
    */
  def apply(rawQuery: RawQuery): QueryStream =
    new QueryStream(rawQuery)

/** Information about a slash command */
final case class SlashCommand(
    name: String,
    description: String,
    args: Option[String]
)

object SlashCommand:
  def fromRaw(obj: js.Dynamic): SlashCommand =
    SlashCommand(
      name = obj.name.asInstanceOf[String],
      description = obj.description.asInstanceOf[String],
      args = obj.args.asInstanceOf[js.UndefOr[String]].toOption
    )

/** Information about a supported model */
final case class ModelInfo(
    id: String,
    name: String,
    provider: String
)

object ModelInfo:
  def fromRaw(obj: js.Dynamic): ModelInfo =
    ModelInfo(
      id = obj.id.asInstanceOf[String],
      name = obj.name.asInstanceOf[String],
      provider = obj.provider.asInstanceOf[String]
    )

/** MCP server connection status */
final case class McpServerStatusInfo(
    name: String,
    status: String,
    serverName: Option[String],
    serverVersion: Option[String],
    error: Option[String] = None,
    scope: Option[String] = None,
    tools: Option[List[McpToolStatusInfo]] = None
)

object McpServerStatusInfo:
  def fromRaw(obj: js.Dynamic): McpServerStatusInfo =
    val serverInfo = obj.serverInfo.asInstanceOf[js.UndefOr[js.Dynamic]]
    val toolsArray = obj.tools.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]]
    McpServerStatusInfo(
      name = obj.name.asInstanceOf[String],
      status = obj.status.asInstanceOf[String],
      serverName = serverInfo.toOption.flatMap(si =>
        si.name.asInstanceOf[js.UndefOr[String]].toOption
      ),
      serverVersion = serverInfo.toOption.flatMap(si =>
        si.version.asInstanceOf[js.UndefOr[String]].toOption
      ),
      error = obj.error.asInstanceOf[js.UndefOr[String]].toOption,
      scope = obj.scope.asInstanceOf[js.UndefOr[String]].toOption,
      tools = toolsArray.toOption.map(_.toList.map(McpToolStatusInfo.fromRaw))
    )

/** MCP tool status information */
final case class McpToolStatusInfo(
    name: String,
    description: Option[String],
    readOnly: Option[Boolean] = None,
    destructive: Option[Boolean] = None,
    openWorld: Option[Boolean] = None
)

object McpToolStatusInfo:
  def fromRaw(obj: js.Dynamic): McpToolStatusInfo =
    val annotations = obj.annotations.asInstanceOf[js.UndefOr[js.Dynamic]]
    McpToolStatusInfo(
      name = obj.name.asInstanceOf[String],
      description = obj.description.asInstanceOf[js.UndefOr[String]].toOption,
      readOnly = annotations.toOption.flatMap(a => a.readOnly.asInstanceOf[js.UndefOr[Boolean]].toOption),
      destructive = annotations.toOption.flatMap(a => a.destructive.asInstanceOf[js.UndefOr[Boolean]].toOption),
      openWorld = annotations.toOption.flatMap(a => a.openWorld.asInstanceOf[js.UndefOr[Boolean]].toOption)
    )

/** Account information from the SDK */
final case class AccountInfo(
    email: Option[String],
    organization: Option[String],
    subscriptionType: Option[String],
    tokenSource: Option[String],
    apiKeySource: Option[String]
)

object AccountInfo:
  def fromRaw(obj: js.Dynamic): AccountInfo =
    AccountInfo(
      email = obj.email.asInstanceOf[js.UndefOr[String]].toOption,
      organization = obj.organization.asInstanceOf[js.UndefOr[String]].toOption,
      subscriptionType = obj.subscriptionType.asInstanceOf[js.UndefOr[String]].toOption,
      tokenSource = obj.tokenSource.asInstanceOf[js.UndefOr[String]].toOption,
      apiKeySource = obj.apiKeySource.asInstanceOf[js.UndefOr[String]].toOption
    )

/** Result of a rewindFiles operation */
final case class RewindFilesResult(
    canRewind: Boolean,
    error: Option[String],
    filesChanged: Option[List[String]],
    insertions: Option[Int],
    deletions: Option[Int]
)

object RewindFilesResult:
  def fromRaw(obj: js.Dynamic): RewindFilesResult =
    RewindFilesResult(
      canRewind = obj.canRewind.asInstanceOf[Boolean],
      error = obj.error.asInstanceOf[js.UndefOr[String]].toOption,
      filesChanged = obj.filesChanged.asInstanceOf[js.UndefOr[js.Array[String]]].toOption.map(_.toList),
      insertions = obj.insertions.asInstanceOf[js.UndefOr[Int]].toOption,
      deletions = obj.deletions.asInstanceOf[js.UndefOr[Int]].toOption
    )

/** Result of a setMcpServers operation */
final case class McpSetServersResult(
    added: List[String],
    removed: List[String],
    errors: Map[String, String]
)

object McpSetServersResult:
  def fromRaw(obj: js.Dynamic): McpSetServersResult =
    val errorsDict = obj.errors.asInstanceOf[js.Dictionary[String]]
    McpSetServersResult(
      added = obj.added.asInstanceOf[js.Array[String]].toList,
      removed = obj.removed.asInstanceOf[js.Array[String]].toList,
      errors = errorsDict.toMap
    )
