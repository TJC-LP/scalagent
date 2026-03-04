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
