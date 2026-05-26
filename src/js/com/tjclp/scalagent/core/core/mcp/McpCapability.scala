package com.tjclp.scalagent.core.mcp

import com.tjclp.scalagent.core.Capability

/** Agent has access to MCP-provided tools. */
trait HasMcpTools extends Capability

/** Agent has access to MCP-provided resources. */
trait HasMcpResources extends Capability

/** Agent has access to MCP-provided prompts. */
trait HasMcpPrompts extends Capability

/** Full MCP capability — tools, resources, and prompts. */
type FullMcpCaps = HasMcpTools & HasMcpResources & HasMcpPrompts
