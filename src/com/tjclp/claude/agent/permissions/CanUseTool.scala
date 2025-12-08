package com.tjclp.claude.agent.permissions

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio._
import zio.json._
import zio.json.ast.Json
import com.tjclp.claude.agent.hooks.PermissionSuggestion

/** Type alias for the canUseTool permission handler.
  *
  * This callback is invoked before each tool execution to decide whether to allow it.
  *
  * Example usage:
  * {{{
  * val handler: CanUseTool = (toolName, input, ctx) =>
  *   if toolName == "Bash" then
  *     ZIO.succeed(PermissionResult.deny("Bash is disabled"))
  *   else
  *     ZIO.succeed(PermissionResult.allow)
  * }}}
  */
type CanUseTool = (String, Json, PermissionContext) => Task[PermissionResult]

/** Context provided to canUseTool handler.
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
    suggestions: List[PermissionSuggestion] = Nil,
    blockedPath: Option[String] = None,
    decisionReason: Option[String] = None,
    toolUseId: String,
    agentId: Option[String] = None
)

object PermissionContext:
  given JsonDecoder[PermissionContext] = DeriveJsonDecoder.gen[PermissionContext]
  given JsonEncoder[PermissionContext] = DeriveJsonEncoder.gen[PermissionContext]

object CanUseTool:
  import scala.concurrent.ExecutionContext.Implicits.global

  /** Always allow all tools */
  val allowAll: CanUseTool = (_, _, _) => ZIO.succeed(PermissionResult.allow)

  /** Always deny with a message */
  def denyAll(reason: String): CanUseTool = (_, _, _) =>
    ZIO.succeed(PermissionResult.deny(reason))

  /** Allow specific tools, deny others */
  def allowOnly(allowedTools: String*): CanUseTool = (toolName, _, _) =>
    if allowedTools.contains(toolName) then ZIO.succeed(PermissionResult.allow)
    else ZIO.succeed(PermissionResult.deny(s"Tool $toolName is not allowed"))

  /** Deny specific tools, allow others */
  def denyTools(deniedTools: String*): CanUseTool = (toolName, _, _) =>
    if deniedTools.contains(toolName) then
      ZIO.succeed(PermissionResult.deny(s"Tool $toolName is blocked"))
    else ZIO.succeed(PermissionResult.allow)

  /** Convert a Scala CanUseTool handler to JavaScript function for SDK.
    *
    * This bridges the ZIO-based handler to the SDK's expected JS function format.
    */
  def toRawJs(
      handler: CanUseTool,
      runtime: Runtime[Any]
  ): js.Function3[String, js.Any, js.Dynamic, js.Promise[js.Object]] =
    (toolName: String, input: js.Any, options: js.Dynamic) => {
      val inputJson = parseJson(input)
      val context = parseContext(options)
      val effect = handler(toolName, inputJson, context).map(_.toRaw)

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(effect).toJSPromise
      }
    }

  private def parseJson(value: js.Any): Json =
    val jsonStr = js.JSON.stringify(value)
    jsonStr.fromJson[Json].getOrElse(Json.Null)

  private def parseContext(options: js.Dynamic): PermissionContext =
    PermissionContext(
      suggestions = Nil, // TODO: Parse suggestions array
      blockedPath = options.blockedPath.asInstanceOf[js.UndefOr[String]].toOption,
      decisionReason = options.decisionReason.asInstanceOf[js.UndefOr[String]].toOption,
      toolUseId = options.toolUseID.asInstanceOf[String],
      agentId = options.agentID.asInstanceOf[js.UndefOr[String]].toOption
    )
