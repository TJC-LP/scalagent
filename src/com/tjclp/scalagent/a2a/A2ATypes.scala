package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.json.{OpaqueStringJsonCodec, StringEnumJsonCodec}
import zio.json.{JsonDecoder, JsonEncoder}
import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Type-safe opaque wrappers for A2A protocol identifiers.
  *
  * These provide zero-cost compile-time type safety for different ID types, preventing accidental
  * mixing of IDs (e.g., passing a TaskId where a MessageId is expected).
  */

/** JS crypto API for UUID generation */
@js.native
@JSGlobal("crypto")
private object Crypto extends js.Object:
  def randomUUID(): String = js.native

/** Unique identifier for an A2A task */
opaque type TaskId = String
object TaskId:
  def apply(s: String): TaskId = s
  def generate: TaskId = Crypto.randomUUID()

  extension (id: TaskId)
    def value: String = id
    def isEmpty: Boolean = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[TaskId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[TaskId] = OpaqueStringJsonCodec.decoder(apply)

/** Unique identifier for an A2A message */
opaque type MessageId = String
object MessageId:
  def apply(s: String): MessageId = s
  def generate: MessageId = Crypto.randomUUID()

  extension (id: MessageId)
    def value: String = id
    def isEmpty: Boolean = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[MessageId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[MessageId] = OpaqueStringJsonCodec.decoder(apply)

/** Unique identifier for a conversation context */
opaque type ContextId = String
object ContextId:
  def apply(s: String): ContextId = s
  def generate: ContextId = Crypto.randomUUID()

  extension (id: ContextId)
    def value: String = id
    def isEmpty: Boolean = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[ContextId] = OpaqueStringJsonCodec.encoder(_.value)
  given JsonDecoder[ContextId] = OpaqueStringJsonCodec.decoder(apply)

/** A2A protocol version */
object A2AProtocol:
  val Version = "0.3.0"
  val JsonRpcVersion = "2.0"

/** Standard A2A transport types */
enum A2ATransport:
  case JSONRPC
  case REST

  def toRaw: String = this match
    case JSONRPC => "JSONRPC"
    case REST    => "REST"

object A2ATransport:
  given JsonEncoder[A2ATransport] = StringEnumJsonCodec.encoder(_.toRaw)
  given JsonDecoder[A2ATransport] = StringEnumJsonCodec.decoderOrFail {
    case "JSONRPC" => Right(A2ATransport.JSONRPC)
    case "REST"    => Right(A2ATransport.REST)
    case other     => Left(s"Unknown transport: $other")
  }

/** Standard well-known paths */
object A2APaths:
  val AgentCard = "/.well-known/agent-card.json"
