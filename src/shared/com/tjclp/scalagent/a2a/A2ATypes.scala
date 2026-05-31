package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.json.{OpaqueStringJsonCodec, StringEnumJsonCodec}
import zio.json.{JsonDecoder, JsonEncoder}
import zio.json.ast.Json

/**
 * Type-safe opaque wrappers for A2A protocol identifiers.
 *
 * These provide zero-cost compile-time type safety for different ID types, preventing accidental
 * mixing of IDs (e.g., passing a TaskId where a MessageId is expected).
 */

/**
 * UUID v4 generator. Implemented per platform to avoid linking JVM-only
 * `java.security` internals into the Scala.js bundle.
 */
private def randomUUID(): String = A2APlatform.randomUUID()

/** Unique identifier for an A2A task */
opaque type TaskId = String
object TaskId:
  def apply(s: String): TaskId = s
  def generate: TaskId         = randomUUID()

  extension (id: TaskId)
    def value: String     = id
    def isEmpty: Boolean  = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[TaskId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[TaskId] = OpaqueStringJsonCodec.decoder(apply)
  // Same-type equality for callers compiled with `-language:strictEquality`.
  given CanEqual[TaskId, TaskId] = CanEqual.derived

/** Unique identifier for an A2A message */
opaque type MessageId = String
object MessageId:
  def apply(s: String): MessageId = s
  def generate: MessageId         = randomUUID()

  extension (id: MessageId)
    def value: String     = id
    def isEmpty: Boolean  = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[MessageId]         = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[MessageId]         = OpaqueStringJsonCodec.decoder(apply)
  given CanEqual[MessageId, MessageId] = CanEqual.derived

/** Unique identifier for a conversation context */
opaque type ContextId = String
object ContextId:
  def apply(s: String): ContextId = s
  def generate: ContextId         = randomUUID()

  extension (id: ContextId)
    def value: String     = id
    def isEmpty: Boolean  = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[ContextId]         = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[ContextId]         = OpaqueStringJsonCodec.decoder(apply)
  given CanEqual[ContextId, ContextId] = CanEqual.derived

/** A2A protocol version */
object A2AProtocol:
  val Version        = "1.0"
  val JsonRpcVersion = "2.0"

  def negotiationVersion(version: String): String =
    version.trim.split("\\.", -1).toList match
      case major :: minor :: Nil if isNumeric(major) && isNumeric(minor) =>
        s"$major.$minor"
      case major :: minor :: patch :: Nil if isNumeric(major) && isNumeric(minor) && isNumeric(patch) =>
        s"$major.$minor"
      case _ =>
        version.trim

  private def isNumeric(value: String): Boolean =
    value.nonEmpty && value.forall(ch => ch >= '0' && ch <= '9')

/** Standard A2A headers */
object A2AHeader:
  val Version            = "A2A-Version"
  val Extensions         = "X-A2A-Extensions"
  val StandardExtensions = "A2A-Extensions"

/** Standard content types */
object A2AContentType:
  val Json    = "application/json"
  val A2AJson = "application/a2a+json"
  val Sse     = "text/event-stream"

/** Standard A2A transport types */
enum A2ATransport:
  case JSONRPC
  case GRPC
  case HTTP_JSON
  case Custom(value: String)

  def toRaw: String = this match
    case JSONRPC   => "JSONRPC"
    case GRPC      => "GRPC"
    case HTTP_JSON => "HTTP+JSON"
    case Custom(v) => v

object A2ATransport:
  def fromRaw(raw: String): Either[String, A2ATransport] =
    raw match
      case "JSONRPC"               => Right(A2ATransport.JSONRPC)
      case "GRPC"                  => Right(A2ATransport.GRPC)
      case "HTTP+JSON" | "REST"    => Right(A2ATransport.HTTP_JSON)
      case other if other.nonEmpty =>
        Right(A2ATransport.Custom(other))
      case _ =>
        Left("protocolBinding must be non-empty")

  given JsonEncoder[A2ATransport] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[A2ATransport] = StringEnumJsonCodec.decoderOrFail(fromRaw)

/** Standard well-known paths */
object A2APaths:
  val AgentCard = "/.well-known/agent-card.json"

private[a2a] object A2AJson:
  def field(fields: Map[String, Json], names: String*): Option[Json] =
    names.iterator.flatMap(fields.get).nextOption()

  def optionalString(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, Option[String]] =
    field(fields, (name +: aliases)*) match
      case Some(value) => value.asString.map(Some(_)).toRight(s"$name must be a string")
      case None        => Right(None)

  def optionalBoolean(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, Option[Boolean]] =
    field(fields, (name +: aliases)*) match
      case Some(value) => value.asBoolean.map(Some(_)).toRight(s"$name must be a boolean")
      case None        => Right(None)

  def optionalStruct(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, Option[Json]] =
    field(fields, (name +: aliases)*) match
      case Some(value) if value.asObject.isDefined => Right(Some(value))
      case Some(_)                                 => Left(s"$name must be an object")
      case None                                    => Right(None)

  def optionalStringList(
    fields: Map[String, Json],
    name: String,
    aliases: String*
  ): Either[String, List[String]] =
    field(fields, (name +: aliases)*) match
      case Some(value) =>
        value.asArray
          .toRight(s"$name must be an array")
          .flatMap(values =>
            values.toList.zipWithIndex.foldRight[Either[String, List[String]]](Right(Nil)) {
              case ((value, index), Right(values)) =>
                value.asString.map(_ :: values).toRight(s"$name[$index] must be a string")
              case ((_, _), Left(error)) => Left(error)
            }
          )
      case None => Right(Nil)
end A2AJson
