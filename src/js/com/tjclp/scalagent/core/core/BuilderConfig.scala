package com.tjclp.scalagent.core

import com.tjclp.scalagent.core.mcp.McpToolSurface

/**
 * Bundled runtime state accumulated by `AgentBuilder`.
 *
 * Passed to the interpreter's `agentTransform` at build time so it can
 * wire declared capabilities into provider-specific options.
 */
final case class BuilderConfig(
  tools: ToolSurface = ToolSurface.empty,
  mcpToolSurfaces: List[McpToolSurface] = Nil,
  runtimeDepth: Int = 0,
  fullToolAccess: Boolean = false,
  directoryScope: Option[DirectoryScope] = None)

object BuilderConfig:
  val empty: BuilderConfig = BuilderConfig()
