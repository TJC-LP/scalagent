package com.tjclp.scalagent.core.a2a

import zio.Task
import com.tjclp.scalagent.core.Agent
import com.tjclp.scalagent.a2a.AgentCard

/** A remote agent accessible via the A2A protocol.
  *
  * Extends `Agent` — can be used anywhere an `Agent` is expected:
  * as a delegation target in `TypedAgent.delegate`, in `AgentBuilder`,
  * or called directly via `.run()`. The A2A transport is invisible
  * to callers.
  *
  * @tparam P principal type
  * @tparam I input type (typically String — A2A messages are text)
  * @tparam O output type
  */
trait A2ARemoteAgent[-P, -I, +O] extends Agent[P, I, O]:
  /** The remote agent's declared capabilities and metadata. */
  def card: AgentCard

/** Contract for exposing a local Agent as an A2A endpoint.
  *
  * Any `Agent` can be wrapped as an `A2AEndpoint` via an adapter
  * in the interop layer.
  */
trait A2AEndpoint:
  def start: Task[Unit]
  def stop: Task[Unit]
  def url: String
  def card: AgentCard
