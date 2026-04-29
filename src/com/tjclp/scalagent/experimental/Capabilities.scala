package com.tjclp.scalagent.experimental

import language.experimental.captureChecking
import caps.SharedCapability

/**
 * Capture-checked filesystem sandbox.
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
 *
 * Construction is restricted to the `experimental` package so callers
 * must go through `SandboxedRun` or `ScopedCapabilities`.
 */
final class FileSandbox private[experimental] (val root: String) extends SharedCapability:
  private val nodePath = scala.scalajs.js.Dynamic.global.require("node:path")
  private val nodeFs   = scala.scalajs.js.Dynamic.global.require("node:fs")

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
    val entries  = nodeFs.readdirSync(resolved).asInstanceOf[scala.scalajs.js.Array[String]]
    entries.toList

  /**
   * Resolve a path within the sandbox, rejecting escapes.
   *
   * First performs a lexical check (fast, always works), then resolves
   * symlinks via realpathSync when possible to prevent symlink-based escapes.
   */
  private def resolveSafe(path: String): String =
    val normalizedRoot = nodePath.resolve(root).asInstanceOf[String]
    val resolved       = nodePath.resolve(root, path).asInstanceOf[String]

    // Phase 1: lexical check (catches ".." traversals even when paths don't exist)
    val lexicalRelative = nodePath.relative(normalizedRoot, resolved).asInstanceOf[String]
    val lexicalEscape   =
      lexicalRelative.startsWith("..") || nodePath.isAbsolute(lexicalRelative).asInstanceOf[Boolean]
    if lexicalEscape then throw new SecurityException(s"Path escape attempt: $path resolves outside sandbox $root")

    // Phase 2: symlink check (catches symlinks that point outside the sandbox)
    try
      val realRoot     = nodeFs.realpathSync(normalizedRoot).asInstanceOf[String]
      val realResolved =
        try nodeFs.realpathSync(resolved).asInstanceOf[String]
        catch
          case _: Throwable =>
            // File doesn't exist yet (write case) — resolve parent dir instead
            val parent = nodeFs.realpathSync(nodePath.dirname(resolved).asInstanceOf[String]).asInstanceOf[String]
            nodePath.join(parent, nodePath.basename(resolved).asInstanceOf[String]).asInstanceOf[String]
      val realRelative = nodePath.relative(realRoot, realResolved).asInstanceOf[String]
      val realEscape   =
        realRelative.startsWith("..") || nodePath.isAbsolute(realRelative).asInstanceOf[Boolean]
      if realEscape then
        throw new SecurityException(s"Path escape attempt: $path resolves outside sandbox $root (via symlink)")
      realResolved
    catch
      case e: SecurityException => throw e
      case _: Throwable         => resolved // root doesn't exist yet; lexical check passed
  end resolveSafe
end FileSandbox

/**
 * Capture-checked budget slice.
 *
 * Grants spending authority up to a fixed amount. The compiler prevents
 * it from being copied, leaked, or retained beyond its authorized scope.
 *
 * Construction is restricted to the `experimental` package so callers
 * must go through `SandboxedRun` or `ScopedCapabilities`.
 */
final class BudgetSlice private[experimental] (private var _remaining: Double) extends SharedCapability:
  require(_remaining >= 0, s"Budget cannot be negative: ${_remaining}")

  def remaining: Double = _remaining

  /** Spend from this budget. Throws if insufficient. */
  def spend(amount: Double): Unit =
    require(amount >= 0, s"Cannot spend negative: $amount")
    require(amount <= _remaining, s"Budget exhausted: need $amount, have ${_remaining}")
    _remaining -= amount

  /**
   * Create a child slice with a fraction of the remaining budget.
   * Deducts the child's amount from this budget.
   */
  def childSlice(fraction: Double): BudgetSlice =
    require(fraction > 0 && fraction <= 1.0, s"Fraction must be in (0, 1]: $fraction")
    val childAmount = _remaining * fraction
    _remaining -= childAmount
    BudgetSlice(childAmount)

  def isExhausted: Boolean = _remaining <= 0
end BudgetSlice

/**
 * Capture-checked spawn permit.
 *
 * Authorizes delegation to child agents with a depth limit.
 * Each spawn consumes one level. At depth zero, no further spawning.
 *
 * Construction is restricted to the `experimental` package so callers
 * must go through `SandboxedRun` or `ScopedCapabilities`.
 */
final class SpawnPermit private[experimental] (val maxDepth: Int) extends SharedCapability:
  require(maxDepth >= 0, s"Depth cannot be negative: $maxDepth")

  def canSpawn: Boolean = maxDepth > 0

  def childPermit: Option[SpawnPermit] =
    if maxDepth > 0 then Some(SpawnPermit(maxDepth - 1))
    else None

/**
 * Capture-checked authority to invoke a nondeterministic reviewer.
 *
 * This is intentionally separate from ordinary execution capabilities:
 * using an agent as a judge is an explicit control-plane decision.
 *
 * Construction is restricted to the `experimental` package so callers
 * must go through `SandboxedRun` or `ScopedCapabilities`.
 */
final class ReviewPermit private[experimental] (
  val label: String,
  private var _remainingReviews: Int)
    extends SharedCapability:
  require(_remainingReviews > 0, s"ReviewPermit must allow at least one review, got ${_remainingReviews}")

  def remainingReviews: Int = _remainingReviews

  def canReview: Boolean = _remainingReviews > 0

  /** Consume one review slot. Throws if exhausted. */
  def consume(): Unit =
    require(_remainingReviews > 0, s"ReviewPermit exhausted for '$label'")
    _remainingReviews -= 1
