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
  given JsonEncoder[A2AMessage] = JsonEncoder[Json].contramap { message =>
    var obj = Json.Obj(
      "messageId" -> Json.Str(message.messageId.value),
      "role"      -> message.role.toJsonAST.toOption.get,
      "parts"     -> Json.Arr(message.parts.map(_.toJsonAST.toOption.get)*),
    )
    message.contextId.foreach(id => obj = obj.add("contextId", Json.Str(id.value)))
    message.taskId.foreach(id => obj = obj.add("taskId", Json.Str(id.value)))
    if message.referenceTaskIds.nonEmpty then
      obj = obj.add("referenceTaskIds", Json.Arr(message.referenceTaskIds.map(id => Json.Str(id.value))*))
    message.metadata.foreach(metadata => obj = obj.add("metadata", metadata))
    if message.extensions.nonEmpty then obj = obj.add("extensions", Json.Arr(message.extensions.map(Json.Str(_))*))
    obj
  }

  given JsonDecoder[A2AMessage] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("Message must be an object").flatMap { obj =>
      val fields = obj.toMap
      for
        role  <- fields.get("role").toRight("Missing role").flatMap(_.as[A2ARole])
        parts <- fields
          .get("parts")
          .flatMap(_.asArray)
          .toRight("Missing parts")
          .flatMap(values =>
            values.toList.map(_.as[Part]).foldRight[Either[String, List[Part]]](Right(Nil)) {
              case (Right(part), Right(parts)) => Right(part :: parts)
              case (Left(error), _)            => Left(error)
              case (_, Left(error))            => Left(error)
            }
          )
        messageId = fields
          .get("messageId")
          .orElse(fields.get("message_id"))
          .flatMap(_.asString)
          .map(MessageId(_))
          .getOrElse(MessageId.generate)
        contextId = fields
          .get("contextId")
          .orElse(fields.get("context_id"))
          .flatMap(_.asString)
          .filter(_.nonEmpty)
          .map(ContextId(_))
        taskId = fields
          .get("taskId")
          .orElse(fields.get("task_id"))
          .flatMap(_.asString)
          .filter(_.nonEmpty)
          .map(TaskId(_))
        referenceTaskIds = fields
          .get("referenceTaskIds")
          .orElse(fields.get("reference_task_ids"))
          .flatMap(_.asArray)
          .map(_.toList.flatMap(_.asString).map(TaskId(_)))
          .getOrElse(Nil)
        metadata   = fields.get("metadata")
        extensions = fields.get("extensions").flatMap(_.asArray).map(_.toList.flatMap(_.asString)).getOrElse(Nil)
      yield A2AMessage(role, parts, messageId, contextId, taskId, referenceTaskIds, metadata, extensions)
      end for
    }
  }

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
    case A2ARole.User  => "ROLE_USER"
    case A2ARole.Agent => "ROLE_AGENT"
  }

  given JsonDecoder[A2ARole] = StringEnumJsonCodec.decoderOrFail {
    case "ROLE_USER" | "user"   => Right(A2ARole.User)
    case "ROLE_AGENT" | "agent" => Right(A2ARole.Agent)
    case other                  => Left(s"Unknown role: $other")
  }

/** Message part - discriminated union of content types */
enum Part:
  case Text(text: String, metadata: Option[Json] = None)
  case File(file: FileContent, metadata: Option[Json] = None)
  case Data(data: Json, metadata: Option[Json] = None)

object Part:
  private def optionalString(fields: Map[String, Json], names: String*): Option[String] =
    names.iterator.flatMap(name => fields.get(name).flatMap(_.asString)).nextOption()

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
        var obj = Json.Obj("text" -> Json.Str(text), "mediaType" -> Json.Str("text/plain"))
        metadata.foreach(m => obj = obj.add("metadata", m))
        obj
      case File(file, metadata) =>
        var obj = file match
          case FileContent.Bytes(bytes, name, mimeType) =>
            var fileObj = Json.Obj("raw" -> Json.Str(bytes))
            name.foreach(value => fileObj = fileObj.add("filename", Json.Str(value)))
            mimeType.foreach(value => fileObj = fileObj.add("mediaType", Json.Str(value)))
            fileObj
          case FileContent.Uri(uri, name, mimeType) =>
            var fileObj = Json.Obj("url" -> Json.Str(uri))
            name.foreach(value => fileObj = fileObj.add("filename", Json.Str(value)))
            mimeType.foreach(value => fileObj = fileObj.add("mediaType", Json.Str(value)))
            fileObj
        metadata.foreach(m => obj = obj.add("metadata", m))
        obj
      case Data(data, metadata) =>
        var obj = Json.Obj("data" -> data, "mediaType" -> Json.Str("application/json"))
        metadata.foreach(m => obj = obj.add("metadata", m))
        obj
  }

  given JsonDecoder[Part] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("Part must be an object").flatMap { jsonObj =>
      val fields   = jsonObj.toMap
      val metadata = fields.get("metadata")
      fields.get("kind").flatMap(_.asString) match
        case Some("text") =>
          fields.get("text").flatMap(_.asString).toRight("Missing 'text' field").map(Text(_, metadata))
        case Some("file") =>
          for
            fileJson <- fields.get("file").toRight("Missing 'file' field")
            file     <- mergeLegacyFileFields(fields, fileJson).as[FileContent]
          yield File(file, metadata)
        case Some("data") =>
          for dataJson <- fields.get("data").toRight("Missing 'data' field")
          yield Data(dataJson, metadata)
        case Some(other) =>
          Left(s"Unknown part kind: $other")
        case None =>
          fields.get("text").flatMap(_.asString) match
            case Some(text) => Right(Text(text, metadata))
            case None       =>
              fields.get("raw").flatMap(_.asString) match
                case Some(raw) =>
                  Right(
                    File(
                      FileContent.Bytes(
                        bytes = raw,
                        name = optionalString(fields, "filename", "name"),
                        mimeType = optionalString(fields, "mediaType", "media_type", "mimeType"),
                      ),
                      metadata,
                    )
                  )
                case None =>
                  fields.get("url").flatMap(_.asString) match
                    case Some(url) =>
                      Right(
                        File(
                          FileContent.Uri(
                            uri = url,
                            name = optionalString(fields, "filename", "name"),
                            mimeType = optionalString(fields, "mediaType", "media_type", "mimeType"),
                          ),
                          metadata,
                        )
                      )
                    case None =>
                      fields.get("data") match
                        case Some(dataJson) => Right(Data(dataJson, metadata))
                        case None           => Left("Part must contain one of text, raw, url, or data")
      end match
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
    Artifact(artifactId = A2APlatform.randomUUID(), parts = List(Part.Text(content)), name = Some(name))

  /** Create a file artifact */
  def file(
    name: String,
    uri: String,
    mimeType: Option[String] = None,
  ): Artifact =
    Artifact(
      artifactId = A2APlatform.randomUUID(),
      parts = List(Part.File(FileContent.Uri(uri, name = Some(name), mimeType = mimeType))),
      name = Some(name),
    )
