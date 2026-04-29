package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.json.StringEnumJsonCodec
import zio.json.*
import zio.json.ast.Json

/**
 * A2A Message - represents an exchange between user and agent.
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
  messageId: MessageId = MessageId.generate,
  contextId: Option[ContextId] = None,
  taskId: Option[TaskId] = None,
  referenceTaskIds: List[TaskId] = Nil,
  metadata: Option[Json] = None,
  extensions: List[String] = Nil):
  /** Extract all text content from this message */
  def text: String = parts.collect { case Part.Text(t, _) => t }.mkString("\n")

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
      contextId = contextId,
    )

  /** Create an agent message with text content */
  def agentText(text: String, contextId: Option[ContextId] = None): A2AMessage =
    A2AMessage(
      role = A2ARole.Agent,
      parts = List(Part.Text(text)),
      contextId = contextId,
    )

  /** Create a message with multiple parts */
  def multi(role: A2ARole, parts: Part*): A2AMessage =
    A2AMessage(
      role = role,
      parts = parts.toList,
    )
end A2AMessage

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
  case Text(text: String, metadata: Option[Json] = None)
  case File(file: FileContent, metadata: Option[Json] = None)
  case Data(data: Json, metadata: Option[Json] = None)

object Part:
  private def mergeLegacyFileFields(partFields: Map[String, Json], fileJson: Json): Json =
    fileJson.asObject match
      case Some(fileObj) =>
        val fileFieldMap = fileObj.toMap
        var merged       = fileObj
        if !fileFieldMap.contains("name") then partFields.get("name").foreach(name => merged = merged.add("name", name))
        if !fileFieldMap.contains("mimeType") then
          partFields.get("mimeType").foreach(mimeType => merged = merged.add("mimeType", mimeType))
        merged
      case None => fileJson

  // Manual codec for discriminated union with "kind" field
  given JsonEncoder[Part] = JsonEncoder[Json].contramap { part =>
    part match
      case Text(text, metadata) =>
        var obj = Json.Obj("kind" -> Json.Str("text"), "text" -> Json.Str(text))
        metadata.foreach(m => obj = obj.add("metadata", m))
        obj
      case File(file, metadata) =>
        var obj = Json.Obj("kind" -> Json.Str("file"), "file" -> file.toJsonAST.toOption.get)
        metadata.foreach(m => obj = obj.add("metadata", m))
        obj
      case Data(data, metadata) =>
        var obj = Json.Obj("kind" -> Json.Str("data"), "data" -> data)
        metadata.foreach(m => obj = obj.add("metadata", m))
        obj
  }

  given JsonDecoder[Part] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("Part must be an object").flatMap { jsonObj =>
      val fields   = jsonObj.toMap
      val metadata = fields.get("metadata")
      fields.get("kind").flatMap(_.asString).toRight("Missing 'kind' field").flatMap {
        case "text" =>
          fields.get("text").flatMap(_.asString).toRight("Missing 'text' field").map(Text(_, metadata))
        case "file" =>
          for
            fileJson <- fields.get("file").toRight("Missing 'file' field")
            file     <- mergeLegacyFileFields(fields, fileJson).as[FileContent]
          yield File(file, metadata)
        case "data" =>
          for dataJson <- fields.get("data").toRight("Missing 'data' field")
          yield Data(dataJson, metadata)
        case other => Left(s"Unknown part kind: $other")
      }
    }
  }
end Part

/**
 * File content - either bytes (base64) or URI reference.
 * Name and mimeType live on the file object per A2A spec.
 */
enum FileContent:
  case Bytes(
    bytes: String,
    name: Option[String] = None,
    mimeType: Option[String] = None)
  case Uri(
    uri: String,
    name: Option[String] = None,
    mimeType: Option[String] = None)

object FileContent:
  given JsonEncoder[FileContent] = JsonEncoder[Json].contramap {
    case Bytes(bytes, name, mimeType) =>
      var obj = Json.Obj("bytes" -> Json.Str(bytes))
      name.foreach(n => obj = obj.add("name", Json.Str(n)))
      mimeType.foreach(m => obj = obj.add("mimeType", Json.Str(m)))
      obj
    case Uri(uri, name, mimeType) =>
      var obj = Json.Obj("uri" -> Json.Str(uri))
      name.foreach(n => obj = obj.add("name", Json.Str(n)))
      mimeType.foreach(m => obj = obj.add("mimeType", Json.Str(m)))
      obj
  }

  given JsonDecoder[FileContent] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("FileContent must be an object").flatMap { jsonObj =>
      val fields   = jsonObj.toMap
      val name     = fields.get("name").flatMap(_.asString)
      val mimeType = fields.get("mimeType").flatMap(_.asString)
      fields.get("bytes").flatMap(_.asString) match
        case Some(bytesVal) => Right(Bytes(bytesVal, name, mimeType))
        case None           =>
          fields.get("uri").flatMap(_.asString) match
            case Some(uriVal) => Right(Uri(uriVal, name, mimeType))
            case None         => Left("FileContent must have either 'bytes' or 'uri'")
    }
  }
end FileContent

/** Artifact - output produced by an agent during task execution */
final case class Artifact(
  artifactId: String,
  parts: List[Part],
  name: Option[String] = None,
  description: Option[String] = None,
  extensions: List[String] = Nil,
  metadata: Option[Json] = None)
object Artifact:
  given JsonEncoder[Artifact] = DeriveJsonEncoder.gen[Artifact]
  given JsonDecoder[Artifact] = DeriveJsonDecoder.gen[Artifact]

  /** Create a simple text artifact */
  def text(name: String, content: String): Artifact =
    Artifact(artifactId = java.util.UUID.randomUUID().toString, parts = List(Part.Text(content)), name = Some(name))

  /** Create a file artifact */
  def file(
    name: String,
    uri: String,
    mimeType: Option[String] = None,
  ): Artifact =
    Artifact(
      artifactId = java.util.UUID.randomUUID().toString,
      parts = List(Part.File(FileContent.Uri(uri, name = Some(name), mimeType = mimeType))),
      name = Some(name),
    )
