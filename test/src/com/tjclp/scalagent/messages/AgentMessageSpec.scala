package com.tjclp.scalagent.messages

import munit.FunSuite
import zio.json._
import com.tjclp.scalagent.TestFixtures
import com.tjclp.scalagent.TestFixtures._

class AgentMessageSpec extends FunSuite:

  // ============================================
  // Message Variants
  // ============================================

  test("Assistant message contains API message"):
    val msg = assistantMessage
    assertEquals(msg.message.role, Role.Assistant)
    assertEquals(msg.sessionId, testSessionId)

  test("User message stores role correctly"):
    val msg = userMessage
    assertEquals(msg.message.role, Role.User)
    assertEquals(msg.isSynthetic, false)

  test("Synthetic user message is flagged"):
    val msg = syntheticUserMessage
    assertEquals(msg.isSynthetic, true)
    assert(msg.parentToolUseId.isDefined)
    assert(msg.toolUseResult.isDefined)

  test("Result message contains outcome"):
    val msg = resultSuccess
    msg.outcome match
      case _: ResultOutcome.Success => () // OK
      case _                        => fail("Expected Success outcome")

  // ============================================
  // Extension Methods - text
  // ============================================

  test("text extracts text from Assistant message"):
    val result = assistantMessage.text
    assertEquals(result, Some("Hello, I'm Claude!"))

  test("text extracts text from User message"):
    val result = userMessage.text
    assertEquals(result, Some("Hello Claude!"))

  test("text returns None for Result message"):
    val result = resultSuccess.text
    assertEquals(result, None)

  test("text returns None when no text blocks"):
    val msg = assistantMessageWithToolUse
    // Has both text and tool use - should return text
    val result = msg.text
    assertEquals(result, Some("Hello, I'm Claude!"))

  // ============================================
  // Extension Methods - toolCalls
  // ============================================

  test("toolCalls extracts tool use from Assistant message"):
    val calls = assistantMessageWithToolUse.toolCalls
    assertEquals(calls.size, 1)
    assertEquals(calls.head.name, "Read")

  test("toolCalls returns empty for message without tools"):
    val calls = assistantMessage.toolCalls
    assert(calls.isEmpty)

  test("toolCalls returns empty for User message"):
    val calls = userMessage.toolCalls
    assert(calls.isEmpty)

  // ============================================
  // Extension Methods - toolResults
  // ============================================

  test("toolResults extracts results from synthetic User message"):
    val results = syntheticUserMessage.toolResults
    assertEquals(results.size, 1)
    assertEquals(results.head.toolUseId, testToolUseId)

  test("toolResults returns empty for regular User message"):
    val results = userMessage.toolResults
    assert(results.isEmpty)

  // ============================================
  // Extension Methods - isResult
  // ============================================

  test("isResult returns true for Result message"):
    assert(resultSuccess.isResult)
    assert(resultError.isResult)

  test("isResult returns false for non-Result messages"):
    assert(!assistantMessage.isResult)
    assert(!userMessage.isResult)

  // ============================================
  // Extension Methods - isComplete
  // ============================================

  test("isComplete returns true for completed Result"):
    assert(resultSuccess.isComplete)
    assert(resultError.isComplete)

  test("isComplete returns false for non-Result messages"):
    assert(!assistantMessage.isComplete)
    assert(!userMessage.isComplete)

  // ============================================
  // Extension Methods - asResult
  // ============================================

  test("asResult returns Some for Result message"):
    val result = resultSuccess.asResult
    assert(result.isDefined)
    result.foreach { outcome =>
      assert(outcome.isSuccess)
    }

  test("asResult returns None for non-Result messages"):
    assertEquals(assistantMessage.asResult, None)
    assertEquals(userMessage.asResult, None)

  // ============================================
  // Extension Methods - isAssistant / isUser
  // ============================================

  test("isAssistant returns true for Assistant messages"):
    assert(assistantMessage.isAssistant)
    assert(assistantMessageWithToolUse.isAssistant)

  test("isAssistant returns false for non-Assistant messages"):
    assert(!userMessage.isAssistant)
    assert(!resultSuccess.isAssistant)

  test("isUser returns true for User messages"):
    assert(userMessage.isUser)
    assert(syntheticUserMessage.isUser)

  test("isUser returns false for non-User messages"):
    assert(!assistantMessage.isUser)
    assert(!resultSuccess.isUser)

  // ============================================
  // List Extension Methods
  // ============================================

  test("allText extracts text from all messages"):
    val text = simpleConversation.allText
    assert(text.contains("Hello Claude!"))
    assert(text.contains("Hello, I'm Claude!"))

  test("finalResult returns the result outcome"):
    val result = simpleConversation.finalResult
    assert(result.isDefined)
    result.foreach(r => assert(r.isSuccess))

  test("finalResult returns None when no result"):
    val messages = List(userMessage, assistantMessage)
    assertEquals(messages.finalResult, None)

  test("allToolCalls collects all tool calls"):
    val calls = toolUseConversation.allToolCalls
    assertEquals(calls.size, 1)

  test("allToolResults collects all tool results"):
    val results = toolUseConversation.allToolResults
    assertEquals(results.size, 1)

  test("assistantMessages filters to only assistant messages"):
    val assistants = simpleConversation.assistantMessages
    assertEquals(assistants.size, 1)
    assistants.foreach(a => assert(a.isInstanceOf[AgentMessage.Assistant]))

  test("isSuccess returns true for successful conversation"):
    assert(simpleConversation.isSuccess)

  test("isSuccess returns false for error conversation"):
    assert(!errorConversation.isSuccess)

  test("isSuccess returns false when no result"):
    val messages = List(userMessage, assistantMessage)
    assert(!messages.isSuccess)

  // ============================================
  // JSON Serialization
  // ============================================

  test("Assistant message JSON round-trip"):
    val msg: AgentMessage = assistantMessage
    val json = msg.toJson
    val parsed = json.fromJson[AgentMessage]
    parsed match
      case Right(AgentMessage.Assistant(_, _, _, msgUuid, msgSessionId)) =>
        assertEquals(msgUuid, testMessageUuid)
        assertEquals(msgSessionId, testSessionId)
      case other => fail(s"Expected Right(Assistant), got $other")

  test("Result message JSON round-trip"):
    val msg: AgentMessage = resultSuccess
    val json = msg.toJson
    val parsed = json.fromJson[AgentMessage]
    parsed match
      case Right(AgentMessage.Result(resultOutcome, _, _, _)) =>
        assert(resultOutcome.isSuccess)
      case other => fail(s"Expected Right(Result), got $other")

  // ============================================
  // Content Blocks
  // ============================================

  test("ContentBlock.Text stores text"):
    assertEquals(textBlock.text, "Hello, I'm Claude!")

  test("ContentBlock.ToolUse stores tool details"):
    assertEquals(toolUseBlock.id, testToolUseId)
    assertEquals(toolUseBlock.name, "Read")

  test("ContentBlock.ToolResult stores result details"):
    assertEquals(toolResultBlock.toolUseId, testToolUseId)
    assertEquals(toolResultBlock.content, "File contents here")
    assertEquals(toolResultBlock.isError, false)

  test("Error tool result has isError true"):
    assertEquals(errorToolResultBlock.isError, true)

  // ============================================
  // TaskNotification Tests
  // ============================================

  test("TaskNotification stores task details"):
    val msg = taskNotification
    assertEquals(msg.taskId, "task-123")
    assertEquals(msg.status, TaskStatus.Completed)
    assertEquals(msg.outputFile, "/tmp/task-output.txt")
    assertEquals(msg.sessionId, testSessionId)

  test("isTaskNotification returns true for TaskNotification"):
    assert(taskNotification.isTaskNotification)
    assert(taskNotificationFailed.isTaskNotification)

  test("isTaskNotification returns false for other messages"):
    assert(!assistantMessage.isTaskNotification)
    assert(!userMessage.isTaskNotification)
    assert(!resultSuccess.isTaskNotification)

  // ============================================
  // ToolUseSummary Tests
  // ============================================

  test("ToolUseSummary stores summary details"):
    val msg = toolUseSummary
    assertEquals(msg.summary, "Read 3 files successfully")
    assertEquals(msg.precedingToolUseIds.size, 2)
    assertEquals(msg.precedingToolUseIds.head, testToolUseId)

  test("isToolUseSummary returns true for ToolUseSummary"):
    assert(toolUseSummary.isToolUseSummary)

  test("isToolUseSummary returns false for other messages"):
    assert(!assistantMessage.isToolUseSummary)
    assert(!userMessage.isToolUseSummary)
    assert(!resultSuccess.isToolUseSummary)

  // ============================================
  // TaskStatus Tests
  // ============================================

  test("TaskStatus.fromString parses known values"):
    assertEquals(TaskStatus.fromString("completed"), TaskStatus.Completed)
    assertEquals(TaskStatus.fromString("failed"), TaskStatus.Failed)
    assertEquals(TaskStatus.fromString("stopped"), TaskStatus.Stopped)

  test("TaskStatus.fromString returns Custom for unknown values"):
    TaskStatus.fromString("custom_status") match
      case TaskStatus.Custom(v) => assertEquals(v, "custom_status")
      case other                => fail(s"Expected Custom, got $other")

  test("TaskStatus.toRaw produces correct strings"):
    assertEquals(TaskStatus.Completed.toRaw, "completed")
    assertEquals(TaskStatus.Failed.toRaw, "failed")
    assertEquals(TaskStatus.Stopped.toRaw, "stopped")
    assertEquals(TaskStatus.Custom("xyz").toRaw, "xyz")

  // ============================================
  // HookOutcome Tests
  // ============================================

  test("HookOutcome.fromString parses known values"):
    assertEquals(HookOutcome.fromString("success"), HookOutcome.Success)
    assertEquals(HookOutcome.fromString("error"), HookOutcome.Error)
    assertEquals(HookOutcome.fromString("cancelled"), HookOutcome.Cancelled)

  test("HookOutcome.fromString returns Custom for unknown values"):
    HookOutcome.fromString("custom_outcome") match
      case HookOutcome.Custom(v) => assertEquals(v, "custom_outcome")
      case other                 => fail(s"Expected Custom, got $other")

  test("HookOutcome.toRaw produces correct strings"):
    assertEquals(HookOutcome.Success.toRaw, "success")
    assertEquals(HookOutcome.Error.toRaw, "error")
    assertEquals(HookOutcome.Cancelled.toRaw, "cancelled")
    assertEquals(HookOutcome.Custom("xyz").toRaw, "xyz")

  // ============================================
  // List Extension Methods - New Types
  // ============================================

  test("taskNotifications extracts task notifications from list"):
    val messages = List(userMessage, taskNotification, assistantMessage, taskNotificationFailed)
    val notifications = messages.taskNotifications
    assertEquals(notifications.size, 2)
    assertEquals(notifications.head.taskId, "task-123")
    assertEquals(notifications(1).taskId, "task-456")

  test("taskNotifications returns empty list when none present"):
    val notifications = simpleConversation.taskNotifications
    assert(notifications.isEmpty)

  test("toolUseSummaries extracts tool use summaries from list"):
    val messages = List(userMessage, toolUseSummary, assistantMessage)
    val summaries = messages.toolUseSummaries
    assertEquals(summaries.size, 1)
    assertEquals(summaries.head.summary, "Read 3 files successfully")
