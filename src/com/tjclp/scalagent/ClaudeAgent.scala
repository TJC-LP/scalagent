package com.tjclp.scalagent

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import zio.*
import zio.stream.*
import com.tjclp.scalagent.config.*
import com.tjclp.scalagent.errors.*
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.streaming.*

/** Main API for interacting with the Claude Agent SDK.
  *
  * This trait provides a purely functional ZIO interface for querying the Claude agent. It wraps the JavaScript SDK and
  * provides idiomatic Scala types.
  */
trait ClaudeAgent:

  /** Execute a query and return a stream of messages.
    *
    * This is the primary method for interacting with the agent. It returns a ZStream that emits AgentMessage values as
    * they arrive from the SDK.
    *
    * @param prompt
    *   The user prompt to send to the agent
    * @param options
    *   Configuration options for this query
    * @return
    *   A stream of agent messages
    */
  def query(
      prompt: String,
      options: AgentOptions = AgentOptions.default
  ): ZStream[Any, AgentError, AgentMessage]

  /** Execute a query and collect all messages, returning the final result.
    *
    * This is a convenience method that runs the query to completion and returns both all messages and the final
    * outcome.
    *
    * @param prompt
    *   The user prompt to send to the agent
    * @param options
    *   Configuration options for this query
    * @return
    *   A QueryResult containing all messages and the final outcome
    */
  def queryComplete(
      prompt: String,
      options: AgentOptions = AgentOptions.default,
      collectionPolicy: CollectionPolicy = CollectionPolicy.Full,
      sink: QueryCollector.MessageSink = QueryCollector.noSink
  ): IO[AgentError, QueryResult]

  /** Execute a query and return the raw QueryStream for advanced control.
    *
    * Use this when you need access to control methods like `interrupt()` or `setPermissionMode()`.
    *
    * @param prompt
    *   The user prompt to send to the agent
    * @param options
    *   Configuration options for this query
    * @return
    *   A QueryStream wrapper providing both messages and control methods
    */
  def queryRaw(
      prompt: String,
      options: AgentOptions = AgentOptions.default
  ): IO[AgentError, QueryStream]

/** Result of a completed query */
final case class QueryResult(
    messages: List[AgentMessage],
    outcome: ResultOutcome,
    totalMessages: Int = 0,
    sawResult: Boolean = false,
    warnings: CollectedWarnings = CollectedWarnings.empty
):
  /** Get the final text result, or fail with AgentError if not successful */
  def text: Either[AgentError, String] = outcome match
    case s: ResultOutcome.Success => Right(s.result)
    case e: ResultOutcome.Error =>
      Left(AgentError.ApiError(500, e.reason.toString, Some(e.errors.mkString("; "))))

  /** Get the final text result as a ZIO effect */
  def textOrFail: IO[AgentError, String] = ZIO.fromEither(text)

  /** Check if the query completed successfully */
  def isSuccess: Boolean = outcome match
    case _: ResultOutcome.Success => true
    case _: ResultOutcome.Error   => false

  /** Whether the SDK emitted an explicit result envelope. */
  def hasFormalResult: Boolean =
    sawResult

  /** Check if there were any permission denials */
  def hasPermissionDenials: Boolean = outcome.permissionDenials.nonEmpty

  /** Get the total cost in USD */
  def cost: Double = outcome.totalCostUsd

  /** Get the number of turns used */
  def turns: Int = outcome.numTurns

  /** Extract all text content from all messages */
  def allText: String =
    messages.flatMap(_.text).mkString("\n")

  /** Semantic final answer for the query, falling back to assistant text only when no result exists. */
  def semanticText: Either[AgentError, String] =
    QueryCollector.semanticText(this)

  /** Semantic final answer as a ZIO effect. */
  def semanticTextOrFail: IO[AgentError, String] =
    QueryCollector.semanticTextOrFail(this)

  /** Lightweight summary of this collection result. */
  def summary: QuerySummary =
    QuerySummary(
      outcome = outcome,
      totalMessages = totalMessages,
      retainedMessages = messages.size,
      warnings = warnings
    )

  /** Outcome without transcript retention. */
  def outcomeOnly: OutcomeOnly = OutcomeOnly(outcome)

  /** Usage-focused view over the final result. */
  def usageSummary: UsageSummary =
    UsageSummary(
      totalCostUsd = outcome.totalCostUsd,
      numTurns = outcome.numTurns,
      usage = outcome.usage,
      modelUsage = outcome.modelUsage
    )


object ClaudeAgent:

  /** Create a live ClaudeAgent layer. */
  val live: ULayer[ClaudeAgent] = ZLayer.succeed(ClaudeAgentLive())

  /** Accessor for the query method */
  def query(
      prompt: String,
      options: AgentOptions = AgentOptions.default
  ): ZStream[ClaudeAgent, AgentError, AgentMessage] =
    ZStream.serviceWithStream[ClaudeAgent](_.query(prompt, options))

  /** Accessor for the queryComplete method */
  def queryComplete(
      prompt: String,
      options: AgentOptions = AgentOptions.default,
      collectionPolicy: CollectionPolicy = CollectionPolicy.Full,
      sink: QueryCollector.MessageSink = QueryCollector.noSink
  ): ZIO[ClaudeAgent, AgentError, QueryResult] =
    ZIO.serviceWithZIO[ClaudeAgent](_.queryComplete(prompt, options, collectionPolicy, sink))

  /** Accessor for the queryRaw method */
  def queryRaw(
      prompt: String,
      options: AgentOptions = AgentOptions.default
  ): ZIO[ClaudeAgent, AgentError, QueryStream] =
    ZIO.serviceWithZIO[ClaudeAgent](_.queryRaw(prompt, options))

/** Live implementation of ClaudeAgent */
private final class ClaudeAgentLive extends ClaudeAgent:

  override def query(
      prompt: String,
      options: AgentOptions
  ): ZStream[Any, AgentError, AgentMessage] =
    ZStream.fromZIO(queryRaw(prompt, options)).flatMap(_.messages)

  override def queryComplete(
      prompt: String,
      options: AgentOptions,
      collectionPolicy: CollectionPolicy,
      sink: QueryCollector.MessageSink
  ): IO[AgentError, QueryResult] =
    for
      stream <- queryRaw(prompt, options)
      result <- QueryCollector.collect(stream.messages, collectionPolicy, sink)
      cleanupWarnings <- stream.cleanupFailures
    yield
      if cleanupWarnings.isEmpty then result
      else result.copy(warnings = result.warnings ++ cleanupWarnings.map(_.description))

  override def queryRaw(
      prompt: String,
      options: AgentOptions
  ): IO[AgentError, QueryStream] =
    (for
      runtime <- ZIO.runtime[Any]
      stream <- ZIO.attempt {
        val preparedOptions = AgentOptionsCompatibility.prepare(options)

        // Convert options to raw JS object
        val rawOptions = preparedOptions.toRaw.asInstanceOf[js.Dynamic]

        // Wire up hooks if any are configured
        if preparedOptions.hooks.nonEmpty then
          rawOptions.hooks = preparedOptions.hooksToRaw(runtime)

        // Wire up subagents with runtime hook callbacks if present
        if preparedOptions.agents.nonEmpty then
          rawOptions.agents = preparedOptions.agentsToRaw(runtime)

        // Wire up canUseTool permission handler if configured
        preparedOptions.canUseToolToRaw(runtime).foreach { handler =>
          rawOptions.canUseTool = handler
        }

        val params = js.Dynamic.literal(
          prompt = prompt,
          options = rawOptions
        )
        val rawQuery = SdkModule.query(params).asInstanceOf[RawQuery]
        QueryStream(rawQuery)
      }
    yield stream).mapError(AgentError.fromThrowable)

/** JavaScript module binding for the SDK.
  *
  * This imports the `query` function from `@anthropic-ai/claude-agent-sdk`.
  */
@js.native
@JSImport("@anthropic-ai/claude-agent-sdk", JSImport.Namespace)
private[scalagent] object SdkModule extends js.Object:
  def query(params: js.Dynamic): js.Object = js.native
  def listSessions(options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Array[js.Dynamic]] = js.native
  def getSessionMessages(
      sessionId: String,
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[js.Array[js.Dynamic]] = js.native
