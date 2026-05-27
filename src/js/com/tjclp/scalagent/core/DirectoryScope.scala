package com.tjclp.scalagent.core

import scala.scalajs.js
import zio.*
import zio.json.ast.Json
import com.tjclp.scalagent.hooks.{HookCallback, HookInput, HookOutput}
import com.tjclp.scalagent.tools.ToolName

/**
 * Directory scope configuration for agent filesystem access.
 *
 * Maps to `AgentOptions.cwd` / `AgentOptions.additionalDirectories` (Claude)
 * and `CodexThreadOptions.workingDirectory` / `CodexThreadOptions.additionalDirectories` (Codex).
 *
 * Enforcement uses a `PreToolUse` hook that intercepts file-accessing tools
 * and validates paths against allowed directory roots. Unlike `canUseTool`
 * (which never fires for read-only tools) or `settings.permissions` deny rules
 * (which always override allow rules), `PreToolUse` hooks fire for ALL tools
 * and can return `permissionDecision: "deny"` to block individual tool calls.
 */
final case class DirectoryScope(
  cwd: String,
  additionalDirectories: List[String] = Nil):
  def withAdditional(dir: String): DirectoryScope =
    copy(additionalDirectories = additionalDirectories :+ dir)

  private def allRoots: List[String] = cwd :: additionalDirectories

  /**
   * Create a PreToolUse hook that enforces this directory scope.
   *
   * The hook intercepts file-accessing tools (Read, Write, Edit, Grep, Glob,
   * NotebookEdit) and validates that file paths resolve within the allowed
   * directory roots. Bash is denied by default because its free-form command
   * strings cannot be reliably parsed for path references (`cat /etc/passwd`,
   * `$(echo /etc)/shadow`, etc.). Grep without `path` is allowed since it
   * defaults to cwd, while Glob validates both `path` and `pattern` because a
   * pattern like `../**/*` can escape cwd even when `path` is omitted.
   *
   * Path validation uses two phases:
   *   1. Lexical check — catches `..` traversals (fast, always works)
   *   2. Symlink check — catches symlinks pointing outside scope (when filesystem exists)
   */
  def toHook: HookCallback =
    case input: HookInput.PreToolUse =>
      val toolName = input.toolName
      if toolName == ToolName.Bash then
        ZIO.succeed(
          HookOutput.denyTool(
            "Bash is denied in directory-scoped agents (commands cannot be reliably path-checked). " +
              "Remove directory scope or use file tools (Read, Grep, Glob) instead."
          )
        )
      else if ToolName.isFileOperation(toolName) then
        DirectoryScope.extractScopeTargets(toolName, input.toolInput, cwd).find(target => !isWithinScope(target)) match
          case None =>
            // No path-like target to check (for example, Grep without an explicit path).
            ZIO.succeed(HookOutput.continue)
          case Some(target) =>
            ZIO.succeed(
              HookOutput.denyTool(
                s"Target '$target' is outside the allowed directories: ${allRoots.mkString(", ")}"
              )
            )
      else ZIO.succeed(HookOutput.continue)
    case _ => ZIO.succeed(HookOutput.continue)
  end toHook

  /**
   * Check whether a path resolves within any allowed directory root.
   *
   * Two-phase validation:
   *   1. Lexical — resolve and check relative path for `..` or absolute escape
   *   2. Symlink — if filesystem exists, resolve real paths and recheck
   */
  private[core] def isWithinScope(path: String): Boolean =
    import DirectoryScope.{nodePath, nodeFs}

    val resolved = if nodePath.isAbsolute(path).asInstanceOf[Boolean] then nodePath.resolve(path).asInstanceOf[String]
    else nodePath.resolve(cwd, path).asInstanceOf[String]

    def lexicallyWithin(root: String): Boolean =
      val normalizedRoot = nodePath.resolve(root).asInstanceOf[String]
      val relative       = nodePath.relative(normalizedRoot, resolved).asInstanceOf[String]
      !relative.startsWith("..") && !nodePath.isAbsolute(relative).asInstanceOf[Boolean]

    def realPathWithin(root: String): Boolean =
      try
        val realRoot     = nodeFs.realpathSync(nodePath.resolve(root).asInstanceOf[String]).asInstanceOf[String]
        val realResolved =
          try nodeFs.realpathSync(resolved).asInstanceOf[String]
          catch
            case _: scala.scalajs.js.JavaScriptException =>
              // File doesn't exist yet (write case) — resolve parent dir instead
              val parent = nodeFs.realpathSync(nodePath.dirname(resolved).asInstanceOf[String]).asInstanceOf[String]
              nodePath.join(parent, nodePath.basename(resolved).asInstanceOf[String]).asInstanceOf[String]
        val relative = nodePath.relative(realRoot, realResolved).asInstanceOf[String]
        !relative.startsWith("..") && !nodePath.isAbsolute(relative).asInstanceOf[Boolean]
      catch
        case _: scala.scalajs.js.JavaScriptException =>
          // Root directory doesn't exist on this filesystem; lexical check is sufficient.
          // We rely on the lexicallyWithin check having already passed.
          true

    allRoots.exists(root => lexicallyWithin(root) && realPathWithin(root))
  end isWithinScope
end DirectoryScope

object DirectoryScope:
  private lazy val nodePath = js.Dynamic.global.require("node:path")
  private lazy val nodeFs   = js.Dynamic.global.require("node:fs")

  /** Extract every path-like target that must stay within scope for a tool call. */
  private[core] def extractScopeTargets(
    toolName: ToolName,
    toolInput: Json,
    cwd: String,
  ): List[String] =
    val obj                                 = toolInput.asObject
    def field(name: String): Option[String] =
      obj.flatMap(_.get(name)).flatMap(_.asString)

    toolName match
      case ToolName.Read | ToolName.Write | ToolName.Edit =>
        field("file_path").toList
      case ToolName.NotebookEdit =>
        field("notebook_path").toList
      case ToolName.Grep =>
        field("path").toList
      case ToolName.Glob =>
        val searchPath    = field("path")
        val pattern       = field("pattern")
        val patternTarget = pattern.map(resolveGlobTarget(cwd, searchPath, _))
        (searchPath.toList ++ patternTarget.toList).distinct
      case _ =>
        Nil
  end extractScopeTargets

  private def resolveGlobTarget(
    cwd: String,
    searchPath: Option[String],
    pattern: String,
  ): String =
    searchPath match
      case Some(path) =>
        val base =
          if nodePath.isAbsolute(path).asInstanceOf[Boolean] then path
          else nodePath.resolve(cwd, path).asInstanceOf[String]
        nodePath.resolve(base, pattern).asInstanceOf[String]
      case None =>
        pattern
end DirectoryScope
