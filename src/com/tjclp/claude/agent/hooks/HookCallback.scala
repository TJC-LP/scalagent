package com.tjclp.claude.agent.hooks

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio._
import zio.json.ast.Json

/** Type alias for hook callback functions.
  *
  * A hook callback receives input context and returns an output that controls agent behavior. Callbacks are ZIO effects
  * that can perform async operations.
  *
  * Example usage:
  * {{{
  * val myHook: HookCallback = {
  *   case input: HookInput.PreToolUse if input.toolName == "Bash" =>
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

  /** Create a hook that blocks specific tools */
  def blockTools(toolNames: String*): HookCallback = {
    case input: HookInput.PreToolUse if toolNames.contains(input.toolName) =>
      ZIO.succeed(HookOutput.block(s"Tool ${input.toolName} is blocked"))
    case input: HookInput.PermissionRequest if toolNames.contains(input.toolName) =>
      ZIO.succeed(HookOutput.deny(s"Tool ${input.toolName} is blocked"))
    case _ =>
      ZIO.succeed(HookOutput.continue)
  }

  /** Create a hook that auto-approves specific tools */
  def autoApprove(toolNames: String*): HookCallback = {
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
    val sessionId = raw.session_id.asInstanceOf[String]
    val cwd = raw.cwd.asInstanceOf[String]
    val transcriptPath = raw.transcript_path.asInstanceOf[String]
    val hookEvent = raw.hook_event.asInstanceOf[String]

    hookEvent match
      case "PreToolUse" =>
        HookInput.PreToolUse(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = raw.tool_name.asInstanceOf[String],
          toolInput = parseJson(raw.tool_input),
          toolUseId = raw.tool_use_id.asInstanceOf[String],
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption
        )

      case "PostToolUse" =>
        HookInput.PostToolUse(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = raw.tool_name.asInstanceOf[String],
          toolInput = parseJson(raw.tool_input),
          toolUseId = raw.tool_use_id.asInstanceOf[String],
          toolResponse = raw.tool_response.asInstanceOf[String],
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption
        )

      case "PostToolUseFailure" =>
        HookInput.PostToolUseFailure(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = raw.tool_name.asInstanceOf[String],
          toolInput = parseJson(raw.tool_input),
          toolUseId = raw.tool_use_id.asInstanceOf[String],
          error = raw.error.asInstanceOf[String],
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption
        )

      case "PermissionRequest" =>
        HookInput.PermissionRequest(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = raw.tool_name.asInstanceOf[String],
          toolInput = parseJson(raw.tool_input),
          toolUseId = raw.tool_use_id.asInstanceOf[String],
          agentId = raw.agent_id.asInstanceOf[js.UndefOr[String]].toOption
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
          reason = SessionEndReason.Success // Simplified
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
          subagentId = raw.subagent_id.asInstanceOf[String],
          subagentType = raw.subagent_type.asInstanceOf[String],
          parentToolUseId = raw.parent_tool_use_id.asInstanceOf[String]
        )

      case "SubagentStop" =>
        HookInput.SubagentStop(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          subagentId = raw.subagent_id.asInstanceOf[String],
          subagentType = raw.subagent_type.asInstanceOf[String],
          parentToolUseId = raw.parent_tool_use_id.asInstanceOf[String],
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
