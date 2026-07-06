package com.tjclp.scalagent.hooks

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import zio.json.ast.Json
import com.tjclp.scalagent.config.PermissionMode
import com.tjclp.scalagent.messages.AssistantMessageError
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{SessionId, SubagentId, ToolUseId}

/**
 * Type alias for hook callback functions.
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

  /**
   * Create a hook that blocks specific tools.
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

  /**
   * Create a hook that auto-approves specific tools.
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
  def logging(logger: String => Task[Unit]): HookCallback = input =>
    val eventType = input.getClass.getSimpleName
    logger(s"Hook event: $eventType for session ${input.sessionId}") *>
      ZIO.succeed(HookOutput.continue)

  /** Combine multiple hooks - runs all and uses the first non-continue result */
  def combine(hooks: HookCallback*): HookCallback = input =>
    ZIO.foreach(hooks.toList)(hook => hook(input)).map { outputs =>
      outputs
        .find {
          case _: HookOutput.Continue => false
          case _                      => true
        }
        .getOrElse(HookOutput.continue)
    }

  /**
   * Convert a Scala hook callback to a JavaScript function for the SDK.
   *
   * This bridges the ZIO-based callback to the SDK's expected JavaScript function format.
   */
  def toRawJs(
    callback: HookCallback,
    runtime: Runtime[Any],
  ): js.Function3[js.Dynamic, js.UndefOr[String], js.Dynamic, js.Promise[js.Object]] =
    (rawInput: js.Dynamic,
      _toolUseId: js.UndefOr[String],
      _options: js.Dynamic,
    ) =>
      val input  = parseHookInput(rawInput)
      val effect = callback(input).map(_.toRaw)
      Unsafe.unsafe { implicit unsafe => runtime.unsafe.runToFuture(effect).toJSPromise }

  /**
   * Parse raw JavaScript hook input to Scala type.
   *
   * This converts the SDK's hook input format to our typed HookInput ADT.
   */
  private def parseHookInput(raw: js.Dynamic): HookInput =
    val sessionId      = SessionId(raw.session_id.asInstanceOf[String])
    val cwd            = raw.cwd.asInstanceOf[String]
    val transcriptPath = raw.transcript_path.asInstanceOf[String]
    val hookEvent      = firstString(raw, "hook_event", "hook_event_name").getOrElse("Unknown")
    val permissionMode = firstString(raw, "permission_mode", "permissionMode").map(PermissionMode.fromString)
    val promptId       = firstString(raw, "prompt_id", "promptId")
    val baseAgentId    = firstString(raw, "agent_id", "agentId").map(SubagentId.apply)
    val baseAgentType  = firstString(raw, "agent_type", "agentType")

    hookEvent match
      case "PreToolUse" =>
        HookInput.PreToolUse(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = ToolName(raw.tool_name.asInstanceOf[String]),
          toolInput = parseJson(raw.tool_input),
          toolUseId = ToolUseId(raw.tool_use_id.asInstanceOf[String]),
          agentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
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
          agentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
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
          agentId = baseAgentId,
          hookAgentType = baseAgentType,
          isInterrupt = optionalBoolean(raw, "is_interrupt").getOrElse(false),
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "PermissionRequest" =>
        HookInput.PermissionRequest(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = ToolName(raw.tool_name.asInstanceOf[String]),
          toolInput = parseJson(raw.tool_input),
          permissionSuggestions = optionalJsValue(raw, "permission_suggestions")
            .filter(js.Array.isArray(_))
            .map(_.asInstanceOf[js.Array[js.Any]].toList.map(parseJson))
            .getOrElse(Nil),
          permissionMode = permissionMode,
          promptId = promptId,
          toolUseId = firstString(raw, "tool_use_id", "toolUseId").map(ToolUseId.apply),
          agentId = baseAgentId,
          hookAgentType = baseAgentType,
          title = firstString(raw, "title"),
          displayName = firstString(raw, "display_name", "displayName"),
          description = firstString(raw, "description"),
        )

      case "Notification" =>
        HookInput.Notification(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          message = raw.message.asInstanceOf[String],
          title = firstString(raw, "title"),
          notificationType = firstString(raw, "notification_type", "notificationType").getOrElse("info"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "UserPromptSubmit" =>
        HookInput.UserPromptSubmit(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          prompt = raw.prompt.asInstanceOf[String],
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "SessionStart" =>
        HookInput.SessionStart(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          source = SessionStartSource.fromString(
            firstString(raw, "source").getOrElse("startup")
          ),
          agentType = baseAgentType,
          model = firstString(raw, "model"),
          hookAgentId = baseAgentId,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "SessionEnd" =>
        HookInput.SessionEnd(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          reason = ExitReason.fromString(firstString(raw, "reason").getOrElse("other")),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
          totalCostUsd = optionalDouble(raw, "total_cost_usd", "totalCostUsd"),
        )

      case "Stop" =>
        HookInput.Stop(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          stopHookActive = optionalBoolean(raw, "stop_hook_active").getOrElse(false),
          lastAssistantMessage = firstString(raw, "last_assistant_message", "lastAssistantMessage"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "StopFailure" =>
        HookInput.StopFailure(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          error = AssistantMessageError.fromString(firstString(raw, "error").getOrElse("unknown")),
          errorDetails = firstString(raw, "error_details", "errorDetails"),
          lastAssistantMessage = firstString(raw, "last_assistant_message", "lastAssistantMessage"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "SubagentStart" =>
        HookInput.SubagentStart(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          agentId = SubagentId(firstString(raw, "agent_id", "agentId").getOrElse("")),
          agentType = firstString(raw, "agent_type", "agentType").getOrElse(""),
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "SubagentStop" =>
        HookInput.SubagentStop(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          stopHookActive = optionalBoolean(raw, "stop_hook_active").getOrElse(false),
          agentId = SubagentId(firstString(raw, "agent_id", "agentId").getOrElse("")),
          agentTranscriptPath = firstString(raw, "agent_transcript_path", "agentTranscriptPath").getOrElse(""),
          agentType = firstString(raw, "agent_type", "agentType").getOrElse(""),
          lastAssistantMessage = firstString(raw, "last_assistant_message", "lastAssistantMessage"),
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "PostCompact" =>
        HookInput.PostCompact(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          trigger = CompactTrigger.fromString(
            firstString(raw, "trigger").getOrElse("auto")
          ),
          compactSummary = firstString(raw, "compact_summary", "compactSummary").getOrElse(""),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "PreCompact" =>
        HookInput.PreCompact(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          trigger = CompactTrigger.fromString(
            firstString(raw, "trigger").getOrElse("auto")
          ),
          customInstructions = firstString(raw, "custom_instructions", "customInstructions"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "Setup" =>
        HookInput.Setup(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          trigger = SetupTrigger.fromString(
            firstString(raw, "trigger").getOrElse("init")
          ),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "TeammateIdle" =>
        HookInput.TeammateIdle(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          teammateName = firstString(raw, "teammate_name", "teammateName").getOrElse(""),
          teamName = firstString(raw, "team_name", "teamName").getOrElse(""),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "TaskCompleted" =>
        HookInput.TaskCompleted(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          taskId = firstString(raw, "task_id", "taskId").getOrElse(""),
          taskSubject = firstString(raw, "task_subject", "taskSubject").getOrElse(""),
          taskDescription = firstString(raw, "task_description", "taskDescription"),
          teammateName = firstString(raw, "teammate_name", "teammateName"),
          teamName = firstString(raw, "team_name", "teamName"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "Elicitation" =>
        HookInput.Elicitation(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          mcpServerName = firstString(raw, "mcp_server_name", "mcpServerName").getOrElse(""),
          message = firstString(raw, "message").getOrElse(""),
          mode = firstString(raw, "mode").map(ElicitationMode.fromString),
          url = firstString(raw, "url"),
          elicitationId = firstString(raw, "elicitation_id", "elicitationId"),
          requestedSchema = optionalJsValue(raw, "requested_schema").map(parseJson),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "ElicitationResult" =>
        HookInput.ElicitationResult(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          mcpServerName = firstString(raw, "mcp_server_name", "mcpServerName").getOrElse(""),
          action = ElicitationAction.fromString(firstString(raw, "action").getOrElse("cancel")),
          elicitationId = firstString(raw, "elicitation_id", "elicitationId"),
          mode = firstString(raw, "mode").map(ElicitationMode.fromString),
          content = optionalJsValue(raw, "content").map(parseJson),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "ConfigChange" =>
        HookInput.ConfigChange(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          source = ConfigChangeSource.fromString(firstString(raw, "source").getOrElse("user_settings")),
          filePath = firstString(raw, "file_path", "filePath"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "WorktreeCreate" =>
        HookInput.WorktreeCreate(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          name = firstString(raw, "name").getOrElse(""),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "WorktreeRemove" =>
        HookInput.WorktreeRemove(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          worktreePath = firstString(raw, "worktree_path", "worktreePath").getOrElse(""),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "InstructionsLoaded" =>
        HookInput.InstructionsLoaded(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          filePath = firstString(raw, "file_path", "filePath").getOrElse(""),
          memoryType = MemoryType.fromString(
            firstString(raw, "memory_type", "memoryType").getOrElse("Project")
          ),
          loadReason = InstructionsLoadReason.fromString(
            firstString(raw, "load_reason", "loadReason").getOrElse("session_start")
          ),
          globs = optionalJsValue(raw, "globs")
            .filter(js.Array.isArray(_))
            .map(_.asInstanceOf[js.Array[String]].toList),
          triggerFilePath = firstString(raw, "trigger_file_path", "triggerFilePath"),
          parentFilePath = firstString(raw, "parent_file_path", "parentFilePath"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "PermissionDenied" =>
        HookInput.PermissionDenied(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          toolName = ToolName(firstString(raw, "tool_name", "toolName").getOrElse("")),
          toolInput = parseJson(raw.tool_input),
          toolUseId = ToolUseId(firstString(raw, "tool_use_id", "toolUseId").getOrElse("")),
          reason = firstString(raw, "reason"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "TaskCreated" =>
        HookInput.TaskCreated(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          taskId = firstString(raw, "task_id", "taskId").getOrElse(""),
          taskSubject = firstString(raw, "task_subject", "taskSubject").getOrElse(""),
          taskDescription = firstString(raw, "task_description", "taskDescription"),
          teammateName = firstString(raw, "teammate_name", "teammateName"),
          teamName = firstString(raw, "team_name", "teamName"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "CwdChanged" =>
        HookInput.CwdChanged(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          oldCwd = firstString(raw, "old_cwd", "oldCwd").getOrElse(""),
          newCwd = firstString(raw, "new_cwd", "newCwd").getOrElse(""),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "FileChanged" =>
        HookInput.FileChanged(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          filePath = firstString(raw, "file_path", "filePath").getOrElse(""),
          event = FileChangeEvent.fromString(firstString(raw, "event").getOrElse("change")),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case "MessageDisplay" =>
        HookInput.MessageDisplay(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          delta = firstString(raw, "delta"),
          finalContent = firstString(raw, "final", "final_content", "finalContent"),
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )

      case other =>
        // Fallback for unknown events
        HookInput.Notification(
          sessionId = sessionId,
          cwd = cwd,
          transcriptPath = transcriptPath,
          message = s"Unknown hook event: $other",
          hookAgentId = baseAgentId,
          hookAgentType = baseAgentType,
          permissionMode = permissionMode,
          promptId = promptId,
        )
    end match
  end parseHookInput

  private def parseJson(value: js.Any): Json =
    import zio.json.*
    if value == null || js.isUndefined(value) then Json.Null
    else
      val jsonStr = js.JSON.stringify(value)
      jsonStr.fromJson[Json].getOrElse(Json.Null)

  private def optionalJsValue(raw: js.Dynamic, field: String): Option[js.Any] =
    raw
      .selectDynamic(field)
      .asInstanceOf[js.UndefOr[js.Any]]
      .toOption
      .filter(v => v != null && !js.isUndefined(v))

  private def firstString(raw: js.Dynamic, fields: String*): Option[String] =
    fields.iterator
      .flatMap(field => optionalJsValue(raw, field))
      .flatMap { value =>
        if js.typeOf(value) == "string" then Some(value.asInstanceOf[String])
        else None
      }
      .nextOption()

  private def optionalBoolean(raw: js.Dynamic, field: String): Option[Boolean] =
    optionalJsValue(raw, field).flatMap { value =>
      if js.typeOf(value) == "boolean" then Some(value.asInstanceOf[Boolean])
      else None
    }

  private def optionalDouble(raw: js.Dynamic, fields: String*): Option[Double] =
    fields.iterator
      .flatMap(field => optionalJsValue(raw, field))
      .flatMap { value =>
        if js.typeOf(value) == "number" then Some(value.asInstanceOf[Double])
        else None
      }
      .nextOption()

  // ============================================================================
  // Hook Combinator Extension Methods
  // ============================================================================

  extension (hook: HookCallback)

    /**
     * Only run this hook when the predicate matches, otherwise continue.
     *
     * Example:
     * {{{
     * val logOnlyBash = HookCallback.logging(println)
     *   .when(_.isInstanceOf[HookInput.PreToolUse])
     * }}}
     */
    def when(predicate: HookInput => Boolean): HookCallback = input =>
      if predicate(input) then hook(input)
      else ZIO.succeed(HookOutput.continue)

    /**
     * Skip this hook when the predicate matches (inverse of when).
     *
     * Example:
     * {{{
     * val blockNonRead = HookCallback.blockTools(ToolName.Bash)
     *   .unless(input => input.toolName == ToolName.Read)
     * }}}
     */
    def unless(predicate: HookInput => Boolean): HookCallback =
      hook.when(input => !predicate(input))

    /**
     * Chain: run this hook first, if it continues then run the other hook.
     *
     * Example:
     * {{{
     * val pipeline = loggingHook.andThen(securityHook).andThen(rateLimitHook)
     * }}}
     */
    def andThen(other: HookCallback): HookCallback = input =>
      hook(input).flatMap {
        case c: HookOutput.Continue => other(input)
        case nonContinue            => ZIO.succeed(nonContinue)
      }

    /**
     * Fallback: run this hook, if it blocks/denies then try the other hook.
     *
     * Example:
     * {{{
     * val lenientSecurity = strictHook.orElse(permissiveHook)
     * }}}
     */
    def orElse(fallback: HookCallback): HookCallback = input =>
      hook(input).flatMap {
        case _: HookOutput.Block                  => fallback(input)
        case d: HookOutput.Decision if !d.approve => fallback(input)
        case other                                => ZIO.succeed(other)
      }

    /**
     * Apply a transformation to the hook output.
     *
     * Example:
     * {{{
     * val withMessage = hook.mapOutput {
     *   case c: HookOutput.Continue => c.copy(systemMessage = Some("Processed"))
     *   case other => other
     * }
     * }}}
     */
    def mapOutput(f: HookOutput => HookOutput): HookCallback = input => hook(input).map(f)

    /**
     * Filter inputs before passing to this hook.
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
  end extension
end HookCallback
