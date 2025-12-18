package com.tjclp.scalagent.session

/** Phantom type markers for compile-time session state tracking.
  *
  * These types have no runtime representation - they exist purely to enforce
  * correct usage at compile time. Operations that require an open session
  * will fail to compile if called on a closed session.
  *
  * Example:
  * {{{
  * val session: ClaudeSession[Open] = ...
  * session.ask("Hello")  // Compiles - session is Open
  *
  * val closed: ClaudeSession[Closed] = session.close
  * closed.ask("Hello")   // Compile error! Cannot prove Closed =:= Open
  * }}}
  */
sealed trait SessionState

/** Marker for an open, active session that can send/receive messages. */
sealed trait Open extends SessionState

/** Marker for a closed session that can no longer be used for communication. */
sealed trait Closed extends SessionState
