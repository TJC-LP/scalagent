package com.tjclp.scalagent.permissions

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{SubagentId, ToolUseId}

/**
 * Type alias for the canUseTool permission handler.
 *
 * This callback is invoked before each tool execution to decide whether to allow it.
 *
 * Example usage:
 * {{{
 * val handler: CanUseTool = (toolName, input, ctx) =>
 *   if toolName == ToolName.Bash then
 *     ZIO.succeed(PermissionResult.deny("Bash is disabled"))
 *   else
 *     ZIO.succeed(PermissionResult.allow)
 * }}}
 */
type CanUseTool = (ToolName, Json, PermissionContext) => Task[PermissionResult]

/**
 * Context provided to canUseTool handler.
 *
 * @param suggestions
 *   Pre-computed permission suggestions from the SDK
 * @param blockedPath
 *   If a file path was blocked, which path
 * @param decisionReason
 *   Why permission was requested
 * @param toolUseId
 *   Unique ID for this tool invocation
 * @param agentId
 *   Subagent ID if running in a subagent
 */
final case class PermissionContext(
  suggestions: List[PermissionUpdate] = Nil,
  blockedPath: Option[String] = None,
  decisionReason: Option[String] = None,
  toolUseId: ToolUseId,
  agentId: Option[SubagentId] = None)

object CanUseTool:
  import scala.concurrent.ExecutionContext.Implicits.global

  /** Always allow all tools */
  val allowAll: CanUseTool = (
    _,
    _,
    _,
  ) => ZIO.succeed(PermissionResult.allow)

  /** Always deny with a message */
  def denyAll(reason: String): CanUseTool = (
    _,
    _,
    _,
  ) => ZIO.succeed(PermissionResult.deny(reason))

  /**
   * Allow specific tools, deny others.
   *
   * Example:
   * {{{
   * CanUseTool.allowOnly(ToolName.Read, ToolName.Glob, ToolName.Grep)
   * }}}
   */
  def allowOnly(allowedTools: ToolName*): CanUseTool = (
    toolName,
    _,
    _,
  ) =>
    if allowedTools.contains(toolName) then ZIO.succeed(PermissionResult.allow)
    else ZIO.succeed(PermissionResult.deny(s"Tool ${toolName.raw} is not allowed"))

  /**
   * Deny specific tools, allow others.
   *
   * Example:
   * {{{
   * CanUseTool.denyTools(ToolName.Bash, ToolName.Write)
   * }}}
   */
  def denyTools(deniedTools: ToolName*): CanUseTool = (
    toolName,
    _,
    _,
  ) =>
    if deniedTools.contains(toolName) then ZIO.succeed(PermissionResult.deny(s"Tool ${toolName.raw} is blocked"))
    else ZIO.succeed(PermissionResult.allow)

  /** Compose two callbacks: first runs, and if it denies, short-circuit. Otherwise delegate to second. */
  def compose(first: CanUseTool, second: CanUseTool): CanUseTool =
    (toolName,
      input,
      ctx,
    ) =>
      first(toolName, input, ctx).flatMap {
        case deny: PermissionResult.Deny => ZIO.succeed(deny)
        case _                           => second(toolName, input, ctx)
      }

  /**
   * Convert a Scala CanUseTool handler to JavaScript function for SDK.
   *
   * This bridges the ZIO-based handler to the SDK's expected JS function format.
   */
  def toRawJs(
    handler: CanUseTool,
    runtime: Runtime[Any],
  ): js.Function3[String, js.Any, js.Dynamic, js.Promise[js.Object]] =
    (rawToolName: String,
      input: js.Any,
      options: js.Dynamic,
    ) =>
      val toolName  = ToolName(rawToolName)
      val inputJson = parseJson(input)
      val context   = parseContext(options)
      val effect    = handler(toolName, inputJson, context).map(_.toRaw)

      Unsafe.unsafe { implicit unsafe => runtime.unsafe.runToFuture(effect).toJSPromise }

  private def parseJson(value: js.Any): Json =
    val jsonStr = js.JSON.stringify(value)
    jsonStr.fromJson[Json].getOrElse(Json.Null)

  private def parseContext(options: js.Dynamic): PermissionContext =
    PermissionContext(
      suggestions = parseSuggestions(options.suggestions),
      blockedPath = options.blockedPath.asInstanceOf[js.UndefOr[String]].toOption,
      decisionReason = options.decisionReason.asInstanceOf[js.UndefOr[String]].toOption,
      toolUseId = ToolUseId(options.toolUseID.asInstanceOf[String]),
      agentId = options.agentID.asInstanceOf[js.UndefOr[String]].toOption.map(SubagentId.apply),
    )

  private def parseSuggestions(raw: js.Any): List[PermissionUpdate] =
    if js.isUndefined(raw) || raw == null then Nil
    else
      val arr = raw.asInstanceOf[js.Array[js.Dynamic]]
      arr.toList.flatMap(PermissionUpdate.fromRaw)
end CanUseTool
