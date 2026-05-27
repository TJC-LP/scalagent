package com.tjclp.scalagent.a2a

import zio.*
import com.tjclp.scalagent.config.AgentOptions
import com.tjclp.scalagent.core.AgentWorkspace

/**
 * Per-invocation customization carrier returned by
 * [[A2AServerLive.Config.invocationPreparer]]. Lets a server intercept a
 * single A2A `message/send` call to:
 *
 *   - Override the prompt seen by the inner agent (e.g., prepend a
 *     manifest of files staged from incoming `Part.File` payloads).
 *   - Per-request mutate the [[AgentOptions]] (e.g., swap the system
 *     prompt for context-specific renders).
 *   - Surface a typed [[AgentWorkspace]] alongside the prompt so
 *     power-user preparers can introspect staged inputs structurally
 *     instead of relying on prompt-string manifests.
 *   - Compute and publish [[Artifact]]s after the agent returns
 *     (e.g., diff a per-task tmp dir to find files the agent wrote).
 *   - Run cleanup that fires on success, failure, AND interruption
 *     via ZIO `.ensuring`.
 *
 * All fields default to identity / no-op / `None` so callers pass
 * only the pieces they need. The `prompt` field remains the single
 * source of truth for what the LLM actually sees — `workspace` is
 * additional structure, not a substitute.
 */
final case class InvocationContext(
  prompt: String,
  optionsModifier: AgentOptions => AgentOptions = identity,
  workspace: Option[AgentWorkspace] = None,
  artifactsAfter: Task[List[Artifact]] = ZIO.succeed(Nil),
  cleanup: Task[Unit] = ZIO.unit)
