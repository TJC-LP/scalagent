package com.tjclp.scalagent.interop.claude

import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.core.{AgentEvent, RunSummary, SubagentContext}
import com.tjclp.scalagent.messages.*

/**
 * Pure mapping from provider-specific AgentMessage to normalized AgentEvent.
 *
 * Each AgentMessage may produce zero or more AgentEvents (e.g., an Assistant
 * message with text + tool use produces two events). Provider-specific messages
 * that don't normalize pass through as `AgentEvent.Native` with a tag and
 * lossless JSON payload.
 *
 * Public so external Claude consumers can normalize the same way the in-tree
 * `ClaudeInterpreter` does (parallel to the public `CodexEventMapper`). Use
 * the package-level alias `com.tjclp.scalagent.ClaudeEventMapper` from outside
 * `interop.claude`.
 */
object EventMapper:

  def mapMessage(msg: AgentMessage): List[AgentEvent] = msg match
    case assistant: AgentMessage.Assistant =>
      assistant.message.content.flatMap(
        mapContentBlock(_, subagentContext(assistant.subagentType, assistant.taskDescription))
      )

    case user: AgentMessage.User if user.isSynthetic =>
      user.message.content.collect {
        case ContentBlock.ToolResult(toolUseId, content, isError) =>
          AgentEvent.ToolResult(
            name = toolUseId.value,
            value = Json.Str(content),
            isError = isError,
          )
      }

    case AgentMessage.Result(outcome, _, _, _) =>
      List(AgentEvent.Completed(toRunSummary(outcome)))

    case AgentMessage.StreamEvent(event, _, _, _) =>
      event.delta match
        case Some(StreamDelta.TextDelta(t)) => List(AgentEvent.TextDelta(t))
        case _                              => List(nativeEvent("stream_event", msg))

    case AgentMessage.ToolProgress(_, toolName, _, elapsed, _, _, _) =>
      List(AgentEvent.Status(s"tool:${toolName.raw} (${elapsed}s)"))

    case AgentMessage.TaskStarted(taskId, description, _, _, _, _, _, _) =>
      List(AgentEvent.DelegationStarted(label = description, childId = taskId))

    case AgentMessage.TaskNotification(taskId, status, _, _, _, _, _, _) =>
      List(AgentEvent.DelegationFinished(childId = taskId, status = status.toRaw))

    case AgentMessage.TaskProgress(taskId, progress, _, _, _, _, _) =>
      List(AgentEvent.Status(s"task:$taskId: $progress"))

    case AgentMessage.RateLimitEvent(retryAfterMs, _, status, _, _, _, _, _, _, _, _, _, _) =>
      List(nativeEvent("rate_limit", msg))

    case AgentMessage.ApiRetry(attempt, maxRetries, _, _, _, _, _) =>
      List(nativeEvent("api_retry", msg))

    case _ =>
      List(nativeEvent(nativeTag(msg), msg))

  private def mapContentBlock(
    block: ContentBlock,
    subagentContext: Option[SubagentContext],
  ): List[AgentEvent] = block match
    case ContentBlock.Text(text) =>
      List(AgentEvent.TextDelta(text, subagentContext))
    case ContentBlock.ToolUse(_, name, input) =>
      List(AgentEvent.ToolCall(name.raw, input))
    case ContentBlock.ToolResult(toolUseId, content, isError) =>
      List(AgentEvent.ToolResult(toolUseId.value, Json.Str(content), isError))
    case ContentBlock.Thinking(_, _) =>
      Nil // Internal reasoning, not exposed as normalized event
    case ContentBlock.Image(_, _) =>
      List(AgentEvent.Native("image", Json.Null))
    case ContentBlock.Unknown(envelope) =>
      List(AgentEvent.Native("content_block.unknown", Json.Null))

  private def subagentContext(
    subagentType: Option[String],
    taskDescription: Option[String],
  ): Option[SubagentContext] =
    subagentType.map(SubagentContext(_, taskDescription))

  private def toRunSummary(outcome: ResultOutcome): RunSummary =
    RunSummary(
      durationMs = outcome.durationMs,
      numTurns = outcome.numTurns,
      costUsd = outcome.totalCostUsd,
      isSuccess = outcome.isSuccess,
      resultText = outcome.resultText,
      stopReason = outcome.stopReason.map(_.toRaw),
    )

  private def nativeTag(msg: AgentMessage): String = msg match
    case _: AgentMessage.User                => "user"
    case _: AgentMessage.UserReplay          => "user_replay"
    case _: AgentMessage.System              => "system"
    case _: AgentMessage.AuthStatus          => "auth_status"
    case _: AgentMessage.PromptSuggestion    => "prompt_suggestion"
    case _: AgentMessage.LocalCommandOutput  => "local_command_output"
    case _: AgentMessage.ElicitationComplete => "elicitation_complete"
    case _: AgentMessage.ToolUseSummary      => "tool_use_summary"
    case _: AgentMessage.BridgeMetadata      => "bridge_metadata"
    case _: AgentMessage.Unknown             => "unknown"
    case _                                   => "other"

  private def nativeEvent(tag: String, msg: AgentMessage): AgentEvent.Native =
    AgentEvent.Native(tag, msg.toJsonAST.getOrElse(Json.Str(msg.toString)))
end EventMapper
