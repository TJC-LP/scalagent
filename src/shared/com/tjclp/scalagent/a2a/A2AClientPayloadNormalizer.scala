package com.tjclp.scalagent.a2a

import zio.json.*
import zio.json.ast.Json

/** Client-side tolerance for canonical/golden A2A payloads without relaxing server request codecs. */
private[a2a] object A2AClientPayloadNormalizer:
  def normalize(json: Json): Json =
    normalizeAt(json, "$")

  def decode[A: JsonDecoder](json: Json): Either[String, A] =
    normalize(json).as[A]

  def decodeString[A: JsonDecoder](body: String): Either[String, A] =
    body.fromJson[Json].flatMap(decode[A])

  private def normalizeAt(json: Json, path: String): Json =
    json.asArray match
      case Some(values) =>
        Json.Arr(values.toList.zipWithIndex.map { case (value, index) => normalizeAt(value, s"$path[$index]") }*)
      case None =>
        json.asObject match
          case Some(obj) =>
            val normalizedFields = obj.toMap.map { case (name, value) => name -> normalizeAt(value, s"$path.$name") }
            normalizeObject(normalizedFields, path)
          case None => json

  private def normalizeObject(fields: Map[String, Json], path: String): Json =
    val withPartAliases   = normalizePartAliases(fields)
    val withSkillDefaults =
      if looksLikeAgentSkill(withPartAliases) then normalizeAgentSkill(withPartAliases)
      else withPartAliases
    val withCardDefaults =
      if looksLikeAgentCard(withSkillDefaults) then normalizeAgentCard(withSkillDefaults)
      else withSkillDefaults
    val withMessageId =
      if looksLikeMessage(withCardDefaults) && messageId(withCardDefaults).isEmpty then
        withCardDefaults + ("messageId" -> Json.Str(normalizedMessageId(path, withCardDefaults)))
      else withCardDefaults
    Json.Obj(withMessageId.toSeq*)

  private def normalizePartAliases(fields: Map[String, Json]): Map[String, Json] =
    if fields.contains("kind") || hasCanonicalPartContent(fields) then fields
    else
      fields.get("file") match
        case Some(fileJson) =>
          fileFields(fileJson).fold(fields) { nested => fields - "file" ++ filePartFields(nested) }
        case None =>
          fields.get("fileUrl") match
            case Some(fileUrlJson) =>
              fileFields(fileUrlJson).fold(fields) { nested => fields - "fileUrl" ++ fileUrlPartFields(nested) }
            case None => fields

  private def fileFields(json: Json): Option[Map[String, Json]] =
    json.asObject.map(_.toMap)

  private def filePartFields(fields: Map[String, Json]): Map[String, Json] =
    val bytes   = A2AJson.nonNullField(fields, "bytes", "raw").map("raw" -> _)
    val uri     = A2AJson.nonNullField(fields, "uri", "url").map("url" -> _)
    val content = (bytes, uri) match
      case (Some(value), None) => Map(value)
      case (None, Some(value)) => Map(value)
      case _                   => Map.empty
    content ++ copiedFileMetadata(fields)

  private def fileUrlPartFields(fields: Map[String, Json]): Map[String, Json] =
    A2AJson
      .nonNullField(fields, "url", "uri")
      .map(value => Map("url" -> value) ++ copiedFileMetadata(fields))
      .getOrElse(Map.empty)

  private def copiedFileMetadata(fields: Map[String, Json]): Map[String, Json] =
    List(
      A2AJson.nonNullField(fields, "filename", "name").map("filename" -> _),
      A2AJson.nonNullField(fields, "mediaType", "media_type", "mimeType").map("mediaType" -> _),
      fields.get("metadata").map("metadata" -> _),
    ).flatten.toMap

  private def normalizeAgentCard(fields: Map[String, Json]): Map[String, Json] =
    val name        = stringField(fields, "name").getOrElse("Agent")
    val description = stringField(fields, "description").filter(_.nonEmpty).getOrElse(name)
    val skills      = fields.get("skills") match
      case Some(Json.Arr(values)) if values.nonEmpty => Some(Json.Arr(values*))
      case _                                         =>
        Some(
          Json.Arr(
            Json.Obj(
              "id"          -> Json.Str("default"),
              "name"        -> Json.Str(s"$name default skill"),
              "description" -> Json.Str(description),
              "tags"        -> Json.Arr(Json.Str("default")),
            )
          )
        )
    fields ++ Map(
      "description"        -> fields.getOrElse("description", Json.Str(description)),
      "version"            -> fields.getOrElse("version", Json.Str("1.0.0")),
      "capabilities"       -> fields.getOrElse("capabilities", Json.Obj()),
      "defaultInputModes"  -> fields.getOrElse("defaultInputModes", Json.Arr(Json.Str("text/plain"))),
      "defaultOutputModes" -> fields.getOrElse("defaultOutputModes", Json.Arr(Json.Str("text/plain"))),
      "skills"             -> skills.get,
    )
  end normalizeAgentCard

  private def normalizeAgentSkill(fields: Map[String, Json]): Map[String, Json] =
    val name        = stringField(fields, "name").orElse(stringField(fields, "id")).getOrElse("Skill")
    val description = stringField(fields, "description").filter(_.nonEmpty).getOrElse(name)
    val tag         = stringField(fields, "id").filter(_.nonEmpty).getOrElse("default")
    fields ++ Map(
      "description" -> fields.getOrElse("description", Json.Str(description)),
      "tags"        -> fields.getOrElse("tags", Json.Arr(Json.Str(tag))),
    )

  private def looksLikeAgentCard(fields: Map[String, Json]): Boolean =
    fields.contains("name") &&
      (fields.contains("supportedInterfaces") || fields.contains("supported_interfaces") ||
        (fields.contains("capabilities") && fields.contains("version")))

  private def looksLikeAgentSkill(fields: Map[String, Json]): Boolean =
    fields.contains("id") &&
      fields.contains("name") &&
      !fields.contains("parts") &&
      !fields.contains("status") &&
      !fields.contains("supportedInterfaces") &&
      !fields.contains("supported_interfaces")

  private def looksLikeMessage(fields: Map[String, Json]): Boolean =
    fields.contains("role") && fields.contains("parts")

  private def messageId(fields: Map[String, Json]): Option[String] =
    stringField(fields, "messageId").orElse(stringField(fields, "message_id")).filter(_.nonEmpty)

  private def hasCanonicalPartContent(fields: Map[String, Json]): Boolean =
    List("text", "raw", "url", "data").exists(name => A2AJson.nonNullField(fields, name).nonEmpty)

  private def stringField(fields: Map[String, Json], name: String): Option[String] =
    fields.get(name).flatMap(_.asString)

  private def normalizedMessageId(path: String, fields: Map[String, Json]): String =
    // Sort fields by key — `Map` iteration order is unspecified (and differs
    // across JVM/Scala.js), so without this the "deterministic dedupe" property
    // below would not actually hold.
    val seed = s"$path:${Json.Obj(fields.toSeq.sortBy(_._1)*).toJson}"
    // 64-bit (two independently-seeded MurmurHash3 words) instead of a single
    // 32-bit hashCode — collisions in client-side dedup on synthesized ids were
    // otherwise likely across a few billion messages. Cross-platform (JVM +
    // Scala.js); deterministic so re-polls of identical content dedupe.
    val h1 = scala.util.hashing.MurmurHash3.stringHash(seed)
    val h2 = scala.util.hashing.MurmurHash3.stringHash(seed, 0x9e3779b9)
    f"client-normalized-$h1%08x$h2%08x"
end A2AClientPayloadNormalizer
