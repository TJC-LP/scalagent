package com.tjclp.scalagent.interop.claude

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import zio.*
import zio.json.*
import zio.stream.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.config.StructuredOutput
import com.tjclp.scalagent.core.*
import com.tjclp.scalagent.streaming.QueryStream
import com.tjclp.scalagent.TestFixtures.*

class ClaudeInterpreterSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private final class InterruptibleClaudeAgent(
      started: Promise[Nothing, Unit],
      interrupted: Ref[Boolean],
      emitProgress: Boolean
  ) extends ClaudeAgent:
    override def query(
        prompt: String,
        opts: AgentOptions
    ): ZStream[Any, AgentError, AgentMessage] =
      ZStream.fail(AgentError.ConfigurationError("unused in test"))

    override def queryComplete(
        prompt: String,
        opts: AgentOptions,
        collectionPolicy: CollectionPolicy,
        sink: QueryCollector.MessageSink
    ): IO[AgentError, QueryResult] =
      (
        started.succeed(()).unit *>
          ZIO.when(emitProgress)(sink(assistantMessage).orDie) *>
          ZIO.never
      ).onInterrupt(interrupted.set(true))

    override def queryRaw(
        prompt: String,
        opts: AgentOptions
    ): IO[AgentError, QueryStream] =
      ZIO.fail(AgentError.ConfigurationError("unused in test"))

  test("string interpreter emits normalized events and returns final text"):
    val program =
      for
        claude <- ZIO.service[ClaudeAgent]
        agent = ClaudeInterpreter.string(claude)
        run = agent.run(
          principal = (),
          input = "Hello",
          policy = ExecutionPolicy.simple(budgetUsd = 0.25, maxTurns = 4)
        )
        output <- ZIO.scoped {
          for
            events <- run.events.runCollect.map(_.toList)
            text <- run.result
            options <- TestClaudeAgent.getOptions
          yield (events, text, options.headOption)
        }
      yield output

    runTask(program.provide(TestClaudeAgent.withResponses(
      assistantMessageWithToolUse,
      resultSuccess
    ))).map { case (events, text, options) =>
      assertEquals(text, successOutcome.result)
      assert(events.exists {
        case AgentEvent.TextDelta("Hello, I'm Claude!") => true
        case _                                          => false
      })
      assert(events.exists {
        case AgentEvent.ToolCall("Read", _) => true
        case _                              => false
      })
      assert(events.exists {
        case AgentEvent.Completed(summary) => summary.isSuccess
        case _                             => false
      })
      assertEquals(options.flatMap(_.maxTurns), Some(4))
      assertEquals(options.flatMap(_.maxBudgetUsd), Some(0.25))
    }

  test("typed interpreter decodes structured output"):
    case class Analysis(summary: String, score: Int) derives JsonDecoder
    given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]

    val structuredResult = AgentMessage.Result(
      outcome = successOutcomeWithStructuredOutput,
      fastModeState = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

    val program =
      for
        claude <- ZIO.service[ClaudeAgent]
        agent = ClaudeInterpreter.typed[Analysis](claude)
        run = agent.run((), "Analyze", ExecutionPolicy.unbounded)
        analysis <- ZIO.scoped(run.result)
        options <- TestClaudeAgent.getOptions
      yield (analysis, options.headOption)

    runTask(program.provide(TestClaudeAgent.withResponses(structuredResult))).map { case (analysis, options) =>
      assertEquals(analysis.summary, "Done")
      assertEquals(analysis.score, 95)
      assert(options.exists(_.outputFormat.isDefined))
    }

  test("events and result share one underlying execution within a scope"):
    val program =
      for
        claude <- ZIO.service[ClaudeAgent]
        agent = ClaudeInterpreter.string(claude)
        run = agent.run((), "Hello once", ExecutionPolicy.unbounded)
        output <- ZIO.scoped {
          for
            _ <- run.events.runDrain
            text <- run.result
            calledOnce <- TestClaudeAgent.wasCalledTimes(1)
          yield (text, calledOnce)
        }
      yield output

    runTask(program.provide(TestClaudeAgent.withResponses(
      assistantMessage,
      resultSuccess
    ))).map { case (text, calledOnce) =>
      assertEquals(text, successOutcome.result)
      assert(calledOnce)
    }

  test("provider errors fail both result and event stream"):
    val program =
      for
        claude <- ZIO.service[ClaudeAgent]
        agent = ClaudeInterpreter.string(claude)
        run = agent.run((), "Hello", ExecutionPolicy.unbounded)
        result <- ZIO.scoped(run.result.either zip run.events.runDrain.either)
      yield result

    runTask(program.provide(TestClaudeAgent.withError(rateLimitedError))).map { case (resultEither, eventsEither) =>
      assert(resultEither.left.exists(_.isInstanceOf[AgentError.RateLimited]))
      assert(eventsEither.left.exists(_.isInstanceOf[AgentError.RateLimited]))
    }

  test("closing the scope interrupts the underlying provider run"):
    val program =
      for
        started <- Promise.make[Nothing, Unit]
        interrupted <- Ref.make(false)
        claude = new InterruptibleClaudeAgent(started, interrupted, emitProgress = false)
        agent = ClaudeInterpreter.string(claude)
        _ <- ZIO.scoped {
          for
            fiber <- agent.run((), "Hello", ExecutionPolicy.unbounded).result.fork
            _ <- started.await
            _ <- fiber.interrupt
          yield ()
        }
        wasInterrupted <- interrupted.get
      yield wasInterrupted

    runTask(program).map { wasInterrupted =>
      assert(wasInterrupted)
    }

  test("string interpreter defaults to no tools when none declared"):
    val program =
      for
        claude  <- ZIO.service[ClaudeAgent]
        agent    = ClaudeInterpreter.string(claude)
        _       <- ZIO.scoped(agent.run((), "hello", ExecutionPolicy.unbounded).result)
        options <- TestClaudeAgent.getOptions
      yield options.headOption

    runTask(program.provide(TestClaudeAgent.withResult("ok"))).map { maybeOpts =>
      assertEquals(maybeOpts.flatMap(_.allowedTools), Some(Nil))
    }
