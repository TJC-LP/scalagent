package com.tjclp.scalagent.interop.a2a

import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.core.{AgentEvent, RunSummary}
import com.tjclp.scalagent.a2a.*

/**
 * Pure mapping from A2A stream events to normalized AgentEvents.
 *
 * Follows the same pattern as `ClaudeInterpreter`'s `EventMapper`:
 * normalized events for text, status, and completion; `Native` for
 * protocol-specific events like artifacts and snapshots.
 */
private[a2a] object A2AEventMapper:

  def mapStreamEvent(event: A2AResponse.StreamEvent): List[AgentEvent] = event match
    case A2AResponse.StreamEvent.TaskStatusUpdate(_, _, status, isFinal) =>
      mapTaskStatus(status, isFinal)

    case A2AResponse.StreamEvent.TaskMessage(_, _, message) =>
      if message.hasText then List(AgentEvent.TextDelta(message.text))
      else List(nativeEvent("a2a.message", event))

    case A2AResponse.StreamEvent.TaskArtifactUpdate(_, _, artifact, _, _) =>
      List(nativeEvent("a2a.artifact", event))

    case A2AResponse.StreamEvent.TaskSnapshot(task) =>
      List(nativeEvent("a2a.snapshot", event))

  private def mapTaskStatus(status: TaskStatus, isFinal: Boolean): List[AgentEvent] =
    val state       = status.state
    val messageText = status.message.filter(_.hasText).map(_.text)

    if isFinal then
      val isSuccess = state == TaskState.Completed
      List(
        AgentEvent.Completed(
          RunSummary(
            durationMs = 0, // A2A doesn't carry timing metadata
            numTurns = 0,
            costUsd = 0.0, // A2A doesn't carry cost metadata
            isSuccess = isSuccess,
            resultText = messageText,
            stopReason = Some(state.toString),
          )
        )
      )
    else
      messageText match
        case Some(text) => List(AgentEvent.Status(text))
        case None       => List(AgentEvent.Status(state.toString))
  end mapTaskStatus

  /** Reverse mapping: AgentEvent → A2A message for server adapter. */
  def toA2AMessage(event: AgentEvent): Option[A2AMessage] = event match
    case AgentEvent.TextDelta(text) =>
      Some(A2AMessage.agentText(text, None))
    case AgentEvent.Status(value) =>
      Some(A2AMessage.agentText(value, None))
    case AgentEvent.Completed(summary) =>
      summary.resultText.map(text => A2AMessage.agentText(text, None))
    case _ => None

  private def nativeEvent(tag: String, event: A2AResponse.StreamEvent): AgentEvent.Native =
    AgentEvent.Native(tag, event.toJsonAST.getOrElse(Json.Str(event.toString)))
end A2AEventMapper
