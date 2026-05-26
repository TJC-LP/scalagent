package com.tjclp.scalagent.core

import zio.*

class TraceLoggerSpec extends munit.FunSuite:
  private val runtime = Runtime.default

  private def runUIO[A](task: UIO[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(task).getOrThrowFiberFailure()
    }

  test("callbackZIO logs events effectfully without unsafe nesting"):
    val ref = runUIO(Ref.make(List.empty[String]))
    val logger = TraceLogger.callbackZIO(line => ref.update(_ :+ line))

    runUIO(logger.logEvent(AgentEvent.Status("working")))

    val lines = runUIO(ref.get)
    assertEquals(lines.size, 1)
    assert(lines.head.contains("working"))

  test("callback delegates to callbackZIO semantics"):
    var lines = List.empty[String]
    val logger = TraceLogger.callback(line => lines = lines :+ line)

    runUIO(logger.logEvent(AgentEvent.TextDelta("hello")))

    assertEquals(lines.size, 1)
    assert(lines.head.contains("hello"))
