package com.tjclp.scalagent.core

import zio.*
import zio.stream.*
import com.tjclp.scalagent.errors.AgentError

/** The result of invoking `Agent.run`: a stream of events and a final typed result.
  *
  * `events` and `result` share a single underlying execution for the lifetime of this
  * `AgentRun` instance. Callers can stream events and then await the final result without
  * triggering a second provider run.
  *
  * @tparam R environment requirements
  * @tparam O the typed output
  */
final case class AgentRun[-R, +O](
    events: ZStream[R & Scope, AgentError, AgentEvent],
    result: ZIO[R & Scope, AgentError, O]
):
  /** Map the output type. */
  def map[O2](f: O => O2): AgentRun[R, O2] =
    AgentRun(events, result.map(f))

  /** Transform the event stream with a side-effecting tap. */
  def tapEvents(f: AgentEvent => UIO[Unit]): AgentRun[R, O] =
    AgentRun(events.tap(f), result)

  /** Collect only text delta events. */
  def textDeltas: ZStream[R & Scope, AgentError, String] =
    events.collect { case AgentEvent.TextDelta(v) => v }

  /** Collect only tool call events. */
  def toolCalls: ZStream[R & Scope, AgentError, AgentEvent.ToolCall] =
    events.collect { case tc: AgentEvent.ToolCall => tc }

  /** Filter to normalized events only (excludes Native passthrough). */
  def normalizedEvents: ZStream[R & Scope, AgentError, AgentEvent] =
    events.filter {
      case _: AgentEvent.Native => false
      case _                    => true
    }

  /** Access native (provider-specific) events only. */
  def nativeEvents: ZStream[R & Scope, AgentError, AgentEvent.Native] =
    events.collect { case n: AgentEvent.Native => n }
