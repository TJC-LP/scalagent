package com.tjclp.scalagent.a2a

import munit.FunSuite
import zio.json.ast.Json
import com.tjclp.scalagent.TestFixtures
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{ApiMessageId, MessageUuid, SessionId, ToolUseId}

class A2AProgressSpec extends FunSuite:
  private val contextId = ContextId("ctx-progress")
  private val taskId    = TaskId("task-progress")

  private def progress(
    message: AgentMessage,
    state: A2AProgress.State = A2AProgress.State.empty,
  ): (A2AProgress.State, A2AMessage) =
    val (next, maybeMessage) = A2AProgress.statusMessage(message, contextId, taskId, state)
    (next, maybeMessage.getOrElse(fail("Expected progress message")))

  private def assistant(content: List[ContentBlock]): AgentMessage.Assistant =
    AgentMessage.Assistant(
      message = ApiAssistantMessage(
        id = ApiMessageId("msg-progress"),
        role = Role.Assistant,
        content = content,
        model = "claude-test",
        stopReason = None,
        stopSequence = None,
        usage = None,
      ),
      parentToolUseId = None,
      error = None,
      uuid = MessageUuid("uuid-progress"),
      sessionId = SessionId("session-progress"),
    )

  private def user(content: List[ContentBlock]): AgentMessage.User =
    AgentMessage.User(
      message = ApiUserMessage(role = Role.User, content = content),
      parentToolUseId = None,
      isSynthetic = true,
      toolUseResult = None,
      uuid = Some(MessageUuid("uuid-user-progress")),
      sessionId = SessionId("session-progress"),
    )

  private def fields(json: Json): Map[String, Json] =
    json.asObject.map(_.toMap).getOrElse(fail(s"Expected JSON object, got $json"))

  test("assistant text is forwarded and truncated"):
    val longText = "a" * 300
    val (_, msg) = progress(assistant(List(ContentBlock.Text(longText))))

    assertEquals(msg.parts.length, 1)
    msg.parts.head match
      case Part.Text(text, _, _, _) =>
        assertEquals(text.length, A2AProgress.MaxTextLength)
        assert(text.endsWith("..."))
      case other => fail(s"Expected text part, got $other")

  test("thinking blocks are forwarded as status text"):
    val (_, msg) = progress(assistant(List(ContentBlock.Thinking("checking the approach", Some("sig")))))

    assertEquals(msg.parts, List(Part.Text("Thinking: checking the approach")))

  test("tool use emits human text plus structured redacted data"):
    val toolUseId = ToolUseId("toolu-progress")
    val input = Json.Obj(
      "file_path"     -> Json.Str("/tmp/test.txt"),
      "authorization" -> Json.Str("Bearer secret"),
      "nested" -> Json.Obj(
        "x-api-key" -> Json.Str("secret-key")
      ),
    )
    val (_, msg) = progress(
      assistant(List(ContentBlock.ToolUse(toolUseId, ToolName.Read, input)))
    )

    assertEquals(msg.parts.head, Part.Text("Calling Read"))
    val dataPart = msg.parts.collectFirst { case Part.Data(data, _, _, _) => data }.getOrElse(fail("Missing data part"))
    val data     = fields(dataPart)
    assertEquals(data("kind"), Json.Str("tool_use"))
    assertEquals(data("id"), Json.Str(toolUseId.value))
    assertEquals(data("name"), Json.Str("Read"))

    val redactedInput = fields(data("input"))
    assertEquals(redactedInput("authorization"), Json.Str("[redacted]"))
    assertEquals(fields(redactedInput("nested"))("x-api-key"), Json.Str("[redacted]"))

  test("tool result uses known tool name and redacts text and data"):
    val toolUseId = ToolUseId("toolu-result")
    val (withToolName, _) = progress(
      assistant(List(ContentBlock.ToolUse(toolUseId, ToolName.Read, Json.Obj())))
    )
    val (_, msg) = progress(
      user(List(ContentBlock.ToolResult(toolUseId, "Authorization: Bearer secret\nfile contents", isError = false))),
      withToolName,
    )

    assertEquals(msg.parts.head, Part.Text("Read -> Authorization: [redacted]"))
    val data = fields(msg.parts.collectFirst { case Part.Data(data, _, _, _) => data }.getOrElse(fail("Missing data part")))
    assertEquals(data("kind"), Json.Str("tool_result"))
    assertEquals(data("name"), Json.Str("Read"))
    assertEquals(data("content"), Json.Str("Authorization: [redacted]\nfile contents"))

  test("text-only statuses dedupe but structured tool updates do not"):
    val first = A2AProgress.statusMessage(TestFixtures.toolUseSummary, contextId, taskId, A2AProgress.State.empty)
    assert(first._2.nonEmpty)

    val duplicate = A2AProgress.statusMessage(TestFixtures.toolUseSummary, contextId, taskId, first._1)
    assertEquals(duplicate._2, None)

    val toolUse = assistant(List(TestFixtures.toolUseBlock))
    val structuredFirst = A2AProgress.statusMessage(toolUse, contextId, taskId, duplicate._1)
    val structuredAgain = A2AProgress.statusMessage(toolUse, contextId, taskId, structuredFirst._1)
    assert(structuredFirst._2.nonEmpty)
    assert(structuredAgain._2.nonEmpty)
end A2AProgressSpec
