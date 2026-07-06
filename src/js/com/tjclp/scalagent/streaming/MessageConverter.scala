package com.tjclp.scalagent.streaming

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.Try
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.config.{CommandName, FastModeState, Model, OutputStyle, PermissionMode, SkillName}
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{ApiMessageId, MessageUuid, SessionId, ToolUseId}

/** Converts raw JavaScript SDK messages to Scala ADTs in a forward-compatible way. */
object MessageConverter:

  final case class MessageParseException(
    message: String,
    raw: Json,
    cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  private final case class EnvelopeContext(
    uuid: Option[MessageUuid],
    sessionId: Option[SessionId],
    parentToolUseId: Option[ToolUseId])

  def fromRaw(raw: js.Any): AgentMessage =
    val rawJson    = jsToJson(raw)
    val obj        = asDynamicObject(raw, rawJson)
    val rawType    = requiredString(obj, "type", rawJson)
    val context    = envelopeContext(obj)
    val rawSubtype = stringField(obj, "subtype")

    rawType match
      case "assistant" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseAssistantMessage(obj, rawJson, context)
        }
      case "user" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseUserMessage(obj, rawJson, context)
        }
      case "result" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseResultMessage(obj, rawJson)
        }
      case "system" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseSystemEnvelope(obj, rawJson, context)
        }
      case "stream_event" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseStreamEvent(obj, rawJson, context)
        }
      case "tool_progress" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseToolProgress(obj, rawJson)
        }
      case "auth_status" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseAuthStatus(obj, rawJson)
        }
      case "task_notification" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseTaskNotification(obj, rawJson)
        }
      case "tool_use_summary" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseToolUseSummary(obj, rawJson)
        }
      case "prompt_suggestion" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parsePromptSuggestion(obj, rawJson)
        }
      case "rate_limit_event" | "rate_limit" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseRateLimitEvent(obj, rawJson)
        }
      case "local_command_output" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseLocalCommandOutput(obj, rawJson)
        }
      case "elicitation_complete" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseElicitationComplete(obj, rawJson)
        }
      case "task_started" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseTaskStarted(obj, rawJson)
        }
      case "task_progress" =>
        guardTopLevelUnknown(rawJson, rawType, rawSubtype, context) {
          parseTaskProgress(obj, rawJson)
        }
      case other =>
        AgentMessage.Unknown(unknownEnvelope(rawJson, other, rawSubtype, context))
    end match
  end fromRaw

  private def guardTopLevelUnknown(
    raw: Json,
    rawType: String,
    rawSubtype: Option[String],
    context: EnvelopeContext,
  )(parse: => AgentMessage
  ): AgentMessage =
    try parse
    catch
      case _: MessageParseException =>
        AgentMessage.Unknown(unknownEnvelope(raw, rawType, rawSubtype, context))

  private def parseAssistantMessage(
    obj: js.Dynamic,
    raw: Json,
    context: EnvelopeContext,
  ): AgentMessage.Assistant =
    val messageObj = requiredDynamic(obj, "message", raw, "assistant.message")
    AgentMessage.Assistant(
      message = parseApiAssistantMessage(messageObj, jsToJson(messageObj.asInstanceOf[js.Any]), context),
      parentToolUseId = context.parentToolUseId,
      error = stringField(obj, "error").map(AssistantMessageError.fromString),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
      requestId = stringField(obj, "request_id"),
      subagentType = stringField(obj, "subagent_type"),
      taskDescription = stringField(obj, "task_description"),
    )

  private def parseUserMessage(
    obj: js.Dynamic,
    raw: Json,
    context: EnvelopeContext,
  ): AgentMessage =
    val messageObj = requiredDynamic(obj, "message", raw, "user.message")
    val message    = parseApiUserMessage(messageObj, jsToJson(messageObj.asInstanceOf[js.Any]), context)
    val isReplay   = booleanField(obj, "isReplay").getOrElse(false)

    if isReplay then
      AgentMessage.UserReplay(
        message = message,
        parentToolUseId = context.parentToolUseId,
        uuid = requiredUuid(obj, raw),
        sessionId = requiredSessionId(obj, raw),
      )
    else
      AgentMessage.User(
        message = message,
        parentToolUseId = context.parentToolUseId,
        isSynthetic = booleanField(obj, "isSynthetic").getOrElse(false),
        toolUseResult = anyField(obj, "tool_use_result").map(jsToJson),
        uuid = stringField(obj, "uuid").map(MessageUuid.apply),
        sessionId = requiredSessionId(obj, raw),
        timestamp = stringField(obj, "timestamp"),
        origin = anyField(obj, "origin").map(jsToJson),
        subagentType = stringField(obj, "subagent_type"),
        taskDescription = stringField(obj, "task_description"),
      )
    end if
  end parseUserMessage

  private def parseResultMessage(obj: js.Dynamic, raw: Json): AgentMessage.Result =
    val subtype    = requiredString(obj, "subtype", raw)
    val stopReason = stringField(obj, "stop_reason").map(StopReason.fromString)

    val outcome =
      if subtype == "success" then
        ResultOutcome.Success(
          durationMs = longField(obj, "duration_ms").getOrElse(0L),
          durationApiMs = longField(obj, "duration_api_ms").getOrElse(0L),
          numTurns = intField(obj, "num_turns").getOrElse(0),
          result = stringField(obj, "result").getOrElse(""),
          totalCostUsd = doubleField(obj, "total_cost_usd").getOrElse(0.0),
          usage = dynamicField(obj, "usage").map(parseModelUsage).getOrElse(ModelUsage.empty),
          modelUsage = dynamicField(obj, "modelUsage").map(parseModelUsageMap).getOrElse(Map.empty),
          permissionDenials = dynamicArrayField(obj, "permission_denials").map(parsePermissionDenial),
          structuredOutput = anyField(obj, "structured_output").map(jsToJson),
          stopReason = stopReason,
          deferredToolUse = dynamicField(obj, "deferred_tool_use").map { dtu =>
            DeferredToolUse(
              id = stringField(dtu, "id").getOrElse(""),
              name = stringField(dtu, "name").getOrElse(""),
              input = anyField(dtu, "input").map(jsToJson).getOrElse(Json.Null),
            )
          },
        )
      else
        ResultOutcome.Error(
          reason = ErrorReason.fromString(subtype),
          durationMs = longField(obj, "duration_ms").getOrElse(0L),
          durationApiMs = longField(obj, "duration_api_ms").getOrElse(0L),
          numTurns = intField(obj, "num_turns").getOrElse(0),
          totalCostUsd = doubleField(obj, "total_cost_usd").getOrElse(0.0),
          usage = dynamicField(obj, "usage").map(parseModelUsage).getOrElse(ModelUsage.empty),
          modelUsage = dynamicField(obj, "modelUsage").map(parseModelUsageMap).getOrElse(Map.empty),
          permissionDenials = dynamicArrayField(obj, "permission_denials").map(parsePermissionDenial),
          errors = stringArrayField(obj, "errors"),
          stopReason = stopReason,
        )

    AgentMessage.Result(
      outcome = outcome,
      fastModeState = stringField(obj, "fast_mode_state").map(FastModeState.fromString),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )
  end parseResultMessage

  private def parseSystemEnvelope(
    obj: js.Dynamic,
    raw: Json,
    context: EnvelopeContext,
  ): AgentMessage =
    stringField(obj, "subtype") match
      case Some("task_notification")    => parseTaskNotification(obj, raw)
      case Some("task_progress")        => parseTaskProgress(obj, raw)
      case Some("task_started")         => parseTaskStarted(obj, raw)
      case Some("local_command_output") => parseLocalCommandOutput(obj, raw)
      case Some("elicitation_complete") => parseElicitationComplete(obj, raw)
      case Some("api_retry")            =>
        guardTopLevelUnknown(raw, "system", Some("api_retry"), context) {
          parseApiRetryMessage(obj, raw)
        }
      case Some("bridge_metadata") =>
        guardTopLevelUnknown(raw, "system", Some("bridge_metadata"), context) {
          AgentMessage.BridgeMetadata(
            slashCommands = stringArrayField(obj, "slash_commands"),
            uuid = requiredUuid(obj, raw),
            sessionId = requiredSessionId(obj, raw),
          )
        }
      case Some("mirror_error") =>
        guardTopLevelUnknown(raw, "system", Some("mirror_error"), context) {
          parseMirrorError(obj, raw)
        }
      case other =>
        AgentMessage.System(
          event = parseSystemEvent(obj, raw, other, context),
          uuid = requiredUuid(obj, raw),
          sessionId = requiredSessionId(obj, raw),
        )

  private def parseSystemEvent(
    obj: js.Dynamic,
    raw: Json,
    subtype: Option[String],
    context: EnvelopeContext,
  ): SystemEvent =
    subtype match
      case Some("init") =>
        guardedSystemEvent(raw, context, subtype) {
          parseInitEvent(obj, raw)
        }
      case Some("compact_boundary") =>
        guardedSystemEvent(raw, context, subtype) {
          parseCompactBoundaryEvent(obj, raw)
        }
      case Some("status") =>
        guardedSystemEvent(raw, context, subtype) {
          parseStatusEvent(obj)
        }
      case Some("hook_response") =>
        guardedSystemEvent(raw, context, subtype) {
          parseHookResponseEvent(obj, raw)
        }
      case Some("hook_started") =>
        guardedSystemEvent(raw, context, subtype) {
          parseHookStartedEvent(obj, raw)
        }
      case Some("hook_progress") =>
        guardedSystemEvent(raw, context, subtype) {
          parseHookProgressEvent(obj, raw)
        }
      case Some("files_persisted") =>
        guardedSystemEvent(raw, context, subtype) {
          parseFilesPersistedEvent(obj)
        }
      case Some("session_state_changed") =>
        guardedSystemEvent(raw, context, subtype) {
          parseSessionStateChangedEvent(obj)
        }
      case Some("memory_recall") =>
        guardedSystemEvent(raw, context, subtype) {
          parseMemoryRecallEvent(obj, raw)
        }
      case Some("informational") =>
        guardedSystemEvent(raw, context, subtype) {
          parseInformationalEvent(obj, raw)
        }
      case Some("model_refusal_fallback") =>
        guardedSystemEvent(raw, context, subtype) {
          parseModelRefusalFallbackEvent(obj, raw)
        }
      case Some("model_refusal_no_fallback") =>
        guardedSystemEvent(raw, context, subtype) {
          parseModelRefusalNoFallbackEvent(obj, raw)
        }
      case Some("worker_shutting_down") =>
        guardedSystemEvent(raw, context, subtype) {
          parseWorkerShuttingDownEvent(obj, raw)
        }
      case other =>
        SystemEvent.Unknown(unknownEnvelope(raw, "system", other, context))

  private def guardedSystemEvent(
    raw: Json,
    context: EnvelopeContext,
    subtype: Option[String],
  )(parse: => SystemEvent
  ): SystemEvent =
    try parse
    catch
      case _: MessageParseException =>
        SystemEvent.Unknown(unknownEnvelope(raw, "system", subtype, context))

  private def parseStreamEvent(
    obj: js.Dynamic,
    raw: Json,
    context: EnvelopeContext,
  ): AgentMessage.StreamEvent =
    val event    = requiredDynamic(obj, "event", raw, "stream_event.event")
    val eventRaw = jsToJson(event.asInstanceOf[js.Any])

    AgentMessage.StreamEvent(
      event = RawStreamEvent(
        eventType = requiredString(event, "type", eventRaw),
        index = intField(event, "index"),
        contentBlock = anyField(event, "content_block").map(value => parseContentBlock(value, context)),
        delta = anyField(event, "delta").map(value => parseDelta(value, context)),
      ),
      parentToolUseId = context.parentToolUseId,
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseContentBlock(raw: js.Any, context: EnvelopeContext): ContentBlock =
    val obj     = asDynamicObject(raw, jsToJson(raw))
    val rawJson = jsToJson(raw)

    stringField(obj, "type") match
      case Some("text") =>
        ContentBlock.Text(stringField(obj, "text").getOrElse(""))
      case Some("tool_use") =>
        (for
          id   <- stringField(obj, "id")
          name <- stringField(obj, "name")
        yield ContentBlock.ToolUse(
          id = ToolUseId(id),
          name = ToolName.fromString(name),
          input = anyField(obj, "input").map(jsToJson).getOrElse(Json.Null),
        )).getOrElse(ContentBlock.Unknown(unknownEnvelope(rawJson, "tool_use", None, context)))
      case Some("tool_result") =>
        stringField(obj, "tool_use_id") match
          case Some(toolUseId) =>
            ContentBlock.ToolResult(
              toolUseId = ToolUseId(toolUseId),
              content = parseToolResultContent(anyField(obj, "content")),
              isError = booleanField(obj, "is_error").getOrElse(false),
            )
          case None =>
            ContentBlock.Unknown(unknownEnvelope(rawJson, "tool_result", None, context))
      case Some("thinking") =>
        ContentBlock.Thinking(
          thinking = stringField(obj, "thinking").getOrElse(""),
          signature = stringField(obj, "signature"),
        )
      case Some("image") =>
        parseImageBlock(obj, rawJson, context)
      case Some(other) =>
        ContentBlock.Unknown(unknownEnvelope(rawJson, other, None, context))
      case None =>
        ContentBlock.Unknown(unknownEnvelope(rawJson, "<missing-content-type>", None, context))
    end match
  end parseContentBlock

  private def parseDelta(raw: js.Any, context: EnvelopeContext): StreamDelta =
    val obj     = asDynamicObject(raw, jsToJson(raw))
    val rawJson = jsToJson(raw)

    stringField(obj, "type") match
      case Some("text_delta") =>
        StreamDelta.TextDelta(stringField(obj, "text").getOrElse(""))
      case Some("input_json_delta") =>
        StreamDelta.InputJsonDelta(stringField(obj, "partial_json").getOrElse(""))
      case Some("thinking_delta") =>
        StreamDelta.ThinkingDelta(stringField(obj, "thinking").getOrElse(""))
      case Some(other) =>
        StreamDelta.Unknown(unknownEnvelope(rawJson, other, None, context))
      case None =>
        StreamDelta.Unknown(unknownEnvelope(rawJson, "<missing-delta-type>", None, context))

  private def parseToolProgress(obj: js.Dynamic, raw: Json): AgentMessage.ToolProgress =
    AgentMessage.ToolProgress(
      toolUseId = ToolUseId(requiredString(obj, "tool_use_id", raw)),
      toolName = ToolName.fromString(requiredString(obj, "tool_name", raw)),
      parentToolUseId = stringField(obj, "parent_tool_use_id").map(ToolUseId.apply),
      elapsedTimeSeconds = doubleField(obj, "elapsed_time_seconds").getOrElse(0.0),
      taskId = stringField(obj, "task_id"),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseAuthStatus(obj: js.Dynamic, raw: Json): AgentMessage.AuthStatus =
    AgentMessage.AuthStatus(
      isAuthenticating = firstBoolean(obj, "isAuthenticating", "is_authenticating").getOrElse(false),
      output = stringArrayField(obj, "output"),
      error = stringField(obj, "error"),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseTaskNotification(obj: js.Dynamic, raw: Json): AgentMessage.TaskNotification =
    AgentMessage.TaskNotification(
      taskId = requiredString(obj, "task_id", raw),
      status = TaskStatus.fromString(requiredString(obj, "status", raw)),
      outputFile = stringField(obj, "output_file").getOrElse(""),
      summary = stringField(obj, "summary").getOrElse(""),
      toolUseId = stringField(obj, "tool_use_id").map(ToolUseId.apply),
      usage = dynamicField(obj, "usage").map(parseModelUsageFlexible),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseToolUseSummary(obj: js.Dynamic, raw: Json): AgentMessage.ToolUseSummary =
    AgentMessage.ToolUseSummary(
      summary = stringField(obj, "summary").getOrElse(""),
      precedingToolUseIds = stringArrayField(obj, "preceding_tool_use_ids").map(ToolUseId.apply),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parsePromptSuggestion(obj: js.Dynamic, raw: Json): AgentMessage.PromptSuggestion =
    AgentMessage.PromptSuggestion(
      suggestion = stringField(obj, "suggestion").getOrElse(""),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseRateLimitEvent(obj: js.Dynamic, raw: Json): AgentMessage.RateLimitEvent =
    val rateLimitInfo = dynamicField(obj, "rate_limit_info")
    val overageInfo   = dynamicField(obj, "overage_info").orElse(rateLimitInfo.flatMap(dynamicField(_, "overage")))
    AgentMessage.RateLimitEvent(
      retryAfterMs = longField(obj, "retry_after_ms"),
      model = stringField(obj, "model"),
      status = rateLimitInfo.flatMap(stringField(_, "status")),
      resetsAt = rateLimitInfo.flatMap(longField(_, "resetsAt")),
      rateLimitType = rateLimitInfo.flatMap(stringField(_, "rateLimitType")),
      utilization = rateLimitInfo.flatMap(doubleField(_, "utilization")),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
      overageStatus = overageInfo.flatMap(stringField(_, "status")).orElse(stringField(obj, "overage_status")),
      overageResetsAt = overageInfo.flatMap(stringField(_, "resets_at")).orElse(stringField(obj, "overage_resets_at")),
      overageDisabledReason =
        overageInfo.flatMap(stringField(_, "disabled_reason")).orElse(stringField(obj, "overage_disabled_reason")),
      isUsingOverage =
        overageInfo.flatMap(booleanField(_, "is_using_overage")).orElse(booleanField(obj, "is_using_overage")),
      surpassedThreshold =
        overageInfo.flatMap(booleanField(_, "surpassed_threshold")).orElse(booleanField(obj, "surpassed_threshold")),
    )
  end parseRateLimitEvent

  private def parseLocalCommandOutput(obj: js.Dynamic, raw: Json): AgentMessage.LocalCommandOutput =
    AgentMessage.LocalCommandOutput(
      output = firstString(obj, "content", "output").getOrElse(""),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseElicitationComplete(obj: js.Dynamic, raw: Json): AgentMessage.ElicitationComplete =
    AgentMessage.ElicitationComplete(
      mcpServerName = stringField(obj, "mcp_server_name").getOrElse(""),
      elicitationId = stringField(obj, "elicitation_id").getOrElse(""),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseTaskStarted(obj: js.Dynamic, raw: Json): AgentMessage.TaskStarted =
    AgentMessage.TaskStarted(
      taskId = requiredString(obj, "task_id", raw),
      description = stringField(obj, "description").getOrElse(""),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
      toolUseId = stringField(obj, "tool_use_id"),
      taskType = stringField(obj, "task_type"),
      prompt = stringField(obj, "prompt"),
      workflowName = firstString(obj, "workflow_name", "workflowName"),
    )

  private def parseApiRetryMessage(obj: js.Dynamic, raw: Json): AgentMessage.ApiRetry =
    AgentMessage.ApiRetry(
      attempt = intField(obj, "attempt").getOrElse(0),
      maxRetries = intField(obj, "max_retries").getOrElse(0),
      retryDelayMs = longField(obj, "retry_delay_ms").getOrElse(0L),
      errorStatus = intField(obj, "error_status"),
      error = stringField(obj, "error").map(AssistantMessageError.fromString).getOrElse(AssistantMessageError.Unknown),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseTaskProgress(obj: js.Dynamic, raw: Json): AgentMessage.TaskProgress =
    AgentMessage.TaskProgress(
      taskId = requiredString(obj, "task_id", raw),
      progress = firstString(obj, "description", "progress").getOrElse(""),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
      summary = stringField(obj, "summary"),
      toolUseId = firstString(obj, "tool_use_id", "toolUseId"),
      lastToolName = firstString(obj, "last_tool_name", "lastToolName"),
    )

  private def parseApiAssistantMessage(
    obj: js.Dynamic,
    raw: Json,
    context: EnvelopeContext,
  ): ApiAssistantMessage =
    ApiAssistantMessage(
      id = ApiMessageId(requiredString(obj, "id", raw)),
      role = Role.fromString(requiredString(obj, "role", raw)),
      content = parseContentBlocks(obj, "content", raw, context),
      model = stringField(obj, "model").getOrElse(""),
      stopReason = stringField(obj, "stop_reason").map(StopReason.fromString),
      stopSequence = stringField(obj, "stop_sequence"),
      usage = dynamicField(obj, "usage").map(parseModelUsage),
    )

  private def parseApiUserMessage(
    obj: js.Dynamic,
    raw: Json,
    context: EnvelopeContext,
  ): ApiUserMessage =
    ApiUserMessage(
      role = Role.fromString(requiredString(obj, "role", raw)),
      content = parseContentBlocks(obj, "content", raw, context),
    )

  private def parseContentBlocks(
    obj: js.Dynamic,
    field: String,
    raw: Json,
    context: EnvelopeContext,
  ): List[ContentBlock] =
    requiredArray(obj, field, raw).toList.map(rawValue => parseContentBlock(rawValue, context))

  private def parseImageBlock(
    obj: js.Dynamic,
    raw: Json,
    context: EnvelopeContext,
  ): ContentBlock =
    dynamicField(obj, "source") match
      case Some(source) =>
        val mediaType = stringField(source, "media_type").orElse(stringField(obj, "media_type")).getOrElse("")
        stringField(source, "type") match
          case Some("base64") =>
            stringField(source, "data") match
              case Some(data) =>
                ContentBlock.Image(
                  source = ImageSource.Base64(data, mediaType),
                  mediaType = mediaType,
                )
              case None =>
                ContentBlock.Unknown(unknownEnvelope(raw, "image", Some("base64"), context))
          case Some("url") =>
            stringField(source, "url").orElse(stringField(source, "uri")) match
              case Some(url) =>
                ContentBlock.Image(
                  source = ImageSource.Url(url),
                  mediaType = mediaType,
                )
              case None =>
                ContentBlock.Unknown(unknownEnvelope(raw, "image", Some("url"), context))
          case Some(other) =>
            ContentBlock.Unknown(unknownEnvelope(raw, "image", Some(other), context))
          case None =>
            ContentBlock.Unknown(unknownEnvelope(raw, "image", None, context))
        end match
      case None =>
        ContentBlock.Unknown(unknownEnvelope(raw, "image", None, context))

  private def parseToolResultContent(content: Option[js.Any]): String =
    content match
      case Some(value) if js.typeOf(value) == "string" =>
        value.asInstanceOf[String]
      case Some(value) if js.Array.isArray(value) =>
        value
          .asInstanceOf[js.Array[js.Any]]
          .toList
          .map(rawValue => parseContentBlock(rawValue, EnvelopeContext(None, None, None)))
          .collect { case ContentBlock.Text(text) => text }
          .mkString("\n")
      case Some(value) =>
        js.JSON.stringify(value)
      case None =>
        ""

  private def parseInitEvent(obj: js.Dynamic, raw: Json): SystemEvent.Init =
    SystemEvent.Init(
      apiKeySource = ApiKeySource.fromString(requiredString(obj, "apiKeySource", raw)),
      claudeCodeVersion = requiredString(obj, "claude_code_version", raw),
      cwd = requiredString(obj, "cwd", raw),
      tools = stringArrayField(obj, "tools").map(ToolName.fromString),
      mcpServers = dynamicArrayField(obj, "mcp_servers").flatMap(parseMcpServer),
      model = Model.fromId(requiredString(obj, "model", raw)),
      permissionMode = PermissionMode.fromString(requiredString(obj, "permissionMode", raw)),
      slashCommands = stringArrayField(obj, "slash_commands").map(CommandName.apply),
      outputStyle = OutputStyle.fromString(requiredString(obj, "output_style", raw)),
      skills = stringArrayField(obj, "skills").map(SkillName.apply),
      plugins = dynamicArrayField(obj, "plugins").flatMap(parsePlugin),
      agents = optionStringArray(obj, "agents"),
      betas = optionStringArray(obj, "betas"),
      fastModeState = stringField(obj, "fast_mode_state").map(FastModeState.fromString),
    )

  private def parseCompactBoundaryEvent(obj: js.Dynamic, raw: Json): SystemEvent.CompactBoundary =
    val metadata = requiredDynamic(obj, "compact_metadata", raw, "system.compact_metadata")
    SystemEvent.CompactBoundary(
      trigger = CompactTrigger.fromString(requiredString(metadata, "trigger", raw)),
      preTokens = intField(metadata, "pre_tokens").getOrElse(0),
    )

  private def parseStatusEvent(obj: js.Dynamic): SystemEvent.Status =
    SystemEvent.Status(
      status = stringField(obj, "status").map(SdkStatus.fromString),
      permissionMode = stringField(obj, "permissionMode").map(PermissionMode.fromString),
    )

  private def parseHookResponseEvent(obj: js.Dynamic, raw: Json): SystemEvent.HookResponse =
    SystemEvent.HookResponse(
      hookId = requiredString(obj, "hook_id", raw),
      hookName = requiredString(obj, "hook_name", raw),
      hookEvent = requiredString(obj, "hook_event", raw),
      stdout = stringField(obj, "stdout").getOrElse(""),
      stderr = stringField(obj, "stderr").getOrElse(""),
      output = stringField(obj, "output").getOrElse(""),
      exitCode = intField(obj, "exit_code"),
      outcome = HookOutcome.fromString(requiredString(obj, "outcome", raw)),
    )

  private def parseHookStartedEvent(obj: js.Dynamic, raw: Json): SystemEvent.HookStarted =
    SystemEvent.HookStarted(
      hookId = requiredString(obj, "hook_id", raw),
      hookName = requiredString(obj, "hook_name", raw),
      hookEvent = requiredString(obj, "hook_event", raw),
    )

  private def parseHookProgressEvent(obj: js.Dynamic, raw: Json): SystemEvent.HookProgress =
    SystemEvent.HookProgress(
      hookId = requiredString(obj, "hook_id", raw),
      hookName = requiredString(obj, "hook_name", raw),
      hookEvent = requiredString(obj, "hook_event", raw),
      stdout = stringField(obj, "stdout").getOrElse(""),
      stderr = stringField(obj, "stderr").getOrElse(""),
      output = stringField(obj, "output").getOrElse(""),
    )

  private def parseFilesPersistedEvent(obj: js.Dynamic): SystemEvent.FilesPersisted =
    SystemEvent.FilesPersisted(
      files = dynamicArrayField(obj, "files").flatMap { file =>
        for
          filename <- stringField(file, "filename")
          fileId   <- stringField(file, "file_id")
        yield PersistedFile(filename, fileId)
      },
      failed = dynamicArrayField(obj, "failed").flatMap { file =>
        for
          filename <- stringField(file, "filename")
          error    <- stringField(file, "error")
        yield FailedFile(filename, error)
      },
      processedAt = stringField(obj, "processed_at").getOrElse(""),
    )

  private def parseSessionStateChangedEvent(obj: js.Dynamic): SystemEvent.SessionStateChanged =
    val stateStr = stringField(obj, "state").getOrElse("idle")
    SystemEvent.SessionStateChanged(
      state = SdkSessionState.fromString(stateStr)
    )

  private def parseMirrorError(obj: js.Dynamic, raw: Json): AgentMessage.MirrorError =
    val key = requiredDynamic(obj, "key", raw, "system.mirror_error.key")
    AgentMessage.MirrorError(
      error = requiredString(obj, "error", raw),
      projectKey = requiredString(key, "projectKey", raw),
      mirroredSessionId = SessionId(requiredString(key, "sessionId", raw)),
      subpath = stringField(key, "subpath"),
      uuid = requiredUuid(obj, raw),
      sessionId = requiredSessionId(obj, raw),
    )

  private def parseMemoryRecallEvent(obj: js.Dynamic, raw: Json): SystemEvent.MemoryRecall =
    val mode     = MemoryRecallMode.fromString(requiredString(obj, "mode", raw))
    val memories = dynamicArrayField(obj, "memories").flatMap { mem =>
      for path <- stringField(mem, "path")
      yield RecalledMemory(
        path = path,
        scope = stringField(mem, "scope").map(MemoryScope.fromString).getOrElse(MemoryScope.Custom("")),
        content = stringField(mem, "content"),
      )
    }
    SystemEvent.MemoryRecall(mode = mode, memories = memories)

  private def parseInformationalEvent(obj: js.Dynamic, raw: Json): SystemEvent.Informational =
    SystemEvent.Informational(
      content = requiredString(obj, "content", raw),
      level = InformationalLevel.fromString(requiredString(obj, "level", raw)),
      toolUseId = stringField(obj, "tool_use_id").map(ToolUseId.apply),
      preventContinuation = booleanField(obj, "prevent_continuation").getOrElse(false),
    )

  private def parseModelRefusalFallbackEvent(obj: js.Dynamic, raw: Json): SystemEvent.ModelRefusalFallback =
    SystemEvent.ModelRefusalFallback(
      originalModel = requiredString(obj, "original_model", raw),
      fallbackModel = requiredString(obj, "fallback_model", raw),
      content = stringField(obj, "content").getOrElse(""),
      requestId = stringField(obj, "request_id"),
      apiRefusalCategory = stringField(obj, "api_refusal_category"),
      apiRefusalExplanation = stringField(obj, "api_refusal_explanation"),
      retractedMessageUuids = stringArrayField(obj, "retracted_message_uuids").map(MessageUuid.apply),
      refusedUserMessageUuid = stringField(obj, "refused_user_message_uuid").map(MessageUuid.apply),
    )

  private def parseModelRefusalNoFallbackEvent(obj: js.Dynamic, raw: Json): SystemEvent.ModelRefusalNoFallback =
    SystemEvent.ModelRefusalNoFallback(
      originalModel = requiredString(obj, "original_model", raw),
      content = stringField(obj, "content").getOrElse(""),
      requestId = stringField(obj, "request_id"),
      apiRefusalCategory = stringField(obj, "api_refusal_category"),
      apiRefusalExplanation = stringField(obj, "api_refusal_explanation"),
      refusedUserMessageUuid = stringField(obj, "refused_user_message_uuid").map(MessageUuid.apply),
    )

  private def parseWorkerShuttingDownEvent(obj: js.Dynamic, raw: Json): SystemEvent.WorkerShuttingDown =
    SystemEvent.WorkerShuttingDown(
      reason = requiredString(obj, "reason", raw)
    )

  private def parseMcpServer(server: js.Dynamic): Option[McpServerStatus] =
    for
      name   <- stringField(server, "name")
      status <- stringField(server, "status")
    yield McpServerStatus(
      name = name,
      status = McpConnectionStatus.fromString(status),
      serverInfo = dynamicField(server, "server_info").flatMap { info =>
        for
          serverName <- stringField(info, "name")
          version    <- stringField(info, "version")
        yield McpServerInfo(serverName, version)
      },
      error = stringField(server, "error"),
      scope = stringField(server, "scope"),
      tools = optionDynamicArray(server, "tools").map { tools =>
        tools.flatMap { tool =>
          stringField(tool, "name").map { toolName =>
            McpToolInfo(
              name = toolName,
              description = stringField(tool, "description"),
              annotations = dynamicField(tool, "annotations").map { ann =>
                McpToolAnnotations(
                  readOnly = booleanField(ann, "readOnly"),
                  destructive = booleanField(ann, "destructive"),
                  openWorld = booleanField(ann, "openWorld"),
                )
              },
            )
          }
        }
      },
    )

  private def parsePlugin(plugin: js.Dynamic): Option[PluginInfo] =
    for
      name <- stringField(plugin, "name")
      path <- stringField(plugin, "path")
    yield PluginInfo(name, path)

  private def parseModelUsage(obj: js.Dynamic): ModelUsage =
    ModelUsage(
      inputTokens = intField(obj, "input_tokens").getOrElse(0),
      outputTokens = intField(obj, "output_tokens").getOrElse(0),
      cacheReadInputTokens = intField(obj, "cache_read_input_tokens").getOrElse(0),
      cacheCreationInputTokens = intField(obj, "cache_creation_input_tokens").getOrElse(0),
    )

  private def parseModelUsageFlexible(obj: js.Dynamic): ModelUsage =
    ModelUsage(
      inputTokens = intField(obj, "input_tokens").orElse(intField(obj, "total_tokens")).getOrElse(0),
      outputTokens = intField(obj, "output_tokens").getOrElse(0),
      cacheReadInputTokens = intField(obj, "cache_read_input_tokens").getOrElse(0),
      cacheCreationInputTokens = intField(obj, "cache_creation_input_tokens").getOrElse(0),
    )

  private def parseModelUsageMap(obj: js.Dynamic): Map[String, PerModelUsage] =
    obj.asInstanceOf[js.Dictionary[js.Dynamic]].toMap.map {
      case (modelId, usage) =>
        modelId -> PerModelUsage(
          inputTokens = intField(usage, "inputTokens").getOrElse(0),
          outputTokens = intField(usage, "outputTokens").getOrElse(0),
          cacheReadInputTokens = intField(usage, "cacheReadInputTokens").getOrElse(0),
          cacheCreationInputTokens = intField(usage, "cacheCreationInputTokens").getOrElse(0),
          webSearchRequests = intField(usage, "webSearchRequests").getOrElse(0),
          costUSD = doubleField(usage, "costUSD").getOrElse(0.0),
          contextWindow = intField(usage, "contextWindow").getOrElse(0),
          maxOutputTokens = intField(usage, "maxOutputTokens").getOrElse(0),
        )
    }

  private def parsePermissionDenial(denial: js.Dynamic): PermissionDenial =
    PermissionDenial(
      toolName = ToolName.fromString(stringField(denial, "tool_name").getOrElse("unknown")),
      toolUseId = ToolUseId(stringField(denial, "tool_use_id").getOrElse("")),
      toolInput = anyField(denial, "tool_input").map(jsToJson).getOrElse(Json.Null),
    )

  private def asDynamicObject(value: js.Any, raw: Json): js.Dynamic =
    if value == null || js.isUndefined(value) then
      throw MessageParseException("Expected SDK payload object but received null/undefined", raw)
    else
      val valueType = js.typeOf(value)
      if valueType == "object" || valueType == "function" then value.asInstanceOf[js.Dynamic]
      else throw MessageParseException(s"Expected SDK payload object but received $valueType", raw)

  private def envelopeContext(obj: js.Dynamic): EnvelopeContext =
    EnvelopeContext(
      uuid = stringField(obj, "uuid").map(MessageUuid.apply),
      sessionId = stringField(obj, "session_id").map(SessionId.apply),
      parentToolUseId = stringField(obj, "parent_tool_use_id").map(ToolUseId.apply),
    )

  private def unknownEnvelope(
    raw: Json,
    rawType: String,
    rawSubtype: Option[String],
    context: EnvelopeContext,
  ): UnknownEnvelope =
    UnknownEnvelope(
      raw = raw,
      rawType = rawType,
      rawSubtype = rawSubtype,
      uuid = context.uuid,
      sessionId = context.sessionId,
      parentToolUseId = context.parentToolUseId,
    )

  private def requiredUuid(obj: js.Dynamic, raw: Json): MessageUuid =
    MessageUuid(requiredString(obj, "uuid", raw))

  private def requiredSessionId(obj: js.Dynamic, raw: Json): SessionId =
    SessionId(requiredString(obj, "session_id", raw))

  private def requiredDynamic(
    obj: js.Dynamic,
    field: String,
    raw: Json,
    context: String,
  ): js.Dynamic =
    dynamicField(obj, field).getOrElse {
      throw MessageParseException(s"Missing required object field '$field' in $context", raw)
    }

  private def requiredArray(
    obj: js.Dynamic,
    field: String,
    raw: Json,
  ): js.Array[js.Any] =
    anyField(obj, field)
      .filter(js.Array.isArray)
      .map(_.asInstanceOf[js.Array[js.Any]])
      .getOrElse(throw MessageParseException(s"Missing required array field '$field'", raw))

  private def requiredString(
    obj: js.Dynamic,
    field: String,
    raw: Json,
  ): String =
    stringField(obj, field).getOrElse {
      throw MessageParseException(s"Missing required string field '$field'", raw)
    }

  private def anyField(obj: js.Dynamic, field: String): Option[js.Any] =
    val value = obj.selectDynamic(field).asInstanceOf[js.Any]
    if js.isUndefined(value) || value == null then None else Some(value)

  private def dynamicField(obj: js.Dynamic, field: String): Option[js.Dynamic] =
    anyField(obj, field).map(_.asInstanceOf[js.Dynamic])

  private def dynamicArrayField(obj: js.Dynamic, field: String): List[js.Dynamic] =
    optionDynamicArray(obj, field).getOrElse(Nil)

  private def optionDynamicArray(obj: js.Dynamic, field: String): Option[List[js.Dynamic]] =
    anyField(obj, field)
      .filter(js.Array.isArray)
      .map(_.asInstanceOf[js.Array[js.Dynamic]].toList)

  private def stringArrayField(obj: js.Dynamic, field: String): List[String] =
    optionStringArray(obj, field).getOrElse(Nil)

  private def optionStringArray(obj: js.Dynamic, field: String): Option[List[String]] =
    anyField(obj, field)
      .filter(js.Array.isArray)
      .map(_.asInstanceOf[js.Array[js.Any]].toList.collect {
        case value if js.typeOf(value) == "string" =>
          value.asInstanceOf[String]
      })

  private def stringField(obj: js.Dynamic, field: String): Option[String] =
    anyField(obj, field).flatMap {
      case value if js.typeOf(value) == "string" => Some(value.asInstanceOf[String])
      case _                                     => None
    }

  private def booleanField(obj: js.Dynamic, field: String): Option[Boolean] =
    anyField(obj, field).flatMap {
      case value if js.typeOf(value) == "boolean" => Some(value.asInstanceOf[Boolean])
      case _                                      => None
    }

  private def intField(obj: js.Dynamic, field: String): Option[Int] =
    anyField(obj, field).flatMap {
      case value if js.typeOf(value) == "number" => Some(value.asInstanceOf[Double].toInt)
      case value if js.typeOf(value) == "string" => Try(value.asInstanceOf[String].toInt).toOption
      case _                                     => None
    }

  private def longField(obj: js.Dynamic, field: String): Option[Long] =
    anyField(obj, field).flatMap {
      case value if js.typeOf(value) == "number" => Some(value.asInstanceOf[Double].toLong)
      case value if js.typeOf(value) == "string" => Try(value.asInstanceOf[String].toLong).toOption
      case _                                     => None
    }

  private def doubleField(obj: js.Dynamic, field: String): Option[Double] =
    anyField(obj, field).flatMap {
      case value if js.typeOf(value) == "number" => Some(value.asInstanceOf[Double])
      case value if js.typeOf(value) == "string" => Try(value.asInstanceOf[String].toDouble).toOption
      case _                                     => None
    }

  private def firstString(obj: js.Dynamic, fields: String*): Option[String] =
    fields.iterator.flatMap(field => stringField(obj, field)).toSeq.headOption

  private def firstBoolean(obj: js.Dynamic, fields: String*): Option[Boolean] =
    fields.iterator.flatMap(field => booleanField(obj, field)).toSeq.headOption

  private def jsToJson(value: js.Any): Json =
    if value == null || js.isUndefined(value) then Json.Null
    else
      val jsonStr = js.JSON.stringify(value)
      jsonStr.fromJson[Json].getOrElse(Json.Null)
end MessageConverter
