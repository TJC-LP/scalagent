package com.tjclp.scalagent.config

import com.tjclp.scalagent.types.SessionId

/** Session mode for multi-turn conversations.
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
  * val opts = AgentOptions.default.withSessionMode(SessionMode.Resume("session-abc123"))
  * }}}
  */
enum SessionMode:

  /** Start a fresh session (default behavior) */
  case New

  /** Continue the current session */
  case Continue

  /** Resume a specific session by ID */
  case Resume(sessionId: SessionId)

object SessionMode:

  /** Default session mode - start fresh */
  val default: SessionMode = New

  /** Check if this mode resumes a specific session */
  def isResume(mode: SessionMode): Boolean = mode match
    case Resume(_) => true
    case _         => false

  /** Extract session ID if resuming */
  def sessionId(mode: SessionMode): Option[SessionId] = mode match
    case Resume(id) => Some(id)
    case _          => None
