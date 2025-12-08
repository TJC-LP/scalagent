package com.tjclp.claude.agent.streaming

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio._
import zio.stream._
import com.tjclp.claude.agent.config.PermissionMode
import com.tjclp.claude.agent.messages.AgentMessage

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

  /** Get supported slash commands */
  def supportedCommands(): js.Promise[js.Array[js.Dynamic]] = js.native

  /** Get supported models */
  def supportedModels(): js.Promise[js.Array[js.Dynamic]] = js.native

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
  val messages: ZStream[Any, Throwable, AgentMessage] =
    AsyncIteratorOps
      .toZStream(rawQuery)
      .map(MessageConverter.fromRaw)

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
