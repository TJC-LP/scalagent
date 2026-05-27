package com.tjclp.scalagent.a2a

import zio.json.ast.Json
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.types.ToolUseId

private[a2a] object A2AProgress:
  val MaxTextLength: Int = 240

  final case class State(
    lastTextOnlyStatus: Option[String] = None,
    toolNamesById: Map[ToolUseId, String] = Map.empty)

  object State:
    val empty: State = State()

  def statusMessage(
    agentMessage: AgentMessage,
    contextId: ContextId,
    taskId: TaskId,
    state: State,
  ): (State, Option[A2AMessage]) =
    val mapped    = progressParts(agentMessage, state.toolNamesById)
    val nextState = state.copy(
      toolNamesById = state.toolNamesById ++ mapped.toolNamesById
    )

    if mapped.parts.isEmpty then (nextState, None)
    else
      val textOnlyKey =
        Option.when(mapped.parts.forall(_.isInstanceOf[Part.Text])) {
          mapped.parts.collect { case Part.Text(text, _) => text }.mkString("\n")
        }

      textOnlyKey match
        case Some(text) if state.lastTextOnlyStatus.contains(text) =>
          (nextState, None)
        case _ =>
          val publishedState = nextState.copy(lastTextOnlyStatus = textOnlyKey.orElse(nextState.lastTextOnlyStatus))
          val message        = A2AMessage(
            role = A2ARole.Agent,
            parts = mapped.parts,
            contextId = Some(contextId),
            taskId = Some(taskId),
          )
          (publishedState, Some(message))
  end statusMessage

  private final case class MappedProgress(
    parts: List[Part],
    toolNamesById: Map[ToolUseId, String] = Map.empty)

  private def progressParts(message: AgentMessage, knownToolNames: Map[ToolUseId, String]): MappedProgress =
    def text(value: String): MappedProgress =
      nonEmpty(value).fold(MappedProgress(Nil))(value => MappedProgress(List(Part.Text(truncate(redactText(value))))))

    message match
      case AgentMessage.TaskStarted(_, description, _, _, _, _, _, _) =>
        text(description)

      case AgentMessage.TaskProgress(_, progress, _, _, summary, _, _) =>
        text(summary.flatMap(nonEmpty).getOrElse(progress))

      case AgentMessage.ToolProgress(_, toolName, _, elapsedTimeSeconds, _, _, _) =>
        val elapsed = math.max(1L, math.round(elapsedTimeSeconds))
        text(s"Running ${toolName.raw} (${elapsed}s)")

      case AgentMessage.ToolUseSummary(summary, _, _, _) =>
        text(summary)

      case assistant: AgentMessage.Assistant =>
        assistantParts(assistant.message.content)

      case user: AgentMessage.User =>
        toolResultParts(user.message.content, knownToolNames)

      case AgentMessage.System(SystemEvent.Status(status, _), _, _) =>
        status
          .map {
            case SdkStatus.Compacting    => "Compacting context"
            case SdkStatus.Requesting    => "Requesting from model"
            case SdkStatus.Custom(value) => value
          }
          .flatMap(nonEmpty)
          .fold(MappedProgress(Nil))(value => text(value))

      case _ =>
        MappedProgress(Nil)
    end match
  end progressParts

  private def assistantParts(content: List[ContentBlock]): MappedProgress =
    val textParts =
      compactText(content.collect { case ContentBlock.Text(value) => value })
        .map(value => Part.Text(truncate(redactText(value))))
        .toList

    val thinkingParts =
      compactText(content.collect { case ContentBlock.Thinking(value, _) => value })
        .map(value => Part.Text(truncate(s"Thinking: ${redactText(value)}")))
        .toList

    val toolUses      = content.collect { case toolUse: ContentBlock.ToolUse => toolUse }
    val toolNamesById = toolUses.map(toolUse => toolUse.id -> toolUse.name.raw).toMap
    val toolUseText   =
      Option
        .when(toolUses.nonEmpty && textParts.isEmpty && thinkingParts.isEmpty) {
          Part.Text(s"Calling ${toolUses.map(_.name.raw).distinct.mkString(", ")}")
        }
        .toList
    val toolUseData = toolUses.map { toolUse =>
      Part.Data(
        Json.Obj(
          "kind"  -> Json.Str("tool_use"),
          "id"    -> Json.Str(toolUse.id.value),
          "name"  -> Json.Str(toolUse.name.raw),
          "input" -> redactJson(toolUse.input),
        )
      )
    }

    MappedProgress(textParts ++ thinkingParts ++ toolUseText ++ toolUseData, toolNamesById)
  end assistantParts

  private def toolResultParts(
    content: List[ContentBlock],
    knownToolNames: Map[ToolUseId, String],
  ): MappedProgress =
    val toolResults = content.collect { case result: ContentBlock.ToolResult => result }
    val parts       = toolResults.flatMap { result =>
      val toolName   = knownToolNames.get(result.toolUseId)
      val label      = toolName.getOrElse("tool_result")
      val content    = redactText(result.content)
      val prefix     = if result.isError then "error: " else ""
      val firstLine  = firstNonEmptyLine(content).getOrElse("empty result")
      val dataFields =
        List(
          "kind"      -> Json.Str("tool_result"),
          "toolUseId" -> Json.Str(result.toolUseId.value),
          "isError"   -> Json.Bool(result.isError),
          "content"   -> Json.Str(content),
        ) ++ toolName.map(name => "name" -> Json.Str(name)).toList

      List(
        Part.Text(truncate(s"$label -> $prefix$firstLine")),
        Part.Data(Json.Obj(dataFields*)),
      )
    }

    MappedProgress(parts)
  end toolResultParts

  private def compactText(values: List[String]): Option[String] =
    nonEmpty(values.mkString)

  private def nonEmpty(value: String): Option[String] =
    Option.when(value.trim.nonEmpty)(value.trim)

  private def firstNonEmptyLine(value: String): Option[String] =
    value.linesIterator.map(_.trim).find(_.nonEmpty)

  private def truncate(value: String): String =
    if value.length <= MaxTextLength then value
    else value.take(MaxTextLength - 3) + "..."

  private def redactText(value: String): String =
    secretTextPatterns.foldLeft(value) { (text, pattern) =>
      pattern.replaceAllIn(text, matched => matched.group(1) + "[redacted]")
    }

  private val secretTextPatterns =
    List(
      "(?i)(authorization\\s*[:=]\\s*)[^\\r\\n,;]+".r,
      "(?i)(x-api-key\\s*[:=]\\s*)[^\\r\\n,;]+".r,
      "(?i)(cookie\\s*[:=]\\s*)[^\\r\\n]+".r,
      "(?i)(set-cookie\\s*[:=]\\s*)[^\\r\\n]+".r,
      "(?i)(token\\s*[:=]\\s*)[^\\r\\n,;]+".r,
    )

  private def sensitiveKey(key: String): Boolean =
    val normalized = key.toLowerCase
    normalized == "authorization" ||
    normalized == "x-api-key" ||
    normalized == "cookie" ||
    normalized == "set-cookie" ||
    normalized == "token" ||
    normalized.endsWith("_token") ||
    normalized.endsWith("-token") ||
    normalized.contains("api_key") ||
    normalized.contains("api-key")

  private def redactJson(json: Json): Json =
    json match
      case Json.Obj(fields) =>
        Json.Obj(
          fields.toList.map {
            case (key, _) if sensitiveKey(key) => key -> Json.Str("[redacted]")
            case (key, value)                  => key -> redactJson(value)
          }*
        )
      case Json.Arr(values) =>
        Json.Arr(values.toList.map(redactJson)*)
      case other =>
        other
end A2AProgress
