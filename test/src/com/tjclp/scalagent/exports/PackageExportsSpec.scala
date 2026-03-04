package com.tjclp.scalagent.exports

import munit.FunSuite
import com.tjclp.scalagent.*

class PackageExportsSpec extends FunSuite:

  test("SessionInfo and SessionMessage are available from package object"):
    val info: SessionInfo = SessionInfo(
      id = "session-123",
      name = Some("test-session"),
      model = Some("claude-sonnet-4-5"),
      createdAt = None,
      lastActiveAt = None,
      cwd = None,
      numTurns = Some(3),
      totalCostUsd = Some(0.01)
    )

    val message: SessionMessage = SessionMessage(
      role = "user",
      content = "hello",
      timestamp = None
    )

    assertEquals(info.id, "session-123")
    assertEquals(message.role, "user")
