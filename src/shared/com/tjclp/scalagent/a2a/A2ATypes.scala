package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.json.{OpaqueStringJsonCodec, StringEnumJsonCodec}
import zio.json.{JsonDecoder, JsonEncoder}

/**
 * Type-safe opaque wrappers for A2A protocol identifiers.
 *
 * These provide zero-cost compile-time type safety for different ID types, preventing accidental
 * mixing of IDs (e.g., passing a TaskId where a MessageId is expected).
 */

/** UUID v4 generator. `java.util.UUID.randomUUID()` is polyfilled by
  * Scala.js (via `crypto.randomUUID()` under the hood) on the JS side,
  * and native on the JVM side — so this works for both targets without
  * platform-specific code. */
private def randomUUID(): String = java.util.UUID.randomUUID().toString

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

  given JsonEncoder[MessageId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[MessageId] = OpaqueStringJsonCodec.decoder(apply)
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

  given JsonEncoder[ContextId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[ContextId] = OpaqueStringJsonCodec.decoder(apply)
  given CanEqual[ContextId, ContextId] = CanEqual.derived

/** A2A protocol version */
object A2AProtocol:
  val Version        = "1.0"
  val JsonRpcVersion = "2.0"

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

  def toRaw: String = this match
    case JSONRPC   => "JSONRPC"
    case GRPC      => "GRPC"
    case HTTP_JSON => "HTTP+JSON"

object A2ATransport:
  given JsonEncoder[A2ATransport] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[A2ATransport] = StringEnumJsonCodec.decoderOrFail {
    case "JSONRPC"            => Right(A2ATransport.JSONRPC)
    case "GRPC"               => Right(A2ATransport.GRPC)
    case "HTTP+JSON" | "REST" => Right(A2ATransport.HTTP_JSON)
    case other                => Left(s"Unknown transport: $other")
  }

/** Standard well-known paths */
object A2APaths:
  val AgentCard = "/.well-known/agent-card.json"
