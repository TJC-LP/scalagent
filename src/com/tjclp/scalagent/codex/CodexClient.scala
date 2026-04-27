package com.tjclp.scalagent.codex

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import zio.stream.*
import zio.json.ast.Json
import com.tjclp.scalagent.streaming.AsyncIteratorOps

// ============================================================================
// Scala ADTs mirroring Codex SDK types
// ============================================================================

/** Token usage for a completed turn. */
final case class CodexUsage(
  inputTokens: Int,
  cachedInputTokens: Int,
  outputTokens: Int)

/** A completed turn result. */
final case class CodexTurn(
  items: List[CodexItem],
  finalResponse: String,
  usage: Option[CodexUsage])

/** File change entry. */
final case class FileUpdate(path: String, kind: String)

/** Todo entry. */
final case class TodoEntry(text: String, completed: Boolean)

/** Normalized Codex thread item. */
enum CodexItem:
  case AgentMessage(id: String, text: String)
  case Reasoning(id: String, text: String)
  case CommandExecution(
    id: String,
    command: String,
    output: String,
    exitCode: Option[Int],
    status: String)
  case FileChange(
    id: String,
    changes: List[FileUpdate],
    status: String)
  case McpToolCall(
    id: String,
    server: String,
    tool: String,
    args: Json,
    result: Option[Json],
    error: Option[String],
    status: String)
  case WebSearch(id: String, query: String)
  case TodoList(id: String, items: List[TodoEntry])
  case ItemError(id: String, message: String)
end CodexItem

/** Normalized Codex thread event. */
enum CodexEvent:
  case ThreadStarted(threadId: String)
  case TurnStarted
  case TurnCompleted(usage: CodexUsage)
  case TurnFailed(message: String)
  case ItemStarted(item: CodexItem)
  case ItemUpdated(item: CodexItem)
  case ItemCompleted(item: CodexItem)
  case Error(message: String)

// ============================================================================
// Scala client traits
// ============================================================================

/** Pure Scala interface for the Codex client. */
trait CodexClient:
  def startThread(options: CodexThreadOptions = CodexThreadOptions.default): CodexThread
  def resumeThread(id: String, options: CodexThreadOptions = CodexThreadOptions.default): CodexThread

/** A Codex conversation thread. */
trait CodexThread:
  def id: Option[String]
  def runStreamed(input: String, options: CodexTurnOptions): ZStream[Any, Throwable, CodexEvent]
  def runStreamed(input: Seq[CodexInputItem], options: CodexTurnOptions): ZStream[Any, Throwable, CodexEvent]
  def run(input: String, options: CodexTurnOptions): Task[CodexTurn]
  def run(input: Seq[CodexInputItem], options: CodexTurnOptions): Task[CodexTurn]

  def runStreamed(input: CodexInput, options: CodexTurnOptions): ZStream[Any, Throwable, CodexEvent] =
    input match
      case text: String  => runStreamed(text, options)
      case items: Seq[?] => runStreamed(items.asInstanceOf[Seq[CodexInputItem]], options)

  def run(input: CodexInput, options: CodexTurnOptions): Task[CodexTurn] =
    input match
      case text: String  => run(text, options)
      case items: Seq[?] => run(items.asInstanceOf[Seq[CodexInputItem]], options)

  def runStreamed(input: String): ZStream[Any, Throwable, CodexEvent] =
    runStreamed(input, CodexTurnOptions.default)

  def runStreamed(input: Seq[CodexInputItem]): ZStream[Any, Throwable, CodexEvent] =
    runStreamed(input, CodexTurnOptions.default)

  def runStreamed(input: CodexInput): ZStream[Any, Throwable, CodexEvent] =
    runStreamed(input, CodexTurnOptions.default)

  def run(input: String): Task[CodexTurn] =
    run(input, CodexTurnOptions.default)

  def run(input: Seq[CodexInputItem]): Task[CodexTurn] =
    run(input, CodexTurnOptions.default)

  def run(input: CodexInput): Task[CodexTurn] =
    run(input, CodexTurnOptions.default)
end CodexThread

object CodexClient:
  /** Create a CodexClient from options. */
  def create(options: CodexClientOptions = CodexClientOptions.default): CodexClient =
    CodexClientLive(new JsCodex(options.toRaw))

// ============================================================================
// Live implementations
// ============================================================================

private final class CodexClientLive(jsCodex: JsCodex) extends CodexClient:
  def startThread(options: CodexThreadOptions): CodexThread =
    CodexThreadLive(jsCodex.startThread(options.toRaw))

  def resumeThread(id: String, options: CodexThreadOptions): CodexThread =
    CodexThreadLive(jsCodex.resumeThread(id, options.toRaw))

private final class CodexThreadLive(jsThread: JsThread) extends CodexThread:
  def id: Option[String] =
    val raw = jsThread.id
    if raw == null then None else Some(raw.asInstanceOf[String])

  def runStreamed(input: String, options: CodexTurnOptions): ZStream[Any, Throwable, CodexEvent] =
    runStreamedRaw(input, options)

  def runStreamed(input: Seq[CodexInputItem], options: CodexTurnOptions): ZStream[Any, Throwable, CodexEvent] =
    runStreamedRaw(input.map(_.toRaw).toJSArray, options)

  def run(input: String, options: CodexTurnOptions): Task[CodexTurn] =
    runRaw(input, options)

  def run(input: Seq[CodexInputItem], options: CodexTurnOptions): Task[CodexTurn] =
    runRaw(input.map(_.toRaw).toJSArray, options)

  private def runStreamedRaw(input: js.Any, options: CodexTurnOptions): ZStream[Any, Throwable, CodexEvent] =
    ZStream.unwrap {
      ZIO.fromPromiseJS(jsThread.runStreamed(input, options.toRaw)).map { streamedTurn =>
        AsyncIteratorOps
          .toZStreamWithReturn(streamedTurn.events)
          .map(parseEvent)
      }
    }

  private def runRaw(input: js.Any, options: CodexTurnOptions): Task[CodexTurn] =
    ZIO.fromPromiseJS(jsThread.run(input, options.toRaw)).map(parseTurn)

  private def parseEvent(raw: JsThreadEvent): CodexEvent =
    raw.`type` match
      case "thread.started" =>
        val ev = raw.asInstanceOf[JsThreadStartedEvent]
        CodexEvent.ThreadStarted(ev.thread_id)
      case "turn.started" =>
        CodexEvent.TurnStarted
      case "turn.completed" =>
        val ev = raw.asInstanceOf[JsTurnCompletedEvent]
        CodexEvent.TurnCompleted(parseUsage(ev.usage))
      case "turn.failed" =>
        val ev = raw.asInstanceOf[JsTurnFailedEvent]
        CodexEvent.TurnFailed(ev.error.message)
      case "item.started" =>
        val ev = raw.asInstanceOf[JsItemEvent]
        CodexEvent.ItemStarted(parseItem(ev.item))
      case "item.updated" =>
        val ev = raw.asInstanceOf[JsItemEvent]
        CodexEvent.ItemUpdated(parseItem(ev.item))
      case "item.completed" =>
        val ev = raw.asInstanceOf[JsItemEvent]
        CodexEvent.ItemCompleted(parseItem(ev.item))
      case "error" =>
        val ev = raw.asInstanceOf[JsThreadErrorEvent]
        CodexEvent.Error(ev.message)
      case other =>
        CodexEvent.Error(s"Unknown event type: $other")

  private def parseItem(raw: JsThreadItem): CodexItem =
    raw.`type` match
      case "agent_message" =>
        val item = raw.asInstanceOf[JsAgentMessageItem]
        CodexItem.AgentMessage(item.id, item.text)
      case "reasoning" =>
        val item = raw.asInstanceOf[JsReasoningItem]
        CodexItem.Reasoning(item.id, item.text)
      case "command_execution" =>
        val item = raw.asInstanceOf[JsCommandExecutionItem]
        CodexItem.CommandExecution(
          item.id,
          item.command,
          item.aggregated_output,
          item.exit_code.toOption.map(_.toInt), // POSIX exit codes are 0-255; Double.toInt is safe
          item.status,
        )
      case "file_change" =>
        val item    = raw.asInstanceOf[JsFileChangeItem]
        val changes = item.changes.toList.map(c => FileUpdate(c.path, c.kind))
        CodexItem.FileChange(item.id, changes, item.status)
      case "mcp_tool_call" =>
        val item   = raw.asInstanceOf[JsMcpToolCallItem]
        val args   = jsToJson(item.arguments)
        val result = item.result.toOption.map(r => jsToJson(r.asInstanceOf[js.Any]))
        val error  = item.error.toOption.map(_.message)
        CodexItem.McpToolCall(item.id, item.server, item.tool, args, result, error, item.status)
      case "web_search" =>
        val item = raw.asInstanceOf[JsWebSearchItem]
        CodexItem.WebSearch(item.id, item.query)
      case "todo_list" =>
        val item    = raw.asInstanceOf[JsTodoListItem]
        val entries = item.items.toList.map(t => TodoEntry(t.text, t.completed))
        CodexItem.TodoList(item.id, entries)
      case "error" =>
        val item = raw.asInstanceOf[JsErrorItem]
        CodexItem.ItemError(item.id, item.message)
      case other =>
        CodexItem.ItemError(raw.id, s"Unknown item type: $other")

  private def parseUsage(raw: JsUsage): CodexUsage =
    CodexUsage(
      inputTokens = raw.input_tokens.toInt,
      cachedInputTokens = raw.cached_input_tokens.toInt,
      outputTokens = raw.output_tokens.toInt,
    )

  private def parseTurn(raw: JsTurn): CodexTurn =
    val items = raw.items.toList.map(parseItem)
    val usage = raw.usage.toOption.map(parseUsage)
    CodexTurn(items, raw.finalResponse, usage)

  private def jsToJson(value: js.Any): Json =
    if value == null || js.isUndefined(value) then Json.Null
    else
      try
        val str = js.JSON.stringify(value)
        if str == null then Json.Null
        else zio.json.ast.Json.decoder.decodeJson(str).getOrElse(Json.Str(str))
      catch
        case e: Throwable =>
          scala.Console.err.println(s"[CodexClient] jsToJson failed: ${e.getMessage}")
          Json.Null
end CodexThreadLive
