package com.tjclp.scalagent

import zio._
import zio.stream._
import com.tjclp.scalagent.config._
import com.tjclp.scalagent.errors._
import com.tjclp.scalagent.messages._
import com.tjclp.scalagent.session._

/** Simplified entry point for the Claude Agent SDK.
  *
  * This object provides ergonomic top-level functions for common Claude operations,
  * making it easy to get started without understanding the full service architecture.
  *
  * == Quick Start ==
  *
  * {{{
  * import com.tjclp.scalagent.Claude
  *
  * // Simple question/answer
  * val answer = Claude.ask("What is 2 + 2?")
  *
  * // Streaming query
  * Claude.query("Write a poem about Scala")
  *   .textOnly
  *   .tap(Console.printLine(_))
  *   .runDrain
  *
  * // Multi-turn conversation
  * Claude.conversation { session =>
  *   for
  *     _ <- session.send("Hello!")
  *     _ <- session.ask("What's your name?")
  *   yield ()
  * }
  * }}}
  *
  * For more control, use [[ClaudeAgent]] (service pattern) or [[ClaudeSession]] (V2 session API).
  */
object Claude:

  // ============================================================================
  // One-shot Operations
  // ============================================================================

  /** Send a query and get the complete text response.
    *
    * This is the simplest way to interact with Claude - send a prompt, get a text response.
    *
    * Example:
    * {{{
    * val answer = Claude.ask("What is the capital of France?")
    *   .flatMap(answer => Console.printLine(s"Answer: $answer"))
    * }}}
    *
    * @param prompt
    *   The user prompt to send
    * @param options
    *   Configuration options (optional)
    * @return
    *   The text response from Claude
    */
  def ask(prompt: String, options: AgentOptions = AgentOptions.default): IO[AgentError, String] =
    query(prompt, options).runCollect.map { messages =>
      messages.toList.flatMap(_.text).mkString("\n")
    }

  /** Send a query and stream the responses.
    *
    * Returns a stream of AgentMessage values as they arrive. Use stream combinators
    * like `.textOnly` or `.collectResult` for common patterns.
    *
    * Example:
    * {{{
    * Claude.query("Write a haiku")
    *   .textOnly
    *   .tap(text => Console.printLine(text))
    *   .runDrain
    * }}}
    *
    * @param prompt
    *   The user prompt to send
    * @param options
    *   Configuration options (optional)
    * @return
    *   A stream of agent messages
    */
  def query(prompt: String, options: AgentOptions = AgentOptions.default): ZStream[Any, AgentError, AgentMessage] =
    ClaudeAgent.query(prompt, options).provideLayer(ClaudeAgent.live)

  /** Send a query and collect the complete result.
    *
    * Returns a QueryResult containing all messages and the final outcome.
    *
    * Example:
    * {{{
    * Claude.queryComplete("Analyze this code")
    *   .flatMap { result =>
    *     Console.printLine(s"Success: ${result.isSuccess}") *>
    *     Console.printLine(s"Cost: ${result.cost}")
    *   }
    * }}}
    *
    * @param prompt
    *   The user prompt to send
    * @param options
    *   Configuration options (optional)
    * @return
    *   A QueryResult with all messages and outcome
    */
  def queryComplete(prompt: String, options: AgentOptions = AgentOptions.default): IO[AgentError, QueryResult] =
    query(prompt, options).collectResult

  // ============================================================================
  // Session Operations
  // ============================================================================

  /** Create a new session for multi-turn conversations.
    *
    * The session is scoped and will be automatically closed when the scope exits.
    * Returns a `ClaudeSession[Open]` to ensure compile-time safety - you cannot
    * accidentally use a closed session.
    *
    * Example:
    * {{{
    * ZIO.scoped {
    *   for
    *     session <- Claude.session()
    *     _ <- session.ask("What is 2 + 2?")
    *     _ <- session.ask("Now multiply that by 3")
    *   yield ()
    * }
    * }}}
    *
    * @param options
    *   Configuration options (optional)
    * @return
    *   A scoped open ClaudeSession
    */
  def session(options: AgentOptions = AgentOptions.default): ZIO[Scope, AgentError, ClaudeSession[Open]] =
    ClaudeSession.create(options).withFinalizer(s => s.close.ignoreLogged)

  /** Run a multi-turn conversation with automatic resource management.
    *
    * This is the most ergonomic way to have multi-turn conversations with Claude.
    * The session is automatically created and closed.
    *
    * Example:
    * {{{
    * val result = Claude.conversation { session =>
    *   for
    *     answer1 <- session.ask("What is 2 + 2?")
    *     _ <- Console.printLine(s"First answer: $answer1")
    *     answer2 <- session.ask(s"Now multiply $answer1 by 3")
    *     _ <- Console.printLine(s"Second answer: $answer2")
    *   yield answer2
    * }
    * }}}
    *
    * @param options
    *   Configuration options
    * @param f
    *   The conversation function (receives an open session)
    * @return
    *   The result of the conversation
    */
  def conversation[A](options: AgentOptions = AgentOptions.default)(
      f: ClaudeSession[Open] => IO[AgentError, A]
  ): IO[AgentError, A] =
    ZIO.scoped {
      session(options).flatMap(f)
    }

  /** Run a conversation with default options.
    *
    * Convenience overload that uses default configuration.
    *
    * Example:
    * {{{
    * Claude.chat { session =>
    *   session.ask("Hello, who are you?")
    * }
    * }}}
    */
  def chat[A](f: ClaudeSession[Open] => IO[AgentError, A]): IO[AgentError, A] =
    conversation(AgentOptions.default)(f)
