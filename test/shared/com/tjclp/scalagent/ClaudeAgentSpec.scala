package com.tjclp.scalagent

import munit.FunSuite
import zio._
import zio.stream._
import com.tjclp.scalagent.config._
import com.tjclp.scalagent.errors._
import com.tjclp.scalagent.messages._
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.TestFixtures._

/** Integration tests using TestClaudeAgent mock.
  *
  * These tests verify the ClaudeAgent interface behavior using
  * a mock implementation that doesn't require actual API calls.
  *
  * NOTE: These tests are ignored on Scala.js because ZIO can't block
  * in JavaScript's single-threaded environment. They serve as documentation
  * for how the mock should be used and can be run on JVM if needed.
  */
class ClaudeAgentSpec extends FunSuite:

  // ZIO can't block in JavaScript, so we need to skip these tests
  // They document how the TestClaudeAgent mock works but can't run in Scala.js
  override def munitIgnore: Boolean = true

  // Helper to run ZIO effects in tests (only works on JVM, not JS)
  def runZIO[A](zio: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(zio).getOrThrowFiberFailure()
    }

  // ============================================
  // Query Method Tests
  // ============================================

  test("query returns stream of messages"):
    val program = for
      agent  <- ZIO.service[ClaudeAgent]
      result <- agent.query("Hello", AgentOptions.default).runCollect
    yield result.toList

    val messages = runZIO(
      program.provide(TestClaudeAgent.withResponses(simpleConversation*))
    )

    assertEquals(messages.size, simpleConversation.size)

  test("query streams messages in order"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.query("Hello", AgentOptions.default).runCollect
    yield result.toList

    val messages = runZIO(
      program.provide(TestClaudeAgent.withResponses(
        userMessage,
        assistantMessage,
        resultSuccess
      ))
    )

    // First message should be User
    assert(messages.head.isUser)
    // Second should be Assistant
    assert(messages(1).isAssistant)
    // Last should be Result
    assert(messages.last.isResult)

  test("query returns empty stream when no responses"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.query("Hello", AgentOptions.default).runCollect
    yield result.toList

    val messages = runZIO(
      program.provide(TestClaudeAgent.layer)
    )

    assert(messages.isEmpty)

  test("query fails when error is set"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.query("Hello", AgentOptions.default).runCollect.either
    yield result

    val result = runZIO(
      program.provide(TestClaudeAgent.withError(rateLimitedError))
    )

    result match
      case Left(err: AgentError.RateLimited) => () // Expected
      case other => fail(s"Expected Left(RateLimited), got $other")

  // ============================================
  // QueryComplete Method Tests
  // ============================================

  test("queryComplete returns QueryResult"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Hello", AgentOptions.default)
    yield result

    val result = runZIO(
      program.provide(TestClaudeAgent.withResponses(simpleConversation*))
    )

    assertEquals(result.messages.size, simpleConversation.size)
    assert(result.isSuccess)

  test("queryComplete extracts text from successful result"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Hello", AgentOptions.default)
    yield result.text

    val text = runZIO(
      program.provide(TestClaudeAgent.withResponses(simpleConversation*))
    )

    text match
      case Right(t) => assert(t.contains("Task completed"))
      case Left(_)  => fail("Expected Right")

  test("queryComplete returns error for failed conversation"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Hello", AgentOptions.default)
    yield result

    val result = runZIO(
      program.provide(TestClaudeAgent.withResponses(errorConversation*))
    )

    assert(!result.isSuccess)
    result.outcome match
      case e: ResultOutcome.Error =>
        assertEquals(e.reason, ErrorReason.DuringExecution)
      case _ => fail("Expected Error outcome")

  test("queryComplete extracts cost from result"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Hello", AgentOptions.default)
    yield result.cost

    val cost = runZIO(
      program.provide(TestClaudeAgent.withResponses(simpleConversation*))
    )

    assertEquals(cost, 0.005)

  test("queryComplete extracts turns from result"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Hello", AgentOptions.default)
    yield result.turns

    val turns = runZIO(
      program.provide(TestClaudeAgent.withResponses(simpleConversation*))
    )

    assertEquals(turns, 3)

  // ============================================
  // Convenience Layer Tests
  // ============================================

  test("withResult layer returns simple text result"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Hello", AgentOptions.default)
    yield result.text

    val text = runZIO(
      program.provide(TestClaudeAgent.withResult("Hello from test!"))
    )

    text match
      case Right(t) => assertEquals(t, "Hello from test!")
      case Left(_)  => fail("Expected Right")

  test("withError layer fails all queries"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Hello", AgentOptions.default).either
    yield result

    val result = runZIO(
      program.provide(TestClaudeAgent.withError(apiError))
    )

    result match
      case Left(e: AgentError.ApiError) =>
        assertEquals(e.code, 500)
      case other => fail(s"Expected Left(ApiError), got $other")

  // ============================================
  // Test Accessor Tests
  // ============================================

  test("getPrompts tracks sent prompts"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- agent.query("First prompt", AgentOptions.default).runDrain
      _     <- agent.query("Second prompt", AgentOptions.default).runDrain
      prompts <- TestClaudeAgent.getPrompts
    yield prompts

    val prompts = runZIO(
      program.provide(TestClaudeAgent.withResponses(resultSuccess))
    )

    assertEquals(prompts, List("First prompt", "Second prompt"))

  test("getOptions tracks passed options"):
    val opts = AgentOptions.default.withMaxTurns(5)
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- agent.query("Test", opts).runDrain
      options <- TestClaudeAgent.getOptions
    yield options

    val options = runZIO(
      program.provide(TestClaudeAgent.withResponses(resultSuccess))
    )

    assertEquals(options.size, 1)
    assertEquals(options.head.maxTurns, Some(5))

  test("wasPromptSent checks if prompt was used"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- agent.query("Special prompt", AgentOptions.default).runDrain
      sent  <- TestClaudeAgent.wasPromptSent("Special prompt")
      notSent <- TestClaudeAgent.wasPromptSent("Other prompt")
    yield (sent, notSent)

    val (sent, notSent) = runZIO(
      program.provide(TestClaudeAgent.withResponses(resultSuccess))
    )

    assert(sent)
    assert(!notSent)

  test("wasCalledTimes tracks call count"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- agent.query("One", AgentOptions.default).runDrain
      _     <- agent.query("Two", AgentOptions.default).runDrain
      _     <- agent.query("Three", AgentOptions.default).runDrain
      called3 <- TestClaudeAgent.wasCalledTimes(3)
      called2 <- TestClaudeAgent.wasCalledTimes(2)
    yield (called3, called2)

    val (called3, called2) = runZIO(
      program.provide(TestClaudeAgent.withResponses(resultSuccess))
    )

    assert(called3)
    assert(!called2)

  test("clearRecorded resets tracking state"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- agent.query("First", AgentOptions.default).runDrain
      _     <- TestClaudeAgent.clearRecorded
      _     <- agent.query("Second", AgentOptions.default).runDrain
      prompts <- TestClaudeAgent.getPrompts
    yield prompts

    val prompts = runZIO(
      program.provide(TestClaudeAgent.withResponses(resultSuccess))
    )

    assertEquals(prompts, List("Second"))

  test("setResponses changes what queries return"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- TestClaudeAgent.setResponses(List(resultSuccess))
      result <- agent.queryComplete("Test", AgentOptions.default)
    yield result.isSuccess

    val isSuccess = runZIO(
      program.provide(TestClaudeAgent.layer)
    )

    assert(isSuccess)

  test("setError causes subsequent queries to fail"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- TestClaudeAgent.setError(permissionDeniedError)
      result <- agent.queryComplete("Test", AgentOptions.default).either
    yield result

    val result = runZIO(
      program.provide(TestClaudeAgent.layer)
    )

    result match
      case Left(_: AgentError.PermissionDenied) => () // Expected
      case other => fail(s"Expected Left(PermissionDenied), got $other")

  test("clearError allows queries to succeed again"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      _     <- TestClaudeAgent.setResponses(List(resultSuccess))
      _     <- TestClaudeAgent.setError(apiError)
      fail1 <- agent.queryComplete("Test", AgentOptions.default).either
      _     <- TestClaudeAgent.clearError
      success <- agent.queryComplete("Test", AgentOptions.default).either
    yield (fail1, success)

    val (fail1, success) = runZIO(
      program.provide(TestClaudeAgent.layer)
    )

    assert(fail1.isLeft)
    assert(success.isRight)

  // ============================================
  // Tool Use Flow Tests
  // ============================================

  test("tool use conversation flows correctly"):
    val program = for
      agent <- ZIO.service[ClaudeAgent]
      result <- agent.queryComplete("Read a file", AgentOptions.default)
    yield result

    val result = runZIO(
      program.provide(TestClaudeAgent.withResponses(toolUseConversation*))
    )

    // Should have tool calls
    val toolCalls = result.messages.allToolCalls
    assertEquals(toolCalls.size, 1)
    assertEquals(toolCalls.head.name, ToolName.Read)

    // Should have tool results
    val toolResults = result.messages.allToolResults
    assertEquals(toolResults.size, 1)

    // Should complete successfully
    assert(result.isSuccess)
