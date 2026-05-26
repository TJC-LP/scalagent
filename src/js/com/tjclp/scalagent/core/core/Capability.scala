package com.tjclp.scalagent.core

import scala.annotation.implicitNotFound

// ============================================================================
// Capability marker traits — phantom types for intersection-based composition
// ============================================================================

/**
 * Base trait for agent capabilities. Used as phantom types in intersections.
 *
 * Open for extension by protocol packages (MCP, A2A) that add
 * protocol-specific capability markers.
 *
 * Example:
 * {{{
 * type Analyst = CanUseTools[ReadOnlyTools] & HasBudget
 * type Supervisor = FullCaps & CanSpawn[Depth2]
 * }}}
 */
trait Capability

/** Agent can use tools from a tool surface classified as T. */
trait CanUseTools[T <: ToolSet] extends Capability

/** Agent can delegate to child agents at maximum depth D. */
trait CanSpawn[D <: Depth] extends Capability

/** Agent can read from memory. */
trait CanReadMemory extends Capability

/** Agent can write to memory. */
trait CanWriteMemory extends Capability

/** Agent can escalate decisions to a human. */
trait CanEscalateHuman extends Capability

/** Agent has an enforced budget constraint. */
trait HasBudget extends Capability

/** Agent has a directory scope (cwd and/or additionalDirectories). */
trait HasDirectoryScope extends Capability

// ============================================================================
// Tool set classification markers
// ============================================================================

/** Marker for tool set classification, tracked at the type level. */
sealed trait ToolSet

/** Full tool access (read, write, execute). */
sealed trait AllTools extends ToolSet

/** Read-only tool access (Read, Grep, Glob only). */
sealed trait ReadOnlyTools extends ToolSet

/** Custom tool set — user-defined restriction. */
sealed trait CustomTools extends ToolSet

// ============================================================================
// Common capability type aliases
// ============================================================================

/** Read-only analyst: can read and has budget. */
type ReadOnlyCaps = CanUseTools[ReadOnlyTools] & HasBudget

/** Full-access agent: all tools, budget, human escalation. */
type FullCaps = CanUseTools[AllTools] & HasBudget & CanEscalateHuman

/** Supervisor: full access plus delegation at depth D. */
type SupervisorCaps[D <: Depth] = FullCaps & CanSpawn[D]

// ============================================================================
// Type class evidence for extracting capabilities from intersection types
// ============================================================================

/**
 * Evidence that capability set C includes spawn ability.
 *
 * Resolves from intersection types:
 * - `CanSpawn[D]` directly
 * - `CanSpawn[D] & R` (left position)
 * - `L & R` where R contains CanSpawn (recursive right)
 */
@implicitNotFound(
  "This agent does not have CanSpawn capability — delegation is forbidden by the type system"
)
trait HasSpawn[C]:
  type MaxDepth <: Depth

object HasSpawn:
  given direct[D <: Depth]: HasSpawn[CanSpawn[D]] with
    type MaxDepth = D
  given left[D <: Depth, R]: HasSpawn[CanSpawn[D] & R] with
    type MaxDepth = D
  given right[L, R](using ev: HasSpawn[R]): HasSpawn[L & R] with
    type MaxDepth = ev.MaxDepth

/** Evidence that capability set C includes tool use. */
@implicitNotFound(
  "This agent does not have CanUseTools capability"
)
trait HasToolsCap[C]:
  type Tools <: ToolSet

object HasToolsCap:
  given direct[T <: ToolSet]: HasToolsCap[CanUseTools[T]] with
    type Tools = T
  given left[T <: ToolSet, R]: HasToolsCap[CanUseTools[T] & R] with
    type Tools = T
  given right[L, R](using ev: HasToolsCap[R]): HasToolsCap[L & R] with
    type Tools = ev.Tools
