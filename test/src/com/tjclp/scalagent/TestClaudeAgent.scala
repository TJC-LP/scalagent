package com.tjclp.scalagent

import zio._
import zio.stream._
import com.tjclp.scalagent.config._
import com.tjclp.scalagent.errors._
import com.tjclp.scalagent.messages._
import com.tjclp.scalagent.streaming._

/** Mock ClaudeAgent implementation for testing.
  *
  * Allows tests to configure canned responses and verify interactions
  * without making actual API calls.
  *
  * Example usage:
  * {{{
  * import TestFixtures._
  *
  * val test = for
  *   _      <- TestClaudeAgent.setResponses(simpleConversation)
  *   result <- ClaudeAgent.queryComplete("Hello")
  * yield assertTrue(result.isSuccess)
  *
  * test.provide(TestClaudeAgent.layer)
  * }}}
  */
final class TestClaudeAgent(
    val responsesRef: Ref[List[AgentMessage]],
    val promptsRef: Ref[List[String]],
    val optionsRef: Ref[List[AgentOptions]],
    val errorRef: Ref[Option[AgentError]]
) extends ClaudeAgent:

  override def query(
      prompt: String,
      opts: AgentOptions
  ): ZStream[Any, AgentError, AgentMessage] =
    ZStream.unwrap(
      for
        _        <- promptsRef.update(_ :+ prompt)
        _        <- optionsRef.update(_ :+ opts)
        maybeErr <- errorRef.get
        msgs     <- responsesRef.get
      yield maybeErr match
        case Some(err) => ZStream.fail(err)
        case None      => ZStream.fromIterable(msgs)
    )

  override def queryComplete(
      prompt: String,
      opts: AgentOptions
  ): IO[AgentError, QueryResult] =
    for
      _        <- promptsRef.update(_ :+ prompt)
      _        <- optionsRef.update(_ :+ opts)
      maybeErr <- errorRef.get
      result   <- maybeErr match
        case Some(err) => ZIO.fail(err)
        case None =>
          responsesRef.get.map { msgs =>
            val outcome = msgs.collectFirst { case AgentMessage.Result(o, _, _) => o }
            QueryResult(
              msgs,
              outcome.getOrElse(
                ResultOutcome.Error(
                  reason = ErrorReason.DuringExecution,
                  durationMs = 0,
                  durationApiMs = 0,
                  numTurns = 0,
                  totalCostUsd = 0.0,
                  usage = ModelUsage.empty,
                  modelUsage = Map.empty,
                  permissionDenials = Nil,
                  errors = List("No result message in test responses")
                )
              )
            )
          }
    yield result

  override def queryRaw(
      prompt: String,
      opts: AgentOptions
  ): IO[AgentError, QueryStream] =
    // queryRaw is not easily mockable since QueryStream wraps JS native code
    // Tests should use query() or queryComplete() instead
    ZIO.fail(AgentError.ConfigurationError(
      "queryRaw is not supported in TestClaudeAgent. Use query() or queryComplete() instead."
    ))

object TestClaudeAgent:

  /** Create a TestClaudeAgent layer with empty initial state */
  val layer: ULayer[ClaudeAgent & TestClaudeAgent] =
    ZLayer.fromZIO(
      for
        responses <- Ref.make[List[AgentMessage]](Nil)
        prompts   <- Ref.make[List[String]](Nil)
        options   <- Ref.make[List[AgentOptions]](Nil)
        error     <- Ref.make[Option[AgentError]](None)
      yield new TestClaudeAgent(responses, prompts, options, error)
    )

  /** Create a layer with pre-configured responses */
  def withResponses(messages: AgentMessage*): ULayer[ClaudeAgent & TestClaudeAgent] =
    ZLayer.fromZIO(
      for
        responses <- Ref.make[List[AgentMessage]](messages.toList)
        prompts   <- Ref.make[List[String]](Nil)
        options   <- Ref.make[List[AgentOptions]](Nil)
        error     <- Ref.make[Option[AgentError]](None)
      yield new TestClaudeAgent(responses, prompts, options, error)
    )

  /** Create a layer that returns a simple text result */
  def withResult(text: String): ULayer[ClaudeAgent & TestClaudeAgent] =
    val result = AgentMessage.Result(
      outcome = ResultOutcome.Success(
        durationMs = 100,
        durationApiMs = 80,
        numTurns = 1,
        result = text,
        totalCostUsd = 0.001,
        usage = ModelUsage.empty,
        modelUsage = Map.empty,
        permissionDenials = Nil,
        structuredOutput = None
      ),
      uuid = TestFixtures.testMessageUuid,
      sessionId = TestFixtures.testSessionId
    )
    withResponses(result)

  /** Create a layer that always fails with the given error */
  def withError(error: AgentError): ULayer[ClaudeAgent & TestClaudeAgent] =
    ZLayer.fromZIO(
      for
        responses <- Ref.make[List[AgentMessage]](Nil)
        prompts   <- Ref.make[List[String]](Nil)
        options   <- Ref.make[List[AgentOptions]](Nil)
        err       <- Ref.make[Option[AgentError]](Some(error))
      yield new TestClaudeAgent(responses, prompts, options, err)
    )

  // ============================================
  // Test Accessors - for verifying interactions
  // ============================================

  /** Set the responses that will be returned by subsequent queries */
  def setResponses(messages: List[AgentMessage]): ZIO[TestClaudeAgent, Nothing, Unit] =
    ZIO.serviceWithZIO[TestClaudeAgent](agent =>
      agent.responsesRef.set(messages)
    )

  /** Set an error to be returned by subsequent queries */
  def setError(error: AgentError): ZIO[TestClaudeAgent, Nothing, Unit] =
    ZIO.serviceWithZIO[TestClaudeAgent](agent =>
      agent.errorRef.set(Some(error))
    )

  /** Clear any error, so queries will succeed */
  def clearError: ZIO[TestClaudeAgent, Nothing, Unit] =
    ZIO.serviceWithZIO[TestClaudeAgent](agent =>
      agent.errorRef.set(None)
    )

  /** Get all prompts that were sent to the agent */
  def getPrompts: ZIO[TestClaudeAgent, Nothing, List[String]] =
    ZIO.serviceWithZIO[TestClaudeAgent](_.promptsRef.get)

  /** Get all options that were passed to queries */
  def getOptions: ZIO[TestClaudeAgent, Nothing, List[AgentOptions]] =
    ZIO.serviceWithZIO[TestClaudeAgent](_.optionsRef.get)

  /** Clear recorded prompts and options */
  def clearRecorded: ZIO[TestClaudeAgent, Nothing, Unit] =
    ZIO.serviceWithZIO[TestClaudeAgent](agent =>
      agent.promptsRef.set(Nil) *> agent.optionsRef.set(Nil)
    )

  /** Verify that a specific prompt was sent */
  def wasPromptSent(prompt: String): ZIO[TestClaudeAgent, Nothing, Boolean] =
    getPrompts.map(_.contains(prompt))

  /** Verify that the agent was called exactly n times */
  def wasCalledTimes(n: Int): ZIO[TestClaudeAgent, Nothing, Boolean] =
    getPrompts.map(_.size == n)
