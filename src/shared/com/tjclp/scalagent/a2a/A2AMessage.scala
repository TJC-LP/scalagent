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
  def text: String = parts.collect { case Part.Text(t, _, _, _) => t }.mkString("\n")

  /** Check if message has any text content */
  def hasText: Boolean = parts.exists(_.isInstanceOf[Part.Text])

object A2AMessage:
  private def decodeNonEmptyParts(value: Json): Either[String, List[Part]] =
    value.asArray
      .toRight("parts must be an array")
      .flatMap(values =>
        values.toList.map(_.as[Part]).foldRight[Either[String, List[Part]]](Right(Nil)) {
          case (Right(part), Right(parts)) => Right(part :: parts)
          case (Left(error), _)            => Left(error)
          case (_, Left(error))            => Left(error)
        }
      )
      .flatMap {
        case Nil   => Left("parts must contain at least one part")
        case parts => Right(parts)
      }

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
        role <- fields
          .get("role")
          .toRight("Missing role")
          .flatMap(_.as[A2ARole])
          .flatMap(A2ARole.requireSpecified(_, "role"))
        parts     <- fields.get("parts").toRight("Missing parts").flatMap(decodeNonEmptyParts)
        messageId <- fields
          .get("messageId")
          .orElse(fields.get("message_id"))
          .flatMap(_.asString)
          .filter(_.nonEmpty)
          .map(MessageId(_))
          .toRight("Missing messageId")
        contextIdValue   <- A2AJson.optionalString(fields, "contextId", "context_id")
        taskIdValue      <- A2AJson.optionalString(fields, "taskId", "task_id")
        referenceTaskIds <- A2AJson
          .optionalStringList(fields, "referenceTaskIds", "reference_task_ids")
          .map(_.filter(_.nonEmpty).map(TaskId(_)))
        metadata   <- A2AJson.optionalStruct(fields, "metadata")
        extensions <- A2AJson.optionalStringList(fields, "extensions")
        contextId = contextIdValue.filter(_.nonEmpty).map(ContextId(_))
        taskId    = taskIdValue.filter(_.nonEmpty).map(TaskId(_))
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
  case Unspecified
  case User
  case Agent

  /** Lowercase value used by the JS SDK facade and accepted as a legacy JSON alias. */
  def lowerValue: String = this match
    case Unspecified => "unspecified"
    case User        => "user"
    case Agent       => "agent"

object A2ARole:
  private val specifiedJsonValues = List("ROLE_USER", "ROLE_AGENT")

  private[a2a] def specifiedValuesMessage: String =
    specifiedJsonValues.mkString(", ")

  private[a2a] def requireSpecified(role: A2ARole, field: String): Either[String, A2ARole] =
    role match
      case A2ARole.Unspecified => Left(s"$field must be one of: $specifiedValuesMessage")
      case other               => Right(other)

  given JsonEncoder[A2ARole] = StringEnumJsonCodec.encoder {
    case A2ARole.Unspecified => "ROLE_UNSPECIFIED"
    case A2ARole.User        => "ROLE_USER"
    case A2ARole.Agent       => "ROLE_AGENT"
  }

  def fromWireValue(value: String): Either[String, A2ARole] = value match
    case "ROLE_UNSPECIFIED" | "unspecified" | "unknown" => Right(A2ARole.Unspecified)
    case "ROLE_USER" | "user"                           => Right(A2ARole.User)
    case "ROLE_AGENT" | "agent"                         => Right(A2ARole.Agent)
    case other                                          => Left(s"Unknown role: $other")

  given JsonDecoder[A2ARole] = StringEnumJsonCodec.decoderOrFail(fromWireValue)
end A2ARole

/** Message part - discriminated union of content types */
enum Part:
  case Text(
    text: String,
    metadata: Option[Json] = None,
    filename: Option[String] = None,
    mediaType: Option[String] = None)
  case File(file: FileContent, metadata: Option[Json] = None)
  case Data(
    data: Json,
    metadata: Option[Json] = None,
    filename: Option[String] = None,
    mediaType: Option[String] = None)

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
      case Text(text, metadata, filename, mediaType) =>
        var obj = Json.Obj("text" -> Json.Str(text))
        filename.foreach(value => obj = obj.add("filename", Json.Str(value)))
        mediaType.foreach(value => obj = obj.add("mediaType", Json.Str(value)))
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
      case Data(data, metadata, filename, mediaType) =>
        var obj = Json.Obj("data" -> data)
        filename.foreach(value => obj = obj.add("filename", Json.Str(value)))
        mediaType.foreach(value => obj = obj.add("mediaType", Json.Str(value)))
        metadata.foreach(m => obj = obj.add("metadata", m))
        obj
  }

  given JsonDecoder[Part] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("Part must be an object").flatMap { jsonObj =>
      val fields = jsonObj.toMap
      for
        filename  <- A2AJson.optionalString(fields, "filename", "name")
        mediaType <- A2AJson.optionalString(fields, "mediaType", "media_type", "mimeType")
        metadata  <- A2AJson.optionalStruct(fields, "metadata")
        part      <-
          fields.get("kind").flatMap(_.asString) match
            case Some("text") =>
              fields
                .get("text")
                .flatMap(_.asString)
                .toRight("Missing 'text' field")
                .map(Text(_, metadata, filename, mediaType))
            case Some("file") =>
              for
                fileJson <- fields.get("file").toRight("Missing 'file' field")
                file     <- mergeLegacyFileFields(fields, fileJson).as[FileContent]
              yield File(file, metadata)
            case Some("data") =>
              for dataJson <- fields.get("data").toRight("Missing 'data' field")
              yield Data(dataJson, metadata, filename, mediaType)
            case Some(other) =>
              Left(s"Unknown part kind: $other")
            case None =>
              val contentFields = List(
                A2AJson.nonNullNamedField(fields, "text"),
                A2AJson.nonNullNamedField(fields, "raw"),
                A2AJson.nonNullNamedField(fields, "url"),
                fields.get("data").map("data" -> _),
              ).flatten
              contentFields match
                case ("text", textJson) :: Nil =>
                  textJson.asString.toRight("text must be a string").map(Text(_, metadata, filename, mediaType))
                case ("raw", rawJson) :: Nil =>
                  FileContent.decodeBase64BytesField(rawJson, "raw").map { raw =>
                    File(
                      FileContent.Bytes(
                        bytes = raw,
                        name = filename,
                        mimeType = mediaType,
                      ),
                      metadata,
                    )
                  }
                case ("url", urlJson) :: Nil =>
                  urlJson.asString.toRight("url must be a string").map { url =>
                    File(
                      FileContent.Uri(
                        uri = url,
                        name = filename,
                        mimeType = mediaType,
                      ),
                      metadata,
                    )
                  }
                case ("data", dataJson) :: Nil =>
                  Right(Data(dataJson, metadata, filename, mediaType))
                case Nil =>
                  Left("Part must contain exactly one of text, raw, url, or data")
                case _ =>
                  Left("Part must contain exactly one of text, raw, url, or data")
              end match
          end match
      yield part
      end for
    }
  }
end Part

/**
 * File content - either bytes (base64) or URI reference.
 * This keeps legacy file name/media-type metadata with the file content while the Part codec
 * emits those values as top-level A2A v1 `filename` / `mediaType` fields.
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
  private val Base64BytesPattern =
    """^(?:[A-Za-z0-9+/\-_]{4})*(?:[A-Za-z0-9+/\-_]{2}(?:==)?|[A-Za-z0-9+/\-_]{3}=?)?$""".r

  private[a2a] def decodeBase64BytesField(json: Json, field: String): Either[String, String] =
    json.asString.toRight(s"$field must be a string").flatMap { value =>
      if isBase64Bytes(value) then Right(value)
      else Left(s"$field must be base64-encoded bytes")
    }

  private def isBase64Bytes(value: String): Boolean =
    value == value.trim && Base64BytesPattern.pattern.matcher(value).matches()

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
      val fields = jsonObj.toMap
      for
        name     <- A2AJson.optionalString(fields, "name", "filename")
        mimeType <- A2AJson.optionalString(fields, "mimeType", "mediaType", "media_type")
        content  <- (A2AJson.nonNullField(fields, "bytes"), A2AJson.nonNullField(fields, "uri")) match
          case (Some(bytesJson), None) =>
            decodeBase64BytesField(bytesJson, "bytes").map(Bytes(_, name, mimeType))
          case (None, Some(uriJson)) =>
            uriJson.asString.map(Uri(_, name, mimeType)).toRight("uri must be a string")
          case (None, None) =>
            Left("FileContent must have either 'bytes' or 'uri'")
          case (Some(_), Some(_)) =>
            Left("FileContent must contain exactly one of 'bytes' or 'uri'")
      yield content
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
  private def decodeNonEmptyParts(value: Json): Either[String, List[Part]] =
    value.asArray
      .toRight("parts must be an array")
      .flatMap(values =>
        values.toList.map(_.as[Part]).foldRight[Either[String, List[Part]]](Right(Nil)) {
          case (Right(part), Right(parts)) => Right(part :: parts)
          case (Left(error), _)            => Left(error)
          case (_, Left(error))            => Left(error)
        }
      )
      .flatMap {
        case Nil   => Left("parts must contain at least one part")
        case parts => Right(parts)
      }

  given JsonEncoder[Artifact] = JsonEncoder[Json].contramap { artifact =>
    var obj = Json.Obj(
      "artifactId" -> Json.Str(artifact.artifactId),
      "parts"      -> Json.Arr(artifact.parts.map(_.toJsonAST.toOption.get)*),
    )
    artifact.name.filter(_.nonEmpty).foreach(value => obj = obj.add("name", Json.Str(value)))
    artifact.description.filter(_.nonEmpty).foreach(value => obj = obj.add("description", Json.Str(value)))
    if artifact.extensions.nonEmpty then obj = obj.add("extensions", Json.Arr(artifact.extensions.map(Json.Str(_))*))
    artifact.metadata.foreach(value => obj = obj.add("metadata", value))
    obj
  }
  given JsonDecoder[Artifact] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject.toRight("Artifact must be an object").flatMap { obj =>
      val fields = obj.toMap
      fields
        .get("parts")
        .map(decodeNonEmptyParts)
        .getOrElse(Left("Missing parts"))
        .flatMap { parts =>
          for
            artifactId <- fields
              .get("artifactId")
              .orElse(fields.get("artifact_id"))
              .flatMap(_.asString)
              .filter(_.nonEmpty)
              .toRight("Missing artifactId")
            name        <- A2AJson.optionalString(fields, "name")
            description <- A2AJson.optionalString(fields, "description")
            extensions  <- A2AJson.optionalStringList(fields, "extensions")
            metadata    <- A2AJson.optionalStruct(fields, "metadata")
          yield Artifact(
            artifactId = artifactId,
            parts = parts,
            name = name,
            description = description,
            extensions = extensions,
            metadata = metadata,
          )
        }
    }
  }

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
end Artifact
