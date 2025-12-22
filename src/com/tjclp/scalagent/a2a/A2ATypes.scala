package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import zio.json.{JsonDecoder, JsonEncoder}

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

  given JsonEncoder[TaskId] = JsonEncoder.string.asInstanceOf[JsonEncoder[TaskId]]
  given JsonDecoder[TaskId] = JsonDecoder.string.asInstanceOf[JsonDecoder[TaskId]]

/** Unique identifier for an A2A message */
opaque type MessageId = String
object MessageId:
  def apply(s: String): MessageId = s
  def generate: MessageId = Crypto.randomUUID()

  extension (id: MessageId)
    def value: String = id
    def isEmpty: Boolean = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[MessageId] = JsonEncoder.string.asInstanceOf[JsonEncoder[MessageId]]
  given JsonDecoder[MessageId] = JsonDecoder.string.asInstanceOf[JsonDecoder[MessageId]]

/** Unique identifier for a conversation context */
opaque type ContextId = String
object ContextId:
  def apply(s: String): ContextId = s
  def generate: ContextId = Crypto.randomUUID()

  extension (id: ContextId)
    def value: String = id
    def isEmpty: Boolean = (id: String).isEmpty
    def nonEmpty: Boolean = !isEmpty

  given JsonEncoder[ContextId] = JsonEncoder.string.asInstanceOf[JsonEncoder[ContextId]]
  given JsonDecoder[ContextId] = JsonDecoder.string.asInstanceOf[JsonDecoder[ContextId]]

/** A2A protocol version */
object A2AProtocol:
  val Version = "0.3.0"
  val JsonRpcVersion = "2.0"

/** Standard A2A transport types */
enum A2ATransport:
  case JSONRPC
  case REST

object A2ATransport:
  given JsonEncoder[A2ATransport] = JsonEncoder.string.contramap {
    case A2ATransport.JSONRPC => "JSONRPC"
    case A2ATransport.REST    => "REST"
  }

  given JsonDecoder[A2ATransport] = JsonDecoder.string.mapOrFail {
    case "JSONRPC" => Right(A2ATransport.JSONRPC)
    case "REST"    => Right(A2ATransport.REST)
    case other     => Left(s"Unknown transport: $other")
  }

/** Standard well-known paths */
object A2APaths:
  val AgentCard = "/.well-known/agent-card.json"
