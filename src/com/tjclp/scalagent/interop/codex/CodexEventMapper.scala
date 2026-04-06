package com.tjclp.scalagent.interop.codex

import zio.json.ast.Json
import com.tjclp.scalagent.core.{AgentEvent, RunSummary}
import com.tjclp.scalagent.codex.{CodexEvent, CodexItem, CodexUsage}

/** Maps Codex events to the provider-independent AgentEvent ADT.
  *
  * Follows the same pattern as `interop/claude/EventMapper`: each provider
  * event maps to zero or more `AgentEvent` values. Provider-specific data
  * passes through as `AgentEvent.Native`.
  */
object CodexEventMapper:

  /** State tracked across events to build the final RunSummary. */
  final class MapperState:
    var lastAgentMessage: Option[String] = None
    var startTimeMs: Long = System.currentTimeMillis()

  def createState(): MapperState = MapperState()

  /** Map a single CodexEvent to normalized AgentEvents. */
  def mapEvent(event: CodexEvent, state: MapperState): List[AgentEvent] =
    event match
      case CodexEvent.ThreadStarted(threadId) =>
        List(AgentEvent.Native("thread.started", Json.Obj("thread_id" -> Json.Str(threadId))))

      case CodexEvent.TurnStarted =>
        state.startTimeMs = System.currentTimeMillis()
        List(AgentEvent.Status("turn_started"))

      case CodexEvent.TurnCompleted(usage) =>
        val durationMs = System.currentTimeMillis() - state.startTimeMs
        val summary = RunSummary(
          durationMs = durationMs,
          numTurns = 1,
          costUsd = 0.0, // Codex doesn't report USD cost
          isSuccess = true,
          resultText = state.lastAgentMessage,
          stopReason = Some("turn.completed")
        )
        // Also emit token usage as Native for observability
        val usageEvent = AgentEvent.Native(
          "codex.usage",
          Json.Obj(
            "input_tokens" -> Json.Num(usage.inputTokens),
            "cached_input_tokens" -> Json.Num(usage.cachedInputTokens),
            "output_tokens" -> Json.Num(usage.outputTokens)
          )
        )
        List(usageEvent, AgentEvent.Completed(summary))

      case CodexEvent.TurnFailed(message) =>
        val durationMs = System.currentTimeMillis() - state.startTimeMs
        val summary = RunSummary(
          durationMs = durationMs,
          numTurns = 1,
          costUsd = 0.0,
          isSuccess = false,
          resultText = Some(message),
          stopReason = Some("turn.failed")
        )
        List(AgentEvent.Completed(summary))

      case CodexEvent.ItemStarted(item) => mapItemStarted(item, state)
      case CodexEvent.ItemUpdated(item) => mapItemUpdated(item, state)
      case CodexEvent.ItemCompleted(item) => mapItemCompleted(item, state)

      case CodexEvent.Error(message) =>
        List(AgentEvent.Native("error", Json.Obj("message" -> Json.Str(message))))

  private def mapItemStarted(item: CodexItem, state: MapperState): List[AgentEvent] =
    item match
      case CodexItem.AgentMessage(_, text) =>
        state.lastAgentMessage = Some(text)
        List(AgentEvent.TextDelta(text))

      case CodexItem.CommandExecution(_, command, _, _, _) =>
        List(AgentEvent.ToolCall("command", Json.Obj("command" -> Json.Str(command))))

      case CodexItem.McpToolCall(_, server, tool, args, _, _, _) =>
        val name = s"mcp:$server:$tool"
        List(AgentEvent.ToolCall(name, args))

      case CodexItem.WebSearch(_, query) =>
        List(AgentEvent.ToolCall("web_search", Json.Obj("query" -> Json.Str(query))))

      case CodexItem.Reasoning(_, text) =>
        List(AgentEvent.Native("reasoning", Json.Obj("text" -> Json.Str(text))))

      case CodexItem.TodoList(_, items) =>
        val summary = items.map(t => s"${if t.completed then "[x]" else "[ ]"} ${t.text}").mkString("; ")
        List(AgentEvent.Status(s"todo: $summary"))

      case CodexItem.FileChange(_, changes, _) =>
        val changesJson = Json.Arr(changes.map(c =>
          Json.Obj("path" -> Json.Str(c.path), "kind" -> Json.Str(c.kind))
        )*)
        List(AgentEvent.ToolCall("file_change", Json.Obj("changes" -> changesJson)))

      case CodexItem.ItemError(_, message) =>
        List(AgentEvent.Status(s"error: $message"))

  private def mapItemUpdated(item: CodexItem, state: MapperState): List[AgentEvent] =
    item match
      case CodexItem.AgentMessage(_, text) =>
        state.lastAgentMessage = Some(text)
        List(AgentEvent.TextDelta(text))

      case CodexItem.TodoList(_, items) =>
        val summary = items.map(t => s"${if t.completed then "[x]" else "[ ]"} ${t.text}").mkString("; ")
        List(AgentEvent.Status(s"todo: $summary"))

      case CodexItem.CommandExecution(_, _, output, _, _) =>
        List(AgentEvent.Status(s"command: $output"))

      case _ => Nil // Most updates are intermediate; ignore silently

  private def mapItemCompleted(item: CodexItem, state: MapperState): List[AgentEvent] =
    item match
      case CodexItem.AgentMessage(_, text) =>
        state.lastAgentMessage = Some(text)
        Nil // Already streamed via started/updated

      case CodexItem.CommandExecution(_, _, output, exitCode, status) =>
        val isError = status == "failed"
        val resultJson = Json.Obj(
          "output" -> Json.Str(output),
          "exit_code" -> exitCode.fold(Json.Null: Json)(c => Json.Num(c))
        )
        List(AgentEvent.ToolResult("command", resultJson, isError))

      case CodexItem.McpToolCall(_, server, tool, _, result, error, status) =>
        val name = s"mcp:$server:$tool"
        val isError = status == "failed"
        val resultJson = error match
          case Some(msg) => Json.Obj("error" -> Json.Str(msg))
          case None => result.getOrElse(Json.Null)
        List(AgentEvent.ToolResult(name, resultJson, isError))

      case CodexItem.WebSearch(_, query) =>
        List(AgentEvent.ToolResult("web_search", Json.Obj("query" -> Json.Str(query)), false))

      case CodexItem.FileChange(_, changes, status) =>
        val isError = status == "failed"
        val changesJson = Json.Arr(changes.map(c =>
          Json.Obj("path" -> Json.Str(c.path), "kind" -> Json.Str(c.kind))
        )*)
        List(AgentEvent.ToolResult("file_change", changesJson, isError))

      case CodexItem.ItemError(_, message) =>
        List(AgentEvent.Status(s"error: $message"))

      case _ => Nil
