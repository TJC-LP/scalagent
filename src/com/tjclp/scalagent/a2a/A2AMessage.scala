package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.json.StringEnumJsonCodec
import zio.json.*
import zio.json.ast.Json

/** A2A Message - represents an exchange between user and agent.
  *
  * Messages contain parts (text, files, structured data) and metadata for routing and context.
  *
  * @param role
  *   Who sent the message (user or agent)
  * @param parts
  *   Content parts of the message
  * @param messageId
  *   Unique message identifier
  * @param contextId
  *   Context/conversation identifier
  * @param taskId
  *   Associated task identifier
  * @param referenceTaskIds
  *   Related task references
  * @param metadata
  *   Extension-specific metadata
  * @param extensions
  *   Active extension URIs
  */
final case class A2AMessage(
    role: A2ARole,
    parts: List[Part],
    messageId: Option[MessageId] = None,
    contextId: Option[ContextId] = None,
    taskId: Option[TaskId] = None,
    referenceTaskIds: List[TaskId] = Nil,
    metadata: Option[Json] = None,
    extensions: List[String] = Nil
):
  /** Extract all text content from this message */
  def text: String = parts.collect { case Part.Text(t) => t }.mkString("\n")

  /** Check if message has any text content */
  def hasText: Boolean = parts.exists(_.isInstanceOf[Part.Text])

object A2AMessage:
  given JsonEncoder[A2AMessage] = DeriveJsonEncoder.gen[A2AMessage]
  given JsonDecoder[A2AMessage] = DeriveJsonDecoder.gen[A2AMessage]

  /** Create a user message with text content */
  def userText(text: String, contextId: Option[ContextId] = None): A2AMessage =
    A2AMessage(
      role = A2ARole.User,
      parts = List(Part.Text(text)),
      messageId = Some(MessageId.generate),
      contextId = contextId
    )

  /** Create an agent message with text content */
  def agentText(text: String, contextId: Option[ContextId] = None): A2AMessage =
    A2AMessage(
      role = A2ARole.Agent,
      parts = List(Part.Text(text)),
      messageId = Some(MessageId.generate),
      contextId = contextId
    )

  /** Create a message with multiple parts */
  def multi(role: A2ARole, parts: Part*): A2AMessage =
    A2AMessage(
      role = role,
      parts = parts.toList,
      messageId = Some(MessageId.generate)
    )

/** Message sender role */
enum A2ARole:
  case User
  case Agent

object A2ARole:
  given JsonEncoder[A2ARole] = StringEnumJsonCodec.encoder {
    case A2ARole.User  => "user"
    case A2ARole.Agent => "agent"
  }

  given JsonDecoder[A2ARole] = StringEnumJsonCodec.decoderOrFail {
    case "user"  => Right(A2ARole.User)
    case "agent" => Right(A2ARole.Agent)
    case other   => Left(s"Unknown role: $other")
  }

/** Message part - discriminated union of content types */
enum Part:
  case Text(text: String)
  case File(file: FileContent, name: Option[String] = None, mimeType: Option[String] = None)
  case Data(data: Json, name: Option[String] = None, mimeType: Option[String] = None)

object Part:
  // Manual codec for discriminated union with "kind" field
  given JsonEncoder[Part] = JsonEncoder[Json].contramap { part =>
    part match
      case Text(text) =>
        Json.Obj("kind" -> Json.Str("text"), "text" -> Json.Str(text))
      case File(file, name, mimeType) =>
        var obj = Json.Obj("kind" -> Json.Str("file"), "file" -> file.toJsonAST.toOption.get)
        name.foreach(n => obj = obj.add("name", Json.Str(n)))
        mimeType.foreach(m => obj = obj.add("mimeType", Json.Str(m)))
        obj
      case Data(data, name, mimeType) =>
        var obj = Json.Obj("kind" -> Json.Str("data"), "data" -> data)
        name.foreach(n => obj = obj.add("name", Json.Str(n)))
        mimeType.foreach(m => obj = obj.add("mimeType", Json.Str(m)))
        obj
  }

  given JsonDecoder[Part] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("Part must be an object").flatMap { jsonObj =>
      val fields = jsonObj.toMap
      fields.get("kind").flatMap(_.asString).toRight("Missing 'kind' field").flatMap {
        case "text" =>
          fields.get("text").flatMap(_.asString).toRight("Missing 'text' field").map(Text(_))
        case "file" =>
          for
            fileJson <- fields.get("file").toRight("Missing 'file' field")
            file     <- fileJson.as[FileContent]
            name = fields.get("name").flatMap(_.asString)
            mimeType = fields.get("mimeType").flatMap(_.asString)
          yield File(file, name, mimeType)
        case "data" =>
          for dataJson <- fields.get("data").toRight("Missing 'data' field")
          yield Data(dataJson, fields.get("name").flatMap(_.asString), fields.get("mimeType").flatMap(_.asString))
        case other => Left(s"Unknown part kind: $other")
      }
    }
  }

/** File content - either bytes (base64) or URI reference */
enum FileContent:
  case Bytes(bytes: String) // base64-encoded
  case Uri(uri: String)

object FileContent:
  given JsonEncoder[FileContent] = JsonEncoder[Json].contramap {
    case Bytes(bytes) => Json.Obj("bytes" -> Json.Str(bytes))
    case Uri(uri)     => Json.Obj("uri" -> Json.Str(uri))
  }

  given JsonDecoder[FileContent] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("FileContent must be an object").flatMap { jsonObj =>
      val fields = jsonObj.toMap
      fields.get("bytes").flatMap(_.asString) match
        case Some(bytesVal) => Right(Bytes(bytesVal))
        case None =>
          fields.get("uri").flatMap(_.asString) match
            case Some(uriVal) => Right(Uri(uriVal))
            case None         => Left("FileContent must have either 'bytes' or 'uri'")
    }
  }

/** Artifact - output produced by an agent during task execution */
final case class Artifact(
    name: String,
    parts: List[Part],
    index: Int = 0,
    append: Boolean = false,
    lastChunk: Boolean = true,
    metadata: Option[Json] = None
)
object Artifact:
  given JsonEncoder[Artifact] = DeriveJsonEncoder.gen[Artifact]
  given JsonDecoder[Artifact] = DeriveJsonDecoder.gen[Artifact]

  /** Create a simple text artifact */
  def text(name: String, content: String): Artifact =
    Artifact(name = name, parts = List(Part.Text(content)))

  /** Create a file artifact */
  def file(name: String, uri: String, mimeType: Option[String] = None): Artifact =
    Artifact(name = name, parts = List(Part.File(FileContent.Uri(uri), Some(name), mimeType)))
