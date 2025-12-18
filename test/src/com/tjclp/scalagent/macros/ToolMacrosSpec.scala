package com.tjclp.scalagent.macros

import munit.FunSuite
import zio.*
import com.tjclp.scalagent.tools.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ToolMacrosSpec extends FunSuite:

  object TestTools:
    @Tool("plain_string", "Return a plain string")
    def plainString(): String =
      "hello"

    @Tool("task_string", "Return a string wrapped in ZIO")
    def taskString(): Task[String] =
      ZIO.succeed("hi")

    @Tool("tool_result", "Return a ToolResult directly")
    def toolResult(): ToolResult =
      ToolResult.text("ok")

    @Tool("task_tool_result", "Return a ToolResult wrapped in ZIO")
    def taskToolResult(): Task[ToolResult] =
      ZIO.succeed(ToolResult.text("ok2"))

  private val runtime = Runtime.default

  private def fetch(name: String): ToolDef[Map[String, Any]] =
    ToolMacros
      .collectTools[TestTools.type](runtime)
      .find(_.name == name)
      .map(_.asInstanceOf[ToolDef[Map[String, Any]]])
      .getOrElse(fail(s"Expected tool $name"))

  private def runTool(name: String): Future[ToolResult] =
    val tool = fetch(name)
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.runToFuture(tool.handler(Map.empty))
    }

  test("String return is wrapped in ToolResult.text"):
    runTool("plain_string").map {
      case ToolResult.Success(content) => assertEquals(content, "hello")
      case other                       => fail(s"Expected ToolResult.Success, got $other")
    }

  test("Task[String] return is wrapped in ToolResult.text"):
    runTool("task_string").map {
      case ToolResult.Success(content) => assertEquals(content, "hi")
      case other                       => fail(s"Expected ToolResult.Success, got $other")
    }

  test("ToolResult return is passed through"):
    runTool("tool_result").map {
      case ToolResult.Success(content) => assertEquals(content, "ok")
      case other                       => fail(s"Expected ToolResult.Success, got $other")
    }

  test("Task[ToolResult] return is passed through"):
    runTool("task_tool_result").map {
      case ToolResult.Success(content) => assertEquals(content, "ok2")
      case other                       => fail(s"Expected ToolResult.Success, got $other")
    }
