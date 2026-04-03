package com.tjclp.scalagent.experimental

import language.experimental.captureChecking
import caps.SharedCapability

/** Capture-checked filesystem sandbox.
  *
  * A `FileSandbox` is a capability that grants access to a specific
  * directory tree. Because it extends `SharedCapability`, the compiler
  * tracks it through types: any value that references a `FileSandbox`
  * must declare it in its capture set.
  *
  * This means:
  *   - An agent can't store the sandbox in a field that outlives the run
  *   - An agent can't return a closure that captures the sandbox
  *   - A child agent can only use the sandbox if it was explicitly passed
  */
class FileSandbox(val root: String) extends SharedCapability:
  private val nodePath = scala.scalajs.js.Dynamic.global.require("node:path")
  private val nodeFs = scala.scalajs.js.Dynamic.global.require("node:fs")

  /** Read a file relative to the sandbox root. */
  def read(path: String): String =
    val resolved = resolveSafe(path)
    nodeFs.readFileSync(resolved, "utf-8").asInstanceOf[String]

  /** Write a file relative to the sandbox root. */
  def write(path: String, content: String): Unit =
    val resolved = resolveSafe(path)
    nodeFs.writeFileSync(resolved, content)

  /** List files in a directory relative to the sandbox root. */
  def list(path: String = "."): List[String] =
    val resolved = resolveSafe(path)
    val entries = nodeFs.readdirSync(resolved).asInstanceOf[scala.scalajs.js.Array[String]]
    entries.toList

  /** Resolve a path within the sandbox, rejecting escapes. */
  private def resolveSafe(path: String): String =
    val resolved = nodePath.resolve(root, path).asInstanceOf[String]
    val normalizedRoot = nodePath.resolve(root).asInstanceOf[String]
    if !resolved.startsWith(normalizedRoot) then
      throw new SecurityException(s"Path escape attempt: $path resolves outside sandbox $root")
    resolved

/** Capture-checked budget slice.
  *
  * Grants spending authority up to a fixed amount. The compiler prevents
  * it from being copied, leaked, or retained beyond its authorized scope.
  */
class BudgetSlice(private var _remaining: Double) extends SharedCapability:
  require(_remaining >= 0, s"Budget cannot be negative: ${_remaining}")

  def remaining: Double = _remaining

  /** Spend from this budget. Throws if insufficient. */
  def spend(amount: Double): Unit =
    require(amount >= 0, s"Cannot spend negative: $amount")
    require(amount <= _remaining, s"Budget exhausted: need $amount, have ${_remaining}")
    _remaining -= amount

  /** Create a child slice with a fraction of the remaining budget.
    * Deducts the child's amount from this budget.
    */
  def childSlice(fraction: Double): BudgetSlice =
    require(fraction > 0 && fraction <= 1.0, s"Fraction must be in (0, 1]: $fraction")
    val childAmount = _remaining * fraction
    _remaining -= childAmount
    BudgetSlice(childAmount)

  def isExhausted: Boolean = _remaining <= 0

/** Capture-checked spawn permit.
  *
  * Authorizes delegation to child agents with a depth limit.
  * Each spawn consumes one level. At depth zero, no further spawning.
  */
class SpawnPermit(val maxDepth: Int) extends SharedCapability:
  require(maxDepth >= 0, s"Depth cannot be negative: $maxDepth")

  def canSpawn: Boolean = maxDepth > 0

  def childPermit: Option[SpawnPermit] =
    if maxDepth > 0 then Some(SpawnPermit(maxDepth - 1))
    else None
