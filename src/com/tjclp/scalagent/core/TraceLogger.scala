package com.tjclp.scalagent.core

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Sink for agent trace events and evaluations.
  *
  * Composable: multiple loggers can be combined via `all`.
  * Pluggable: implement `logEvent` and `logEvaluation` for any backend.
  */
trait TraceLogger:
  /** Log a single AgentEvent as it streams. */
  def logEvent(event: AgentEvent): UIO[Unit]

  /** Log the final evaluation after a run completes. */
  def logEvaluation[P, O](eval: Evaluation[P, O]): UIO[Unit]

object TraceLogger:

  /** No-op logger — discards everything. */
  def noop: TraceLogger = new TraceLogger:
    def logEvent(event: AgentEvent): UIO[Unit] = ZIO.unit
    def logEvaluation[P, O](eval: Evaluation[P, O]): UIO[Unit] = ZIO.unit

  /** Log to console with human-readable formatting. */
  def console: TraceLogger = new TraceLogger:
    def logEvent(event: AgentEvent): UIO[Unit] =
      ZIO.succeed {
        event match
          case AgentEvent.TextDelta(v)              => print(v)
          case AgentEvent.ToolCall(name, _)         => println(s"  [tool] $name")
          case AgentEvent.ToolResult(name, _, err)  => println(s"  [result] $name${if err then " (error)" else ""}")
          case AgentEvent.DelegationStarted(l, id)  => println(s"  [delegate] $l ($id)")
          case AgentEvent.DelegationFinished(id, s) => println(s"  [done] $id: $s")
          case AgentEvent.Status(v)                 => println(s"  [status] $v")
          case AgentEvent.Completed(summary)        =>
            println(s"\n  --- Completed: ${if summary.isSuccess then "success" else "failure"} ---")
            println(s"  turns=${summary.numTurns} cost=$$${summary.costUsd} duration=${summary.durationMs}ms")
          case AgentEvent.Native(tag, _)            => () // silent
      }

    def logEvaluation[P, O](eval: Evaluation[P, O]): UIO[Unit] =
      ZIO.succeed {
        println(s"  [eval] score=${eval.score} complexity=${eval.complexity.totalNodes} nodes")
      }

  /** Log to a callback function (JSONL-style). */
  def callback(sink: String => Unit): TraceLogger = new TraceLogger:
    def logEvent(event: AgentEvent): UIO[Unit] =
      ZIO.succeed {
        val json = eventToJson(event)
        sink(json.toJson)
      }

    def logEvaluation[P, O](eval: Evaluation[P, O]): UIO[Unit] =
      ZIO.succeed {
        val json = Json.Obj(
          "type" -> Json.Str("evaluation"),
          "score" -> Json.Num(eval.score),
          "isSuccess" -> Json.Bool(eval.trace.isSuccess),
          "costUsd" -> Json.Num(eval.trace.costUsd),
          "durationMs" -> Json.Num(eval.trace.durationMs),
          "numTurns" -> Json.Num(eval.trace.numTurns),
          "numToolCalls" -> Json.Num(eval.trace.numToolCalls),
          "numDelegations" -> Json.Num(eval.trace.numDelegations),
          "totalEvents" -> Json.Num(eval.trace.totalEvents),
          "complexityNodes" -> Json.Num(eval.complexity.totalNodes)
        )
        sink(json.toJson)
      }

  /** Compose multiple loggers — fan-out to all. */
  def all(loggers: TraceLogger*): TraceLogger = new TraceLogger:
    def logEvent(event: AgentEvent): UIO[Unit] =
      ZIO.foreachDiscard(loggers)(_.logEvent(event))

    def logEvaluation[P, O](eval: Evaluation[P, O]): UIO[Unit] =
      ZIO.foreachDiscard(loggers)(_.logEvaluation(eval))

  private def eventToJson(event: AgentEvent): Json =
    event match
      case AgentEvent.TextDelta(v) =>
        Json.Obj("type" -> Json.Str("text_delta"), "value" -> Json.Str(v))
      case AgentEvent.ToolCall(name, args) =>
        Json.Obj("type" -> Json.Str("tool_call"), "name" -> Json.Str(name), "args" -> args)
      case AgentEvent.ToolResult(name, value, isError) =>
        Json.Obj("type" -> Json.Str("tool_result"), "name" -> Json.Str(name), "value" -> value, "isError" -> Json.Bool(isError))
      case AgentEvent.DelegationStarted(label, childId) =>
        Json.Obj("type" -> Json.Str("delegation_started"), "label" -> Json.Str(label), "childId" -> Json.Str(childId))
      case AgentEvent.DelegationFinished(childId, status) =>
        Json.Obj("type" -> Json.Str("delegation_finished"), "childId" -> Json.Str(childId), "status" -> Json.Str(status))
      case AgentEvent.Status(v) =>
        Json.Obj("type" -> Json.Str("status"), "value" -> Json.Str(v))
      case AgentEvent.Completed(summary) =>
        Json.Obj("type" -> Json.Str("completed"), "isSuccess" -> Json.Bool(summary.isSuccess),
          "costUsd" -> Json.Num(summary.costUsd), "numTurns" -> Json.Num(summary.numTurns))
      case AgentEvent.Native(tag, payload) =>
        Json.Obj("type" -> Json.Str("native"), "tag" -> Json.Str(tag), "payload" -> payload)
