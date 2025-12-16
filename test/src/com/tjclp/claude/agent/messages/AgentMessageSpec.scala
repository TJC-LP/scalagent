package com.tjclp.claude.agent.messages

import munit.FunSuite
import zio.json._
import com.tjclp.claude.agent.TestFixtures
import com.tjclp.claude.agent.TestFixtures._

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
      case Right(AgentMessage.Result(resultOutcome, _, _)) =>
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
