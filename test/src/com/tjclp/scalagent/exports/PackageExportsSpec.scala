package com.tjclp.scalagent.exports

import munit.FunSuite
import com.tjclp.scalagent.*

class PackageExportsSpec extends FunSuite:

  test("SessionInfo and SessionMessage are available from package object"):
    val info: SessionInfo = SessionInfo(
      sessionId = SessionId("session-123"),
      summary = "test-session",
      lastModified = 123L,
      fileSize = 10L,
      customTitle = Some("custom"),
      firstPrompt = Some("hello"),
      gitBranch = Some("main"),
      cwd = Some("/tmp")
    )

    val message: SessionMessage = SessionMessage(
      messageType = "user",
      uuid = "msg-1",
      sessionId = SessionId("session-123"),
      message = "hello",
      parentToolUseId = None
    )

    assertEquals(info.sessionId.value, "session-123")
    assertEquals(message.messageType, "user")

  test("SessionUuid is available from package object"):
    val uuid = SessionUuid("123e4567-e89b-12d3-a456-426614174000")
    assert(uuid.isRight)

  test("core DSL types are available from package object"):
    val policy: ExecutionPolicy = ExecutionPolicy.simple(0.5, 3)
    val budget: Budget = Budget.usd(1.0)
    val summary: RunSummary = RunSummary(
      durationMs = 100L,
      numTurns = 1,
      costUsd = 0.01,
      isSuccess = true
    )

    val event: AgentEvent = AgentEvent.Completed(summary)
    assertEquals(policy.maxTurns, Some(3))
    assertEquals(budget.toUsd, Some(1.0))
    assert(event.isInstanceOf[AgentEvent.Completed])

  test("ClaudeInterpreter is available from package object"):
    val interpreter = ClaudeInterpreter
    assert(interpreter != null)
