package com.tjclp.scalagent.a2a

/**
 * Server-side default execution behavior for `message/send`.
 *
 * The A2A SDK's wire-level control is `MessageSendConfiguration.blocking`.
 * `Asynchronous` makes omitted `blocking` mean `false`; clients that need
 * legacy synchronous behavior can still send `blocking = true`.
 */
enum ExecutionMode derives CanEqual:
  case Asynchronous, Synchronous

object ExecutionMode:
  val Default: ExecutionMode = Asynchronous
