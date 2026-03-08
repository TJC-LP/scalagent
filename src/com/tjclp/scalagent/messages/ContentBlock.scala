package com.tjclp.scalagent.messages

import zio.json.*
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.ToolUseId

/** Content blocks that can appear in assistant and user messages */
enum ContentBlock:
  /** Text content block */
  case Text(text: String)

  /** Tool use request from assistant */
  case ToolUse(
      id: ToolUseId,
      name: ToolName,
      input: zio.json.ast.Json
  )

  /** Tool result from user (typically synthetic) */
  case ToolResult(
      toolUseId: ToolUseId,
      content: String,
      isError: Boolean = false
  )

  /** Thinking block (extended thinking) */
  case Thinking(
      thinking: String,
      signature: Option[String] = None
  )

  /** Image content */
  case Image(
      source: ImageSource,
      mediaType: String
  )

  /** Forward-compatible fallback for unknown content blocks */
  case Unknown(
      envelope: UnknownEnvelope
  )

object ContentBlock:
  given JsonDecoder[ContentBlock] = DeriveJsonDecoder.gen[ContentBlock]
  given JsonEncoder[ContentBlock] = DeriveJsonEncoder.gen[ContentBlock]

/** Image source for image content blocks */
enum ImageSource:
  case Base64(data: String, mediaType: String)
  case Url(url: String)

object ImageSource:
  given JsonDecoder[ImageSource] = DeriveJsonDecoder.gen[ImageSource]
  given JsonEncoder[ImageSource] = DeriveJsonEncoder.gen[ImageSource]
