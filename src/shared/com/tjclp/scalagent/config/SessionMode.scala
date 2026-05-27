package com.tjclp.scalagent.config

import com.tjclp.scalagent.types.{SessionId, MessageUuid}

/**
 * Session mode for multi-turn conversations.
 *
 * Replaces the confusing combination of `continueSession: Boolean` and `resume: Option[String]`
 * with a clear, type-safe enum.
 *
 * Example:
 * {{{
 * // Fresh session (default)
 * val opts = AgentOptions.default.withSessionMode(SessionMode.New)
 *
 * // Continue current session
 * val opts = AgentOptions.default.withSessionMode(SessionMode.Continue)
 *
 * // Resume specific session by ID
 * val opts = AgentOptions.default.withSessionMode(SessionMode.Resume(SessionId("session-abc123")))
 *
 * // Fork from existing session (creates new branch)
 * val opts = AgentOptions.default.withSessionMode(SessionMode.Fork(SessionId("session-abc123")))
 *
 * // Resume from a specific message in a session
 * val opts = AgentOptions.default.withSessionMode(
 *   SessionMode.ResumeAt(SessionId("session-abc123"), MessageUuid("msg-uuid"))
 * )
 * }}}
 */
enum SessionMode:

  /** Start a fresh session (default behavior) */
  case New

  /** Continue the current session */
  case Continue

  /** Resume a specific session by ID */
  case Resume(sessionId: SessionId)

  /**
   * Fork from an existing session, creating a new branch without modifying the original.
   * Useful for exploring alternative approaches while preserving the original conversation.
   */
  case Fork(sessionId: SessionId)

  /**
   * Resume a session from a specific message UUID.
   * Only resumes messages up to and including the specified message.
   * @param sessionId The session to resume
   * @param messageUuid The message UUID to resume at (from SDKAssistantMessage.uuid)
   */
  case ResumeAt(sessionId: SessionId, messageUuid: MessageUuid)
end SessionMode

object SessionMode:

  /** Default session mode - start fresh */
  val default: SessionMode = New

  /** Check if this mode resumes a specific session */
  def isResume(mode: SessionMode): Boolean = mode match
    case Resume(_) | Fork(_) | ResumeAt(_, _) => true
    case _                                    => false

  /** Check if this mode forks a session */
  def isFork(mode: SessionMode): Boolean = mode match
    case Fork(_) => true
    case _       => false

  /** Extract session ID if resuming/forking */
  def sessionId(mode: SessionMode): Option[SessionId] = mode match
    case Resume(id)      => Some(id)
    case Fork(id)        => Some(id)
    case ResumeAt(id, _) => Some(id)
    case _               => None

  /** Extract message UUID if resuming at specific point */
  def messageUuid(mode: SessionMode): Option[MessageUuid] = mode match
    case ResumeAt(_, uuid) => Some(uuid)
    case _                 => None
end SessionMode
