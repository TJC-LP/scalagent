package com.tjclp.scalagent.streaming

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.config.{CommandName, Model, OutputStyle, PermissionMode, SkillName}
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{ApiMessageId, MessageUuid, SessionId, ToolUseId}

/** Converts raw JavaScript SDK messages to Scala ADT.
  *
  * This handles the discriminated union pattern used by the TypeScript SDK, where each message has a `type` field that
  * determines its structure.
  */
object MessageConverter:

  /** Convert a raw JavaScript message to the Scala ADT.
    *
    * @param raw
    *   The raw JavaScript object from the SDK
    * @return
    *   The corresponding AgentMessage case
    */
  def fromRaw(raw: js.Any): AgentMessage =
    val obj = raw.asInstanceOf[js.Dynamic]
    val msgType = obj.`type`.asInstanceOf[String]

    msgType match
      case "assistant"        => parseAssistantMessage(obj)
      case "user"             => parseUserMessage(obj)
      case "result"           => parseResultMessage(obj)
      case "system"           => parseSystemMessage(obj)
      case "stream_event"     => parseStreamEvent(obj)
      case "tool_progress"    => parseToolProgress(obj)
      case "auth_status"      => parseAuthStatus(obj)
      case "task_notification" => parseTaskNotification(obj)
      case "tool_use_summary" => parseToolUseSummary(obj)
      case other => throw new IllegalArgumentException(s"Unknown message type: $other")

  private def parseAssistantMessage(obj: js.Dynamic): AgentMessage.Assistant =
    AgentMessage.Assistant(
      message = parseApiAssistantMessage(obj.message),
      parentToolUseId = obj.parent_tool_use_id.asInstanceOf[js.UndefOr[String]].toOption.map(ToolUseId.apply),
      error = obj.error.asInstanceOf[js.UndefOr[String]].toOption.map(AssistantMessageError.fromString),
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  private def parseUserMessage(obj: js.Dynamic): AgentMessage =
    val isReplay = obj.isReplay.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)

    if isReplay then
      AgentMessage.UserReplay(
        message = parseApiUserMessage(obj.message),
        parentToolUseId = obj.parent_tool_use_id.asInstanceOf[js.UndefOr[String]].toOption.map(ToolUseId.apply),
        uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
        sessionId = SessionId(obj.session_id.asInstanceOf[String])
      )
    else
      AgentMessage.User(
        message = parseApiUserMessage(obj.message),
        parentToolUseId = obj.parent_tool_use_id.asInstanceOf[js.UndefOr[String]].toOption.map(ToolUseId.apply),
        isSynthetic = obj.isSynthetic.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false),
        toolUseResult = obj.tool_use_result.asInstanceOf[js.UndefOr[js.Any]].toOption.map(jsToJson),
        uuid = obj.uuid.asInstanceOf[js.UndefOr[String]].toOption.map(MessageUuid.apply),
        sessionId = SessionId(obj.session_id.asInstanceOf[String])
      )

  private def parseResultMessage(obj: js.Dynamic): AgentMessage.Result =
    val subtype = obj.subtype.asInstanceOf[String]

    val outcome =
      if subtype == "success" then
        ResultOutcome.Success(
          durationMs = obj.duration_ms.asInstanceOf[Double].toLong,
          durationApiMs = obj.duration_api_ms.asInstanceOf[Double].toLong,
          numTurns = obj.num_turns.asInstanceOf[Int],
          result = obj.result.asInstanceOf[String],
          totalCostUsd = obj.total_cost_usd.asInstanceOf[Double],
          usage = parseModelUsage(obj.usage),
          modelUsage = parseModelUsageMap(obj.modelUsage),
          permissionDenials = parsePermissionDenials(obj.permission_denials),
          structuredOutput = obj.structured_output.asInstanceOf[js.UndefOr[js.Any]].toOption.map(jsToJson)
        )
      else
        ResultOutcome.Error(
          reason = ErrorReason.fromString(subtype),
          durationMs = obj.duration_ms.asInstanceOf[Double].toLong,
          durationApiMs = obj.duration_api_ms.asInstanceOf[Double].toLong,
          numTurns = obj.num_turns.asInstanceOf[Int],
          totalCostUsd = obj.total_cost_usd.asInstanceOf[Double],
          usage = parseModelUsage(obj.usage),
          modelUsage = parseModelUsageMap(obj.modelUsage),
          permissionDenials = parsePermissionDenials(obj.permission_denials),
          errors = obj.errors.asInstanceOf[js.Array[String]].toList
        )

    AgentMessage.Result(
      outcome = outcome,
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  private def parseSystemMessage(obj: js.Dynamic): AgentMessage.System =
    // SDK uses flat structure with `subtype` field, not nested `event.type`
    val subtype = obj.subtype.asInstanceOf[String]

    val event: SystemEvent = subtype match
      case "init"             => parseInitEvent(obj)  // Pass obj directly, not obj.event
      case "compact_boundary" => parseCompactBoundaryEvent(obj)
      case "status"           => parseStatusEvent(obj)
      case "hook_response"    => parseHookResponseEvent(obj)
      case "hook_started"     => parseHookStartedEvent(obj)
      case "hook_progress"    => parseHookProgressEvent(obj)
      case other => throw new IllegalArgumentException(s"Unknown system event subtype: $other")

    AgentMessage.System(
      event = event,
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  private def parseStreamEvent(obj: js.Dynamic): AgentMessage.StreamEvent =
    val event = obj.event.asInstanceOf[js.Dynamic]
    AgentMessage.StreamEvent(
      event = RawStreamEvent(
        eventType = event.`type`.asInstanceOf[String],
        index = event.index.asInstanceOf[js.UndefOr[Int]].toOption,
        contentBlock = parseOptionalContentBlock(event.content_block),
        delta = parseOptionalDelta(event.delta)
      ),
      parentToolUseId = obj.parent_tool_use_id.asInstanceOf[js.UndefOr[String]].toOption.map(ToolUseId.apply),
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  private def parseOptionalContentBlock(raw: js.Dynamic): Option[ContentBlock] =
    if js.isUndefined(raw) || raw == null then None
    else
      val blockType = raw.`type`.asInstanceOf[String]
      Some(blockType match
        case "text" =>
          ContentBlock.Text(raw.text.asInstanceOf[js.UndefOr[String]].getOrElse(""))
        case "tool_use" =>
          ContentBlock.ToolUse(
            id = ToolUseId(raw.id.asInstanceOf[String]),
            name = raw.name.asInstanceOf[String],
            input = jsToJson(raw.input)
          )
        case "thinking" =>
          ContentBlock.Thinking(
            thinking = raw.thinking.asInstanceOf[js.UndefOr[String]].getOrElse(""),
            signature = raw.signature.asInstanceOf[js.UndefOr[String]].toOption
          )
        case _ =>
          ContentBlock.Text(s"[Unknown content block: $blockType]")
      )

  private def parseOptionalDelta(raw: js.Dynamic): Option[StreamDelta] =
    if js.isUndefined(raw) || raw == null then None
    else
      val deltaType = raw.`type`.asInstanceOf[String]
      Some(deltaType match
        case "text_delta" =>
          StreamDelta.TextDelta(raw.text.asInstanceOf[String])
        case "input_json_delta" =>
          StreamDelta.InputJsonDelta(raw.partial_json.asInstanceOf[String])
        case "thinking_delta" =>
          StreamDelta.ThinkingDelta(raw.thinking.asInstanceOf[String])
        case _ =>
          StreamDelta.TextDelta(s"[Unknown delta: $deltaType]")
      )

  private def parseToolProgress(obj: js.Dynamic): AgentMessage.ToolProgress =
    AgentMessage.ToolProgress(
      toolUseId = ToolUseId(obj.tool_use_id.asInstanceOf[String]),
      toolName = ToolName.fromString(obj.tool_name.asInstanceOf[String]),
      parentToolUseId = obj.parent_tool_use_id.asInstanceOf[js.UndefOr[String]].toOption.map(ToolUseId.apply),
      elapsedTimeSeconds = obj.elapsed_time_seconds.asInstanceOf[Double],
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  private def parseAuthStatus(obj: js.Dynamic): AgentMessage.AuthStatus =
    AgentMessage.AuthStatus(
      isAuthenticating = obj.is_authenticating.asInstanceOf[Boolean],
      output = obj.output.asInstanceOf[js.Array[String]].toList,
      error = obj.error.asInstanceOf[js.UndefOr[String]].toOption,
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  private def parseTaskNotification(obj: js.Dynamic): AgentMessage.TaskNotification =
    AgentMessage.TaskNotification(
      taskId = obj.task_id.asInstanceOf[String],
      status = TaskStatus.fromString(obj.status.asInstanceOf[String]),
      outputFile = obj.output_file.asInstanceOf[String],
      summary = obj.summary.asInstanceOf[String],
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  private def parseToolUseSummary(obj: js.Dynamic): AgentMessage.ToolUseSummary =
    AgentMessage.ToolUseSummary(
      summary = obj.summary.asInstanceOf[String],
      precedingToolUseIds = obj.preceding_tool_use_ids
        .asInstanceOf[js.UndefOr[js.Array[String]]]
        .toOption
        .map(_.toList.map(ToolUseId.apply))
        .getOrElse(List.empty),
      uuid = MessageUuid(obj.uuid.asInstanceOf[String]),
      sessionId = SessionId(obj.session_id.asInstanceOf[String])
    )

  // Helper parsers

  private def parseApiAssistantMessage(obj: js.Dynamic): ApiAssistantMessage =
    ApiAssistantMessage(
      id = ApiMessageId(obj.id.asInstanceOf[String]),
      role = Role.fromString(obj.role.asInstanceOf[String]),
      content = parseContentBlocks(obj.content),
      model = obj.model.asInstanceOf[String],
      stopReason = obj.stop_reason.asInstanceOf[js.UndefOr[String]].toOption.map(StopReason.fromString),
      stopSequence = obj.stop_sequence.asInstanceOf[js.UndefOr[String]].toOption,
      usage = obj.usage.asInstanceOf[js.UndefOr[js.Dynamic]].toOption.map(parseModelUsage)
    )

  private def parseApiUserMessage(obj: js.Dynamic): ApiUserMessage =
    ApiUserMessage(
      role = Role.fromString(obj.role.asInstanceOf[String]),
      content = parseContentBlocks(obj.content)
    )

  private def parseContentBlocks(arr: js.Dynamic): List[ContentBlock] =
    arr.asInstanceOf[js.Array[js.Dynamic]].toList.map { block =>
      val blockType = block.`type`.asInstanceOf[String]
      blockType match
        case "text" =>
          ContentBlock.Text(block.text.asInstanceOf[String])
        case "tool_use" =>
          ContentBlock.ToolUse(
            id = ToolUseId(block.id.asInstanceOf[String]),
            name = block.name.asInstanceOf[String],
            input = jsToJson(block.input)
          )
        case "tool_result" =>
          // Content can be either a string or an array of content blocks
          val contentValue = block.content
          val contentStr = if js.typeOf(contentValue) == "string" then
            contentValue.asInstanceOf[String]
          else if js.Array.isArray(contentValue) then
            // Extract text from array of content blocks
            contentValue.asInstanceOf[js.Array[js.Dynamic]].toList.collect {
              case c if c.`type`.asInstanceOf[String] == "text" =>
                c.text.asInstanceOf[String]
            }.mkString("\n")
          else
            js.JSON.stringify(contentValue)
          ContentBlock.ToolResult(
            toolUseId = ToolUseId(block.tool_use_id.asInstanceOf[String]),
            content = contentStr,
            isError = block.is_error.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)
          )
        case "thinking" =>
          ContentBlock.Thinking(
            thinking = block.thinking.asInstanceOf[String],
            signature = block.signature.asInstanceOf[js.UndefOr[String]].toOption
          )
        case other =>
          // Fallback to text for unknown types
          ContentBlock.Text(s"[Unknown content block type: $other]")
    }

  private def parseModelUsage(obj: js.Dynamic): ModelUsage =
    ModelUsage(
      inputTokens = obj.input_tokens.asInstanceOf[Int],
      outputTokens = obj.output_tokens.asInstanceOf[Int],
      cacheReadInputTokens =
        obj.cache_read_input_tokens.asInstanceOf[js.UndefOr[Int]].getOrElse(0),
      cacheCreationInputTokens =
        obj.cache_creation_input_tokens.asInstanceOf[js.UndefOr[Int]].getOrElse(0)
    )

  private def parseModelUsageMap(obj: js.Dynamic): Map[String, PerModelUsage] =
    if js.isUndefined(obj) then Map.empty
    else
      val dict = obj.asInstanceOf[js.Dictionary[js.Dynamic]]
      dict.toMap.map { case (k, v) =>
        // SDK uses camelCase for modelUsage fields
        k -> PerModelUsage(
          inputTokens = v.inputTokens.asInstanceOf[Int],
          outputTokens = v.outputTokens.asInstanceOf[Int],
          cacheReadInputTokens = v.cacheReadInputTokens.asInstanceOf[js.UndefOr[Int]].getOrElse(0),
          cacheCreationInputTokens =
            v.cacheCreationInputTokens.asInstanceOf[js.UndefOr[Int]].getOrElse(0),
          webSearchRequests = v.webSearchRequests.asInstanceOf[js.UndefOr[Int]].getOrElse(0),
          costUSD = v.costUSD.asInstanceOf[Double],
          contextWindow = v.contextWindow.asInstanceOf[Int]
        )
      }

  private def parsePermissionDenials(arr: js.Dynamic): List[PermissionDenial] =
    if js.isUndefined(arr) then Nil
    else
      arr.asInstanceOf[js.Array[js.Dynamic]].toList.map { denial =>
        PermissionDenial(
          toolName = denial.tool_name.asInstanceOf[String],
          toolUseId = ToolUseId(denial.tool_use_id.asInstanceOf[String]),
          toolInput = jsToJson(denial.tool_input)
        )
      }

  private def parseInitEvent(obj: js.Dynamic): SystemEvent.Init =
    SystemEvent.Init(
      apiKeySource = obj.apiKeySource.asInstanceOf[String],
      claudeCodeVersion = obj.claude_code_version.asInstanceOf[String],
      cwd = obj.cwd.asInstanceOf[String],
      tools = obj.tools.asInstanceOf[js.Array[String]].toList.map(ToolName.fromString),
      mcpServers = parseMcpServers(obj.mcp_servers),
      model = Model.fromId(obj.model.asInstanceOf[String]),
      permissionMode = PermissionMode.fromString(obj.permissionMode.asInstanceOf[String]),
      slashCommands = obj.slash_commands.asInstanceOf[js.Array[String]].toList.map(CommandName(_)),
      outputStyle = OutputStyle.fromString(obj.output_style.asInstanceOf[String]),
      skills = obj.skills.asInstanceOf[js.Array[String]].toList.map(SkillName.apply),
      plugins = parsePlugins(obj.plugins),
      agents = obj.agents.asInstanceOf[js.UndefOr[js.Array[String]]].toOption.map(_.toList),
      betas = obj.betas.asInstanceOf[js.UndefOr[js.Array[String]]].toOption.map(_.toList)
    )

  private def parseCompactBoundaryEvent(obj: js.Dynamic): SystemEvent.CompactBoundary =
    // SDK uses compact_metadata nested object for boundary info
    val metadata = obj.compact_metadata.asInstanceOf[js.Dynamic]
    SystemEvent.CompactBoundary(
      trigger = CompactTrigger.fromString(metadata.trigger.asInstanceOf[String]),
      preTokens = metadata.pre_tokens.asInstanceOf[Int]
    )

  private def parseStatusEvent(obj: js.Dynamic): SystemEvent.Status =
    SystemEvent.Status(
      status = obj.status.asInstanceOf[js.UndefOr[String]].toOption.map(SdkStatus.fromString)
    )

  private def parseHookResponseEvent(obj: js.Dynamic): SystemEvent.HookResponse =
    SystemEvent.HookResponse(
      hookId = obj.hook_id.asInstanceOf[String],
      hookName = obj.hook_name.asInstanceOf[String],
      hookEvent = obj.hook_event.asInstanceOf[String],
      stdout = obj.stdout.asInstanceOf[js.UndefOr[String]].getOrElse(""),
      stderr = obj.stderr.asInstanceOf[js.UndefOr[String]].getOrElse(""),
      output = obj.output.asInstanceOf[js.UndefOr[String]].getOrElse(""),
      exitCode = obj.exit_code.asInstanceOf[js.UndefOr[Int]].toOption,
      outcome = HookOutcome.fromString(obj.outcome.asInstanceOf[String])
    )

  private def parseHookStartedEvent(obj: js.Dynamic): SystemEvent.HookStarted =
    SystemEvent.HookStarted(
      hookId = obj.hook_id.asInstanceOf[String],
      hookName = obj.hook_name.asInstanceOf[String],
      hookEvent = obj.hook_event.asInstanceOf[String]
    )

  private def parseHookProgressEvent(obj: js.Dynamic): SystemEvent.HookProgress =
    SystemEvent.HookProgress(
      hookId = obj.hook_id.asInstanceOf[String],
      hookName = obj.hook_name.asInstanceOf[String],
      hookEvent = obj.hook_event.asInstanceOf[String],
      stdout = obj.stdout.asInstanceOf[js.UndefOr[String]].getOrElse(""),
      stderr = obj.stderr.asInstanceOf[js.UndefOr[String]].getOrElse(""),
      output = obj.output.asInstanceOf[js.UndefOr[String]].getOrElse("")
    )

  private def parseMcpServers(arr: js.Dynamic): List[McpServerStatus] =
    if js.isUndefined(arr) then Nil
    else
      arr.asInstanceOf[js.Array[js.Dynamic]].toList.map { server =>
        McpServerStatus(
          name = server.name.asInstanceOf[String],
          status = McpConnectionStatus.fromString(server.status.asInstanceOf[String]),
          serverInfo = server.server_info.asInstanceOf[js.UndefOr[js.Dynamic]].toOption.map { info =>
            McpServerInfo(
              name = info.name.asInstanceOf[String],
              version = info.version.asInstanceOf[String]
            )
          },
          error = server.error.asInstanceOf[js.UndefOr[String]].toOption,
          scope = server.scope.asInstanceOf[js.UndefOr[String]].toOption,
          tools = server.tools.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].toOption.map { toolsArr =>
            toolsArr.toList.map { tool =>
              McpToolInfo(
                name = tool.name.asInstanceOf[String],
                description = tool.description.asInstanceOf[js.UndefOr[String]].toOption,
                annotations = tool.annotations.asInstanceOf[js.UndefOr[js.Dynamic]].toOption.map { ann =>
                  McpToolAnnotations(
                    readOnly = ann.readOnly.asInstanceOf[js.UndefOr[Boolean]].toOption,
                    destructive = ann.destructive.asInstanceOf[js.UndefOr[Boolean]].toOption,
                    openWorld = ann.openWorld.asInstanceOf[js.UndefOr[Boolean]].toOption
                  )
                }
              )
            }
          }
        )
      }

  private def parsePlugins(arr: js.Dynamic): List[PluginInfo] =
    if js.isUndefined(arr) then Nil
    else
      arr.asInstanceOf[js.Array[js.Dynamic]].toList.map { plugin =>
        PluginInfo(
          name = plugin.name.asInstanceOf[String],
          path = plugin.path.asInstanceOf[String]
        )
      }

  /** Convert a JavaScript value to zio-json AST */
  private def jsToJson(value: js.Any): Json =
    val jsonStr = js.JSON.stringify(value)
    jsonStr.fromJson[Json].getOrElse(Json.Null)
