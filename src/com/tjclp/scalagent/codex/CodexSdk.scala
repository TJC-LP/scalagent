package com.tjclp.scalagent.codex

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import com.tjclp.scalagent.streaming.AsyncGenerator

// ============================================================================
// Codex SDK JS facades — @js.native bindings to @openai/codex-sdk
// ============================================================================

/** Main Codex client. Creates threads for agent conversations. */
@js.native
@JSImport("@openai/codex-sdk", "Codex")
class JsCodex(options: js.UndefOr[js.Dynamic] = js.undefined) extends js.Object:
  def startThread(options: js.UndefOr[js.Dynamic] = js.undefined): JsThread              = js.native
  def resumeThread(id: String, options: js.UndefOr[js.Dynamic] = js.undefined): JsThread = js.native

/** A conversation thread. Supports multiple turns with history. */
@js.native
trait JsThread extends js.Object:
  def id: String | Null = js.native
  def runStreamed(input: js.Any, turnOptions: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsStreamedTurn] =
    js.native
  def run(input: js.Any, turnOptions: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[JsTurn] = js.native

/** Result of runStreamed — contains an async generator of events. */
@js.native
trait JsStreamedTurn extends js.Object:
  val events: AsyncGenerator[JsThreadEvent, Unit, Unit] = js.native

/** Result of run — buffered turn with all items and final response. */
@js.native
trait JsTurn extends js.Object:
  val items: js.Array[JsThreadItem] = js.native
  val finalResponse: String         = js.native
  val usage: js.UndefOr[JsUsage]    = js.native

/** Token usage for a completed turn. */
@js.native
trait JsUsage extends js.Object:
  val input_tokens: Double        = js.native
  val cached_input_tokens: Double = js.native
  val output_tokens: Double       = js.native

// ============================================================================
// ThreadEvent — top-level JSONL events from codex exec
// ============================================================================

/** Base trait for all thread events. Discriminated by `type` field. */
@js.native
trait JsThreadEvent extends js.Object:
  val `type`: String = js.native

/** Event with a nested ThreadItem (item.started, item.updated, item.completed). */
@js.native
trait JsItemEvent extends JsThreadEvent:
  val item: JsThreadItem = js.native

/** turn.completed event with usage data. */
@js.native
trait JsTurnCompletedEvent extends JsThreadEvent:
  val usage: JsUsage = js.native

/** turn.failed event with error. */
@js.native
trait JsTurnFailedEvent extends JsThreadEvent:
  val error: JsThreadError = js.native

/** thread.started event with thread ID. */
@js.native
trait JsThreadStartedEvent extends JsThreadEvent:
  val thread_id: String = js.native

/** error event with message. */
@js.native
trait JsThreadErrorEvent extends JsThreadEvent:
  val message: String = js.native

/** Error object in turn.failed. */
@js.native
trait JsThreadError extends js.Object:
  val message: String = js.native

// ============================================================================
// ThreadItem — individual items within events
// ============================================================================

/** Base trait for all thread items. Discriminated by `type` field. */
@js.native
trait JsThreadItem extends js.Object:
  val id: String     = js.native
  val `type`: String = js.native

/** agent_message item. */
@js.native
trait JsAgentMessageItem extends JsThreadItem:
  val text: String = js.native

/** reasoning item. */
@js.native
trait JsReasoningItem extends JsThreadItem:
  val text: String = js.native

/** command_execution item. */
@js.native
trait JsCommandExecutionItem extends JsThreadItem:
  val command: String               = js.native
  val aggregated_output: String     = js.native
  val exit_code: js.UndefOr[Double] = js.native
  val status: String                = js.native

/** file_change item. */
@js.native
trait JsFileChangeItem extends JsThreadItem:
  val changes: js.Array[JsFileUpdateChange] = js.native
  val status: String                        = js.native

/** Individual file change within a FileChangeItem. */
@js.native
trait JsFileUpdateChange extends js.Object:
  val path: String = js.native
  val kind: String = js.native

/** mcp_tool_call item. */
@js.native
trait JsMcpToolCallItem extends JsThreadItem:
  val server: String                      = js.native
  val tool: String                        = js.native
  val arguments: js.Any                   = js.native
  val result: js.UndefOr[JsMcpToolResult] = js.native
  val error: js.UndefOr[JsThreadError]    = js.native
  val status: String                      = js.native

/** MCP tool call result. */
@js.native
trait JsMcpToolResult extends js.Object:
  val content: js.Array[js.Dynamic] = js.native
  val structured_content: js.Any    = js.native

/** web_search item. */
@js.native
trait JsWebSearchItem extends JsThreadItem:
  val query: String = js.native

/** error item. */
@js.native
trait JsErrorItem extends JsThreadItem:
  val message: String = js.native

/** todo_list item. */
@js.native
trait JsTodoListItem extends JsThreadItem:
  val items: js.Array[JsTodoItem] = js.native

/** Individual todo entry. */
@js.native
trait JsTodoItem extends js.Object:
  val text: String       = js.native
  val completed: Boolean = js.native
