package com.tjclp.scalagent.a2a

/**
 * Server-side default execution behavior for `message/send`.
 *
 * A2A v1 defaults omitted `returnImmediately` to blocking. `Asynchronous`
 * is an opt-in server policy for deployments that want omitted configuration
 * to return the initial working task immediately. Clients can always choose
 * explicitly with `MessageSendConfiguration.returnImmediately`; legacy
 * `blocking` is still accepted as compatibility input.
 */
enum ExecutionMode derives CanEqual:
  case Asynchronous, Synchronous

object ExecutionMode:
  val Default: ExecutionMode = Synchronous
