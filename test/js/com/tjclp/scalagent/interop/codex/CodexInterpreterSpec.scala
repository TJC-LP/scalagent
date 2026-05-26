package com.tjclp.scalagent.interop.codex

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import zio.*
import zio.json.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.codex.*
import com.tjclp.scalagent.config.StructuredOutput
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.mcp.McpToolName

class CodexInterpreterSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private final class RecordingCodexClient(
      makeStream: () => ZStream[Any, Throwable, CodexEvent]
  ) extends CodexClient:
    var startedOptions: List[CodexThreadOptions] = Nil
    var turnInputs: List[Either[String, List[CodexInputItem]]] = Nil
    var turnOptions: List[CodexTurnOptions] = Nil

    override def startThread(options: CodexThreadOptions): CodexThread =
      startedOptions = startedOptions :+ options
      new CodexThread:
        override def id: Option[String] = Some("thread-1")

        override def runStreamed(input: String, options: CodexTurnOptions): ZStream[Any, Throwable, CodexEvent] =
          turnInputs = turnInputs :+ Left(input)
          turnOptions = turnOptions :+ options
          makeStream()

        override def runStreamed(
            input: Seq[CodexInputItem],
            options: CodexTurnOptions
        ): ZStream[Any, Throwable, CodexEvent] =
          turnInputs = turnInputs :+ Right(input.toList)
          turnOptions = turnOptions :+ options
          makeStream()

        override def run(input: String, options: CodexTurnOptions): Task[CodexTurn] =
          turnInputs = turnInputs :+ Left(input)
          turnOptions = turnOptions :+ options
          ZIO.succeed(CodexTurn(Nil, "unused", Some(CodexUsage(1, 0, 1))))

        override def run(input: Seq[CodexInputItem], options: CodexTurnOptions): Task[CodexTurn] =
          turnInputs = turnInputs :+ Right(input.toList)
          turnOptions = turnOptions :+ options
          ZIO.succeed(CodexTurn(Nil, "unused", Some(CodexUsage(1, 0, 1))))

    override def resumeThread(id: String, options: CodexThreadOptions): CodexThread =
      startThread(options)

  private case class DummyInput(value: String) derives JsonDecoder, ToolInput

  private val customTool = ToolDef.fromInput[DummyInput](
    name = "ask_weather",
    description = "Ask weather"
  )(_ => ZIO.succeed(ToolResult.text("sunny")))

  test("builder keeps sandbox read-only for known read-only MCP tools"):
    val client = new RecordingCodexClient(() =>
      ZStream.fromIterable(List(
        CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "Done")),
        CodexEvent.TurnCompleted(CodexUsage(1, 0, 1))
      ))
    )

    val agent = CodexInterpreter.builder(client)
      .withReadOnlyTools(ToolSurface.withAllowlist(
        tools = Nil,
        allowedTools = List(McpToolName("context7", "resolve-library-id").toToolName)
      ))
      .build

    runTask(ZIO.scoped(agent.run((), "hello", ExecutionPolicy.unbounded).result)).map { output =>
      assertEquals(output, "Done")
      assertEquals(client.startedOptions.headOption.flatMap(_.sandboxMode), Some(SandboxMode.ReadOnly))
    }

  test("string interpreter accepts multimodal CodexInput"):
    val client = new RecordingCodexClient(() =>
      ZStream.fromIterable(List(
        CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "Image described")),
        CodexEvent.TurnCompleted(CodexUsage(1, 0, 1))
      ))
    )

    val program =
      ZIO.scoped {
        CodexInterpreter
          .string(client)
          .run(
            (),
            List(
              CodexInputItem.Text("Describe the screenshots"),
              CodexInputItem.LocalImage("./ui.png")
            ),
            ExecutionPolicy.unbounded
          )
          .result
      }

    runTask(program).map { output =>
      assertEquals(output, "Image described")
      assertEquals(
        client.turnInputs.headOption,
        Some(Right(List(
          CodexInputItem.Text("Describe the screenshots"),
          CodexInputItem.LocalImage("./ui.png")
        )))
      )
    }

  test("withAllTools maps to Codex full-access sandbox"):
    val client = new RecordingCodexClient(() =>
      ZStream.fromIterable(List(
        CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "Done")),
        CodexEvent.TurnCompleted(CodexUsage(1, 0, 1))
      ))
    )

    val agent = CodexInterpreter.builder(client)
      .withAllTools
      .build

    runTask(ZIO.scoped(agent.run((), "hello", ExecutionPolicy.unbounded).result)).map { output =>
      assertEquals(output, "Done")
      assertEquals(client.startedOptions.headOption.flatMap(_.sandboxMode), Some(SandboxMode.FullAccess))
    }

  test("explicit sandbox builder overrides inferred sandbox posture"):
    val client = new RecordingCodexClient(() =>
      ZStream.fromIterable(List(
        CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "Done")),
        CodexEvent.TurnCompleted(CodexUsage(1, 0, 1))
      ))
    )

    val agent = CodexInterpreter
      .sandboxedBuilder(client, SandboxMode.WorkspaceWrite)
      .withReadOnlyTools(ToolSurface.readOnlyBuiltins)
      .build

    runTask(ZIO.scoped(agent.run((), "hello", ExecutionPolicy.unbounded).result)).map { output =>
      assertEquals(output, "Done")
      assertEquals(client.startedOptions.headOption.flatMap(_.sandboxMode), Some(SandboxMode.WorkspaceWrite))
    }

  test("typed interpreter decodes structured output and passes turn schema"):
    case class Analysis(summary: String, status: String) derives JsonDecoder
    given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]

    val jsonResponse = """{"summary":"Done","status":"ok"}"""
    val client = new RecordingCodexClient(() =>
      ZStream.fromIterable(List(
        CodexEvent.ItemStarted(CodexItem.AgentMessage("1", jsonResponse)),
        CodexEvent.TurnCompleted(CodexUsage(1, 0, 1))
      ))
    )

    val program =
      ZIO.scoped {
        CodexInterpreter
          .typed[Analysis](client)
          .run((), "summarize", ExecutionPolicy.unbounded)
          .result
      }

    runTask(program).map { analysis =>
      assertEquals(analysis, Analysis("Done", "ok"))
      assertEquals(client.turnInputs.headOption, Some(Left("summarize")))
      assertEquals(
        client.turnOptions.headOption.flatMap(_.outputSchema),
        Some(StructuredOutput[Analysis].jsonSchema)
      )
    }

  test("builder rejects custom ToolDefs because Codex SDK cannot register them"):
    val client = new RecordingCodexClient(() => ZStream.empty)

    intercept[IllegalArgumentException] {
      CodexInterpreter.builder(client)
        .withTools(ToolSurface(List(customTool)))
        .build
    }

  test("closing the scope interrupts the underlying Codex stream"):
    val program =
      for
        started <- Promise.make[Nothing, Unit]
        interrupted <- Ref.make(false)
        client = new RecordingCodexClient(() =>
          ZStream.unwrap {
            started.succeed(()).as(
              ZStream.acquireReleaseWith(ZIO.unit)(_ => interrupted.set(true)).flatMap(_ => ZStream.never)
            )
          }
        )
        agent = CodexInterpreter.string(client)
        _ <- ZIO.scoped {
          for
            fiber <- agent.run((), "hello", ExecutionPolicy.unbounded).result.fork
            _ <- started.await
            _ <- fiber.interrupt
          yield ()
        }
        wasInterrupted <- interrupted.get
      yield wasInterrupted

    runTask(program).map { wasInterrupted =>
      assert(wasInterrupted)
    }

  test("withWorkingDirectory wires workingDirectory into CodexThreadOptions"):
    val client = new RecordingCodexClient(() =>
      ZStream.fromIterable(List(
        CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "Done")),
        CodexEvent.TurnCompleted(CodexUsage(1, 0, 1))
      ))
    )

    val agent = CodexInterpreter.builder(client)
      .withWorkingDirectory("/data/reports")
      .build

    runTask(ZIO.scoped(agent.run((), "hello", ExecutionPolicy.unbounded).result)).map { _ =>
      assertEquals(client.startedOptions.headOption.flatMap(_.workingDirectory), Some("/data/reports"))
    }

  test("withWorkingDirectory and withAdditionalDirectory wire both into CodexThreadOptions"):
    val client = new RecordingCodexClient(() =>
      ZStream.fromIterable(List(
        CodexEvent.ItemStarted(CodexItem.AgentMessage("1", "Done")),
        CodexEvent.TurnCompleted(CodexUsage(1, 0, 1))
      ))
    )

    val agent = CodexInterpreter.builder(client)
      .withWorkingDirectory("/data/reports")
      .withAdditionalDirectory("/data/shared")
      .build

    runTask(ZIO.scoped(agent.run((), "hello", ExecutionPolicy.unbounded).result)).map { _ =>
      assertEquals(client.startedOptions.headOption.flatMap(_.workingDirectory), Some("/data/reports"))
      assertEquals(client.startedOptions.headOption.map(_.additionalDirectories).getOrElse(Nil), List("/data/shared"))
    }
