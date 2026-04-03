package com.tjclp.scalagent.core

/** Builds a `TypedAgent` by accumulating capability evidence as phantom intersection types.
  *
  * Each `.withX` method returns a new builder with a wider `C` type,
  * growing the intersection at compile time:
  *
  * {{{
  * AgentBuilder(agent)
  *   .withTools(surface)        // C = Any & CanUseTools[AllTools]
  *   .withBudget                // C = Any & CanUseTools[AllTools] & HasBudget
  *   .withSpawnDepth[Depth2]    // C = Any & CanUseTools[AllTools] & HasBudget & CanSpawn[S[S[Z]]]
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
    runtimeDepth: Int,
    agentTransform: (Agent[P, I, O], ToolSurface, Int) => Agent[P, I, O]
):
  /** Add full tool access. Tools compose with previously added tools. */
  def withTools(surface: ToolSurface): AgentBuilder[P, I, O, C & CanUseTools[AllTools]] =
    new AgentBuilder(agent, tools ++ surface, runtimeDepth, agentTransform)

  /** Add read-only tool access. Tools compose with previously added tools. */
  def withReadOnlyTools(surface: ToolSurface): AgentBuilder[P, I, O, C & CanUseTools[ReadOnlyTools]] =
    new AgentBuilder(agent, tools ++ surface, runtimeDepth, agentTransform)

  /** Add spawn capability at the specified Peano depth. */
  def withSpawnDepth[D <: Depth](using d: DepthValue[D]): AgentBuilder[P, I, O, C & CanSpawn[D]] =
    new AgentBuilder(agent, tools, d.value, agentTransform)

  /** Add budget enforcement capability. */
  def withBudget: AgentBuilder[P, I, O, C & HasBudget] =
    new AgentBuilder(agent, tools, runtimeDepth, agentTransform)

  /** Add memory read capability. */
  def withMemoryRead: AgentBuilder[P, I, O, C & CanReadMemory] =
    new AgentBuilder(agent, tools, runtimeDepth, agentTransform)

  /** Add memory write capability. */
  def withMemoryWrite: AgentBuilder[P, I, O, C & CanWriteMemory] =
    new AgentBuilder(agent, tools, runtimeDepth, agentTransform)

  /** Add human escalation capability. */
  def withEscalation: AgentBuilder[P, I, O, C & CanEscalateHuman] =
    new AgentBuilder(agent, tools, runtimeDepth, agentTransform)

  /** Add MCP tools. Composes with existing tools and adds both
    * `CanUseTools[AllTools]` and `HasMcpTools` capability markers.
    */
  def withMcpTools(surface: mcp.McpToolSurface): AgentBuilder[P, I, O, C & CanUseTools[AllTools] & mcp.HasMcpTools] =
    new AgentBuilder(agent, tools ++ surface.toToolSurface, runtimeDepth, agentTransform)

  /** Add MCP resource access capability. */
  def withMcpResources(surface: mcp.McpResourceSurface): AgentBuilder[P, I, O, C & mcp.HasMcpResources] =
    new AgentBuilder(agent, tools, runtimeDepth, agentTransform)

  /** Add MCP prompt access capability. */
  def withMcpPrompts(surface: mcp.McpPromptSurface): AgentBuilder[P, I, O, C & mcp.HasMcpPrompts] =
    new AgentBuilder(agent, tools, runtimeDepth, agentTransform)

  /** Add A2A delegation capability. */
  def withA2ADelegation: AgentBuilder[P, I, O, C & a2a.CanDelegateA2A] =
    new AgentBuilder(agent, tools, runtimeDepth, agentTransform)

  /** Build the `TypedAgent` with all accumulated capabilities.
    *
    * Applies the `agentTransform` to wire capability declarations into
    * the underlying agent's runtime behavior.
    */
  def build: TypedAgent[P, I, O, C] =
    val configuredAgent = agentTransform(agent, tools, runtimeDepth)
    new TypedAgent[P, I, O, C](configuredAgent, tools, runtimeDepth)

object AgentBuilder:
  /** Start building from any Agent with identity transform (no runtime enforcement). */
  def apply[P, I, O](agent: Agent[P, I, O]): AgentBuilder[P, I, O, Any] =
    new AgentBuilder(agent, ToolSurface.empty, 0, (a, _, _) => a)

  /** Start building with an interpreter-provided transform for runtime enforcement. */
  def withTransform[P, I, O](
      agent: Agent[P, I, O],
      transform: (Agent[P, I, O], ToolSurface, Int) => Agent[P, I, O]
  ): AgentBuilder[P, I, O, Any] =
    new AgentBuilder(agent, ToolSurface.empty, 0, transform)
