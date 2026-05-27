package com.tjclp.scalagent.core

import zio.*
import zio.json.*
import zio.json.ast.Json

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

  test("callback keeps subagent context at the end and omits empty taskDescription"):
    var lines = List.empty[String]
    val logger = TraceLogger.callback(line => lines = lines :+ line)

    runUIO(logger.logEvent(AgentEvent.TextDelta("child", Some(SubagentContext("reviewer")))))

    val json = lines.head.fromJson[Json].getOrElse(fail(s"Invalid JSON: ${lines.head}"))
    json match
      case obj: Json.Obj =>
        assertEquals(obj.fields.map(_._1).toList, List("type", "value", "subagentContext"))
        obj.get("subagentContext") match
          case Some(ctx: Json.Obj) =>
            assertEquals(ctx.get("subagentType"), Some(Json.Str("reviewer")))
            assertEquals(ctx.get("taskDescription"), None)
          case other => fail(s"Expected subagent context object, got $other")
      case other => fail(s"Expected object, got $other")
