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
    tools: ToolSurface,
    mcpToolSurfaces: List[mcp.McpToolSurface],
    runtimeDepth: Int,
    agentTransform: (Agent[P, I, O], ToolSurface, List[mcp.McpToolSurface], Int) => Agent[P, I, O]
):
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
    new AgentBuilder(agent, tools ++ surface, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Add read-only tool access. Tools compose with previously added tools.
    *
    * Automatically injects `ToolSurface.readOnlyBuiltins` (Read, Grep, Glob)
    * in addition to the provided surface.
    */
  def withReadOnlyTools(surface: ToolSurface): AgentBuilder[P, I, O, C & CanUseTools[ReadOnlyTools]] =
    val combined = tools ++ surface
    require(
      combined.isReadOnlyCompatible,
      "withReadOnlyTools only supports tools whose provider allowlist is read-only"
    )
    new AgentBuilder(
      agent,
      combined ++ ToolSurface.readOnlyBuiltins,
      mcpToolSurfaces,
      runtimeDepth,
      agentTransform
    )

  /** Add spawn capability at the specified Peano depth. */
  def withSpawnDepth[D <: Depth](using d: DepthValue[D]): AgentBuilder[P, I, O, C & CanSpawn[D]] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, d.value, agentTransform)

  /** Add budget enforcement capability. */
  def withBudget: AgentBuilder[P, I, O, C & HasBudget] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Add memory read capability. */
  def withMemoryRead: AgentBuilder[P, I, O, C & CanReadMemory] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Add memory write capability. */
  def withMemoryWrite: AgentBuilder[P, I, O, C & CanWriteMemory] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Add human escalation capability. */
  def withEscalation: AgentBuilder[P, I, O, C & CanEscalateHuman] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Add MCP tools. Composes with existing tools and adds both
   * `CanUseTools[CustomTools]` and `HasMcpTools` capability markers.
   */
  def withMcpTools(surface: mcp.McpToolSurface): AgentBuilder[P, I, O, C & CanUseTools[CustomTools] & mcp.HasMcpTools] =
    new AgentBuilder(
      agent,
      tools ++ surface.toToolSurface,
      mcpToolSurfaces :+ surface,
      runtimeDepth,
      agentTransform
    )

  /** Add MCP resource access capability. */
  def withMcpResources(surface: mcp.McpResourceSurface): AgentBuilder[P, I, O, C & mcp.HasMcpResources] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Add MCP prompt access capability. */
  def withMcpPrompts(surface: mcp.McpPromptSurface): AgentBuilder[P, I, O, C & mcp.HasMcpPrompts] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Add A2A delegation capability. */
  def withA2ADelegation: AgentBuilder[P, I, O, C & a2a.CanDelegateA2A] =
    new AgentBuilder(agent, tools, mcpToolSurfaces, runtimeDepth, agentTransform)

  /** Build the `TypedAgent` with all accumulated capabilities.
    *
    * Applies the `agentTransform` to wire capability declarations into
    * the underlying agent's runtime behavior.
    */
  def build: TypedAgent[P, I, O, C] =
    val configuredAgent = agentTransform(agent, tools, mcpToolSurfaces, runtimeDepth)
    new TypedAgent[P, I, O, C](configuredAgent, tools, runtimeDepth)

object AgentBuilder:
  /** Start building from any Agent with identity transform (no runtime enforcement). */
  def apply[P, I, O](agent: Agent[P, I, O]): AgentBuilder[P, I, O, Any] =
    new AgentBuilder(agent, ToolSurface.empty, Nil, 0, (a, _, _, _) => a)

  /** Start building with an interpreter-provided transform for runtime enforcement. */
  def withTransform[P, I, O](
      agent: Agent[P, I, O],
      transform: (Agent[P, I, O], ToolSurface, List[mcp.McpToolSurface], Int) => Agent[P, I, O]
  ): AgentBuilder[P, I, O, Any] =
    new AgentBuilder(agent, ToolSurface.empty, Nil, 0, transform)
