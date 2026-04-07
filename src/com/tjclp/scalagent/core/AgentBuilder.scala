package com.tjclp.scalagent.core

import com.tjclp.scalagent.tools.ToolName

/** Builds a `TypedAgent` by accumulating capability evidence as phantom intersection types.
  *
  * Each `.withX` method returns a new builder with a wider `C` type,
  * growing the intersection at compile time:
  *
  * {{{
  * AgentBuilder(agent)
  *   .withTools(surface)        // C = Any & CanUseTools[CustomTools]
  *   .withBudget                // C = Any & CanUseTools[CustomTools] & HasBudget
  *   .withSpawnDepth[Depth2]    // C = Any & CanUseTools[CustomTools] & HasBudget & CanSpawn[S[S[Z]]]
  *   .build                     // TypedAgent[..., C]
  * }}}
  *
  * The `agentTransform` callback allows interpreters to wire capability declarations
  * into runtime enforcement. For example, `ClaudeInterpreter.builder()` provides a
  * transform that applies tool restrictions to `AgentOptions`.
  *
  * @tparam P principal type
  * @tparam I input type
  * @tparam O output type
  * @tparam C accumulated phantom capabilities
  */
final class AgentBuilder[P, I, O, C] private[core] (
    agent: Agent[P, I, O],
    config: BuilderConfig,
    agentTransform: (Agent[P, I, O], BuilderConfig) => Agent[P, I, O]
):
  private def updated[C2](newConfig: BuilderConfig): AgentBuilder[P, I, O, C2] =
    new AgentBuilder[P, I, O, C2](agent, newConfig, agentTransform)

  /** Add an explicit tool allowlist. Tools compose with previously added tools.
   *
   * The resulting capability is `CustomTools`, not `AllTools`: the type reflects
   * exactly what is known about the surface, not an unrestricted provider-wide grant.
   *
   * Claude-backed builders expose any `ToolDef`s in the surface through an
   * implicit in-process MCP server at build time. Use `withMcpTools` when you
   * need an explicit server name / provenance rather than the default local one.
   */
  def withTools(surface: ToolSurface): AgentBuilder[P, I, O, C & CanUseTools[CustomTools]] =
    updated(config.copy(tools = config.tools ++ surface))

  /** Grant access to all built-in tools including Bash. Explicit opt-in to unrestricted access.
    * This is the only way to get the "everything" behavior. The phantom type `AllTools`
    * signals that no restriction was applied.
    */
  def withAllTools: AgentBuilder[P, I, O, C & CanUseTools[AllTools]] =
    updated(config.copy(tools = config.tools ++ ToolSurface.allBuiltins, fullToolAccess = true))

  /** Add read-only tool access. Tools compose with previously added tools.
    *
    * Automatically injects `ToolSurface.readOnlyBuiltins` (Read, Grep, Glob)
    * in addition to the provided surface.
    */
  def withReadOnlyTools(surface: ToolSurface): AgentBuilder[P, I, O, C & CanUseTools[ReadOnlyTools]] =
    val combined = config.tools ++ surface
    require(
      combined.isReadOnlyCompatible,
      "withReadOnlyTools only supports tools whose provider allowlist is read-only"
    )
    updated(config.copy(tools = combined ++ ToolSurface.readOnlyBuiltins))

  /** Add spawn capability at the specified Peano depth. */
  def withSpawnDepth[D <: Depth](using d: DepthValue[D]): AgentBuilder[P, I, O, C & CanSpawn[D]] =
    updated(config.copy(runtimeDepth = d.value))

  /** Add budget enforcement capability. */
  def withBudget: AgentBuilder[P, I, O, C & HasBudget] =
    updated(config)

  /** Add memory read capability. */
  def withMemoryRead: AgentBuilder[P, I, O, C & CanReadMemory] =
    updated(config)

  /** Add memory write capability. */
  def withMemoryWrite: AgentBuilder[P, I, O, C & CanWriteMemory] =
    updated(config)

  /** Add human escalation capability. */
  def withEscalation: AgentBuilder[P, I, O, C & CanEscalateHuman] =
    updated(config)

  /** Set the working directory scope. The agent will operate within this directory.
    * Maps to `AgentOptions.cwd` (Claude) or `CodexThreadOptions.workingDirectory` (Codex).
    */
  def withWorkingDirectory(dir: String): AgentBuilder[P, I, O, C & HasDirectoryScope] =
    val scope = config.directoryScope match
      case Some(existing) => existing.copy(cwd = dir)
      case None           => DirectoryScope(dir)
    updated(config.copy(directoryScope = Some(scope)))

  /** Add an additional accessible directory. Requires `withWorkingDirectory` first. */
  def withAdditionalDirectory(dir: String): AgentBuilder[P, I, O, C & HasDirectoryScope] =
    val scope = config.directoryScope match
      case Some(existing) => existing.withAdditional(dir)
      case None           => throw new IllegalStateException(
        "withAdditionalDirectory requires withWorkingDirectory to be called first"
      )
    updated(config.copy(directoryScope = Some(scope)))

  /** Add MCP tools. Composes with existing tools and adds both
   * `CanUseTools[CustomTools]` and `HasMcpTools` capability markers.
   */
  def withMcpTools(surface: mcp.McpToolSurface): AgentBuilder[P, I, O, C & CanUseTools[CustomTools] & mcp.HasMcpTools] =
    updated(config.copy(
      tools = config.tools ++ surface.toToolSurface,
      mcpToolSurfaces = config.mcpToolSurfaces :+ surface
    ))

  /** Add MCP resource access capability. */
  def withMcpResources(surface: mcp.McpResourceSurface): AgentBuilder[P, I, O, C & mcp.HasMcpResources] =
    updated(config)

  /** Add MCP prompt access capability. */
  def withMcpPrompts(surface: mcp.McpPromptSurface): AgentBuilder[P, I, O, C & mcp.HasMcpPrompts] =
    updated(config)

  /** Add A2A delegation capability. */
  def withA2ADelegation: AgentBuilder[P, I, O, C & a2a.CanDelegateA2A] =
    updated(config)

  /** Build the `TypedAgent` with all accumulated capabilities.
    *
    * Applies the `agentTransform` to wire capability declarations into
    * the underlying agent's runtime behavior.
    */
  def build: TypedAgent[P, I, O, C] =
    val configuredAgent = agentTransform(agent, config)
    new TypedAgent[P, I, O, C](configuredAgent, config.tools, config.runtimeDepth)

object AgentBuilder:
  /** Start building from any Agent with identity transform (no runtime enforcement). */
  def apply[P, I, O](agent: Agent[P, I, O]): AgentBuilder[P, I, O, Any] =
    new AgentBuilder(agent, BuilderConfig.empty, (a, _) => a)

  /** Start building with an interpreter-provided transform for runtime enforcement. */
  def withTransform[P, I, O](
      agent: Agent[P, I, O],
      transform: (Agent[P, I, O], BuilderConfig) => Agent[P, I, O]
  ): AgentBuilder[P, I, O, Any] =
    new AgentBuilder(agent, BuilderConfig.empty, transform)
