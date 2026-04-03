package com.tjclp.scalagent.interop.claude

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import zio.*
import zio.json.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.core.mcp.McpToolSurface
import com.tjclp.scalagent.tools.ToolName

class ClaudeBuilderSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private case class DummyInput(value: String) derives JsonDecoder, ToolInput

  private val customTool = ToolDef.fromInput[DummyInput](
    name = "ask_weather",
    description = "Ask weather"
  )(_ => ZIO.succeed(ToolResult.text("sunny")))

  test("builder with MCP tools wires namespaced allowedTools"):
    val surface = McpToolSurface("weather", List(customTool))

    val program =
      for
        claude <- ZIO.service[ClaudeAgent]
        agent = ClaudeInterpreter
          .builder(claude)
          .withMcpTools(surface)
          .build
        _ <- ZIO.scoped(agent.run((), "hello").result)
        options <- TestClaudeAgent.getOptions
      yield options.headOption

    runTask(program.provide(TestClaudeAgent.withResult("ok"))).map { maybeOptions =>
      val allowed = maybeOptions.flatMap(_.allowedTools).getOrElse(Nil)
      assert(allowed.contains(ToolName.Custom("mcp__weather__ask_weather")))
    }

  test("builder with read-only tools wires built-in read-only allowlist"):
    val program =
      for
        claude <- ZIO.service[ClaudeAgent]
        agent = ClaudeInterpreter
          .builder(claude)
          .withReadOnlyTools(ToolSurface.empty)
          .build
        _ <- ZIO.scoped(agent.run((), "hello").result)
        options <- TestClaudeAgent.getOptions
      yield options.headOption

    runTask(program.provide(TestClaudeAgent.withResult("ok"))).map { maybeOptions =>
      val allowed = maybeOptions.flatMap(_.allowedTools).getOrElse(Nil)
      assert(allowed.contains(ToolName.Read))
      assert(allowed.contains(ToolName.Grep))
      assert(allowed.contains(ToolName.Glob))
    }

  test("withReadOnlyTools rejects non-read-only allowlists"):
    intercept[IllegalArgumentException] {
      AgentBuilder(new Agent[Any, String, String]:
        def run(principal: Any, input: String, policy: ExecutionPolicy): AgentRun[Any, String] =
          AgentRun(zio.stream.ZStream.empty, zio.ZIO.succeed(input))
      ).withReadOnlyTools(ToolSurface(List(customTool)))
    }
