package com.tjclp.scalagent.streaming

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio._
import zio.stream._
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
    serverVersion: Option[String]
)

object McpServerStatusInfo:
  def fromRaw(obj: js.Dynamic): McpServerStatusInfo =
    val serverInfo = obj.serverInfo.asInstanceOf[js.UndefOr[js.Dynamic]]
    McpServerStatusInfo(
      name = obj.name.asInstanceOf[String],
      status = obj.status.asInstanceOf[String],
      serverName = serverInfo.toOption.flatMap(si =>
        si.name.asInstanceOf[js.UndefOr[String]].toOption
      ),
      serverVersion = serverInfo.toOption.flatMap(si =>
        si.version.asInstanceOf[js.UndefOr[String]].toOption
      )
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
