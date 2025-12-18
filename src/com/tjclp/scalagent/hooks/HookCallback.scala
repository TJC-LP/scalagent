package com.tjclp.scalagent.hooks

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio._
import zio.json.ast.Json
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{SessionId, SubagentId, ToolUseId}

/** Type alias for hook callback functions.
  *
  * A hook callback receives input context and returns an output that controls agent behavior. Callbacks are ZIO effects
  * that can perform async operations.
  *
  * Example usage:
  * {{{
  * val myHook: HookCallback = {
  *   case input: HookInput.PreToolUse if input.toolName == ToolName.Bash =>
  *     ZIO.succeed(HookOutput.deny("Bash commands are not allowed"))
  *   case _ =>
  *     ZIO.succeed(HookOutput.continue)
  * }
  * }}}
  */
type HookCallback = HookInput => Task[HookOutput]

object HookCallback:

  /** Create a simple hook that always continues */
  val alwaysContinue: HookCallback = _ => ZIO.succeed(HookOutput.continue)

  /** Create a hook that blocks specific tools.
    *
    * Example:
    * {{{
    * val hook = HookCallback.blockTools(ToolName.Bash, ToolName.Write)
    * }}}
    */
  def blockTools(toolNames: ToolName*): HookCallback = {
    case input: HookInput.PreToolUse if toolNames.contains(input.toolName) =>
      ZIO.succeed(HookOutput.block(s"Tool ${input.toolName.raw} is blocked"))
    case input: HookInput.PermissionRequest if toolNames.contains(input.toolName) =>
      ZIO.succeed(HookOutput.deny(s"Tool ${input.toolName.raw} is blocked"))
    case _ =>
      ZIO.succeed(HookOutput.continue)
  }

  /** Create a hook that auto-approves specific tools.
    *
    * Example:
    * {{{
    * val hook = HookCallback.autoApprove(ToolName.Read, ToolName.Glob)
    * }}}
    */
  def autoApprove(toolNames: ToolName*): HookCallback = {
    case input: HookInput.PermissionRequest if toolNames.contains(input.toolName) =>
      ZIO.succeed(HookOutput.approve)
    case _ =>
      ZIO.succeed(HookOutput.continue)
  }

  /** Create a hook that logs all events */
  def logging(logger: String => Task[Unit]): HookCallback = { input =>
    val eventType = input.getClass.getSimpleName
    logger(s"Hook event: $eventType for session ${input.sessionId}") *>
      ZIO.succeed(HookOutput.continue)
  }

  /** Combine multiple hooks - runs all and uses the first non-continue result */
  def combine(hooks: HookCallback*): HookCallback = { input =>
    ZIO.foreach(hooks.toList)(hook => hook(input)).map { outputs =>
      outputs.find {
        case _: HookOutput.Continue => false
        case _                      => true
      }.getOrElse(HookOutput.continue)
    }
  }

  /** Convert a Scala hook callback to a JavaScript function for the SDK.
    *
    * This bridges the ZIO-based callback to the SDK's expected JavaScript function format.
    */
  def toRawJs(callback: HookCallback, runtime: Runtime[Any]): js.Function1[js.Dynamic, js.Promise[js.Object]] =
    (rawInput: js.Dynamic) => {
      val input = parseHookInput(rawInput)
      val effect = callback(input).map(_.toRaw)
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(effect).toJSPromise
      }
    }

  /** Parse raw JavaScript hook input to Scala type.
    *
    * This converts the SDK's hook input format to our typed HookInput ADT.
    */
  private def parseHookInput(raw: js.Dynamic): HookInput =
    val sessionId = SessionId(raw.session_id.asInstanceOf[String])
    val cwd = raw.cwd.asInstanceOf[String]
    val transcriptPath = raw.transcript_path.asInstanceOf[String]
    val hookEvent = raw.hook_event.asInstanceOf[String]

    hookEvent match
      case "PreToolUse" =>
        HookInput.PreToolUse(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = ToolName(raw.tool_name.asInstanceOf[String]),
          toolInput = parseJson(raw.tool_input),
          toolUseId = ToolUseId(raw.tool_use_id.asInstanceOf[String]),
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption.map(SubagentId.apply)
        )

      case "PostToolUse" =>
        HookInput.PostToolUse(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = ToolName(raw.tool_name.asInstanceOf[String]),
          toolInput = parseJson(raw.tool_input),
          toolUseId = ToolUseId(raw.tool_use_id.asInstanceOf[String]),
          toolResponse = raw.tool_response.asInstanceOf[String],
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption.map(SubagentId.apply)
        )

      case "PostToolUseFailure" =>
        HookInput.PostToolUseFailure(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = ToolName(raw.tool_name.asInstanceOf[String]),
          toolInput = parseJson(raw.tool_input),
          toolUseId = ToolUseId(raw.tool_use_id.asInstanceOf[String]),
          error = raw.error.asInstanceOf[String],
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption.map(SubagentId.apply)
        )

      case "PermissionRequest" =>
        HookInput.PermissionRequest(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = ToolName(raw.tool_name.asInstanceOf[String]),
          toolInput = parseJson(raw.tool_input),
          toolUseId = ToolUseId(raw.tool_use_id.asInstanceOf[String]),
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption.map(SubagentId.apply)
        )

      case "Notification" =>
        HookInput.Notification(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          message = raw.message.asInstanceOf[String]
        )

      case "UserPromptSubmit" =>
        HookInput.UserPromptSubmit(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          prompt = raw.prompt.asInstanceOf[String]
        )

      case "SessionStart" =>
        HookInput.SessionStart(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          isResume = raw.is_resume.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)
        )

      case "SessionEnd" =>
        HookInput.SessionEnd(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          reason = parseSessionEndReason(raw.reason.asInstanceOf[js.UndefOr[String]].toOption),
          totalCostUsd = raw.total_cost_usd.asInstanceOf[js.UndefOr[Double]].toOption
        )

      case "Stop" =>
        HookInput.Stop(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          reason = raw.reason.asInstanceOf[js.UndefOr[String]].getOrElse("unknown")
        )

      case "SubagentStart" =>
        HookInput.SubagentStart(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          subagentId = SubagentId(raw.subagent_id.asInstanceOf[String]),
          subagentType = raw.subagent_type.asInstanceOf[String],
          parentToolUseId = ToolUseId(raw.parent_tool_use_id.asInstanceOf[String])
        )

      case "SubagentStop" =>
        HookInput.SubagentStop(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          subagentId = SubagentId(raw.subagent_id.asInstanceOf[String]),
          subagentType = raw.subagent_type.asInstanceOf[String],
          parentToolUseId = ToolUseId(raw.parent_tool_use_id.asInstanceOf[String]),
          success = raw.success.asInstanceOf[js.UndefOr[Boolean]].getOrElse(true)
        )

      case "PreCompact" =>
        HookInput.PreCompact(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          currentTokens = raw.current_tokens.asInstanceOf[Int],
          trigger = CompactTrigger.Auto
        )

      case other =>
        // Fallback for unknown events
        HookInput.Notification(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          message = s"Unknown hook event: $other"
        )

  private def parseJson(value: js.Any): Json =
    import zio.json._
    val jsonStr = js.JSON.stringify(value)
    jsonStr.fromJson[Json].getOrElse(Json.Null)

  private def parseSessionEndReason(raw: Option[String]): SessionEndReason =
    raw match
      case Some("success")     => SessionEndReason.Success
      case Some("error")       => SessionEndReason.Error
      case Some("interrupted") => SessionEndReason.Interrupted
      case Some("max_turns")   => SessionEndReason.MaxTurns
      case Some("max_budget")  => SessionEndReason.MaxBudget
      case _                   => SessionEndReason.Success

  // ============================================================================
  // Hook Combinator Extension Methods
  // ============================================================================

  extension (hook: HookCallback)

    /** Only run this hook when the predicate matches, otherwise continue.
      *
      * Example:
      * {{{
      * val logOnlyBash = HookCallback.logging(println)
      *   .when(_.isInstanceOf[HookInput.PreToolUse])
      * }}}
      */
    def when(predicate: HookInput => Boolean): HookCallback = { input =>
      if predicate(input) then hook(input)
      else ZIO.succeed(HookOutput.continue)
    }

    /** Skip this hook when the predicate matches (inverse of when).
      *
      * Example:
      * {{{
      * val blockNonRead = HookCallback.blockTools(ToolName.Bash)
      *   .unless(input => input.toolName == ToolName.Read)
      * }}}
      */
    def unless(predicate: HookInput => Boolean): HookCallback =
      hook.when(input => !predicate(input))

    /** Chain: run this hook first, if it continues then run the other hook.
      *
      * Example:
      * {{{
      * val pipeline = loggingHook.andThen(securityHook).andThen(rateLimitHook)
      * }}}
      */
    def andThen(other: HookCallback): HookCallback = { input =>
      hook(input).flatMap {
        case c: HookOutput.Continue => other(input)
        case nonContinue            => ZIO.succeed(nonContinue)
      }
    }

    /** Fallback: run this hook, if it blocks/denies then try the other hook.
      *
      * Example:
      * {{{
      * val lenientSecurity = strictHook.orElse(permissiveHook)
      * }}}
      */
    def orElse(fallback: HookCallback): HookCallback = { input =>
      hook(input).flatMap {
        case _: HookOutput.Block    => fallback(input)
        case d: HookOutput.Decision if !d.approve => fallback(input)
        case other                  => ZIO.succeed(other)
      }
    }

    /** Apply a transformation to the hook output.
      *
      * Example:
      * {{{
      * val withMessage = hook.mapOutput {
      *   case c: HookOutput.Continue => c.copy(systemMessage = Some("Processed"))
      *   case other => other
      * }
      * }}}
      */
    def mapOutput(f: HookOutput => HookOutput): HookCallback = { input =>
      hook(input).map(f)
    }

    /** Filter inputs before passing to this hook.
      *
      * Example:
      * {{{
      * val onlyToolEvents = hook.filter {
      *   case _: HookInput.PreToolUse | _: HookInput.PostToolUse => true
      *   case _ => false
      * }
      * }}}
      */
    def filter(predicate: HookInput => Boolean): HookCallback =
      hook.when(predicate)
