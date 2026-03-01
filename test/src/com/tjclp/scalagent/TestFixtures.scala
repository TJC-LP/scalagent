package com.tjclp.scalagent

import zio.json.ast.Json
import com.tjclp.scalagent.config._
import com.tjclp.scalagent.errors._
import com.tjclp.scalagent.messages._
import com.tjclp.scalagent.types._

/** Common test fixtures for Claude Agent SDK tests.
  *
  * Provides sample messages, content blocks, options, and other test data.
  */
object TestFixtures:

  // ============================================
  // Type-safe IDs
  // ============================================

  val testSessionId: SessionId = SessionId("test-session-123")
  val testMessageUuid: MessageUuid = MessageUuid("msg-uuid-456")
  val testToolUseId: ToolUseId = ToolUseId("toolu_01ABC123")
  val testApiMessageId: ApiMessageId = ApiMessageId("msg_01XYZ789")

  // ============================================
  // Content Blocks
  // ============================================

  val textBlock: ContentBlock.Text =
    ContentBlock.Text("Hello, I'm Claude!")

  val toolUseBlock: ContentBlock.ToolUse =
    ContentBlock.ToolUse(
      id = testToolUseId,
      name = "Read",
      input = Json.Obj("file_path" -> Json.Str("/tmp/test.txt"))
    )

  val toolResultBlock: ContentBlock.ToolResult =
    ContentBlock.ToolResult(
      toolUseId = testToolUseId,
      content = "File contents here",
      isError = false
    )

  val errorToolResultBlock: ContentBlock.ToolResult =
    ContentBlock.ToolResult(
      toolUseId = testToolUseId,
      content = "File not found",
      isError = true
    )

  val thinkingBlock: ContentBlock.Thinking =
    ContentBlock.Thinking(
      thinking = "Let me think about this...",
      signature = Some("sig-abc")
    )

  // ============================================
  // Model Usage
  // ============================================

  val sampleUsage: ModelUsage =
    ModelUsage(
      inputTokens = 100,
      outputTokens = 50,
      cacheReadInputTokens = 10,
      cacheCreationInputTokens = 5
    )

  val samplePerModelUsage: PerModelUsage =
    PerModelUsage(
      inputTokens = 100,
      outputTokens = 50,
      cacheReadInputTokens = 10,
      cacheCreationInputTokens = 5,
      webSearchRequests = 0,
      costUSD = 0.001,
      contextWindow = 200000
    )

  // ============================================
  // API Messages
  // ============================================

  val apiAssistantMessage: ApiAssistantMessage =
    ApiAssistantMessage(
      id = testApiMessageId,
      role = Role.Assistant,
      content = List(textBlock),
      model = "claude-sonnet-4-20250514",
      stopReason = Some(StopReason.EndTurn),
      stopSequence = None,
      usage = Some(sampleUsage)
    )

  val apiAssistantMessageWithToolUse: ApiAssistantMessage =
    ApiAssistantMessage(
      id = testApiMessageId,
      role = Role.Assistant,
      content = List(textBlock, toolUseBlock),
      model = "claude-sonnet-4-20250514",
      stopReason = Some(StopReason.ToolUse),
      stopSequence = None,
      usage = Some(sampleUsage)
    )

  val apiUserMessage: ApiUserMessage =
    ApiUserMessage(
      role = Role.User,
      content = List(ContentBlock.Text("Hello Claude!"))
    )

  val apiUserMessageWithToolResult: ApiUserMessage =
    ApiUserMessage(
      role = Role.User,
      content = List(toolResultBlock)
    )

  // ============================================
  // Agent Messages
  // ============================================

  val assistantMessage: AgentMessage.Assistant =
    AgentMessage.Assistant(
      message = apiAssistantMessage,
      parentToolUseId = None,
      error = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

  val assistantMessageWithToolUse: AgentMessage.Assistant =
    AgentMessage.Assistant(
      message = apiAssistantMessageWithToolUse,
      parentToolUseId = None,
      error = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

  val userMessage: AgentMessage.User =
    AgentMessage.User(
      message = apiUserMessage,
      parentToolUseId = None,
      isSynthetic = false,
      toolUseResult = None,
      uuid = Some(testMessageUuid),
      sessionId = testSessionId
    )

  val syntheticUserMessage: AgentMessage.User =
    AgentMessage.User(
      message = apiUserMessageWithToolResult,
      parentToolUseId = Some(testToolUseId),
      isSynthetic = true,
      toolUseResult = Some(Json.Str("File contents here")),
      uuid = Some(testMessageUuid),
      sessionId = testSessionId
    )

  // ============================================
  // Result Outcomes
  // ============================================

  val successOutcome: ResultOutcome.Success =
    ResultOutcome.Success(
      durationMs = 1500,
      durationApiMs = 1200,
      numTurns = 3,
      result = "Task completed successfully!",
      totalCostUsd = 0.005,
      usage = sampleUsage,
      modelUsage = Map("claude-sonnet-4-20250514" -> samplePerModelUsage),
      permissionDenials = Nil,
      structuredOutput = None
    )

  val successOutcomeWithStructuredOutput: ResultOutcome.Success =
    ResultOutcome.Success(
      durationMs = 1500,
      durationApiMs = 1200,
      numTurns = 3,
      result = """{"summary":"Done","score":95}""",
      totalCostUsd = 0.005,
      usage = sampleUsage,
      modelUsage = Map("claude-sonnet-4-20250514" -> samplePerModelUsage),
      permissionDenials = Nil,
      structuredOutput = Some(Json.Obj(
        "summary" -> Json.Str("Done"),
        "score" -> Json.Num(95)
      ))
    )

  val errorOutcome: ResultOutcome.Error =
    ResultOutcome.Error(
      reason = ErrorReason.DuringExecution,
      durationMs = 500,
      durationApiMs = 400,
      numTurns = 1,
      totalCostUsd = 0.001,
      usage = sampleUsage,
      modelUsage = Map("claude-sonnet-4-20250514" -> samplePerModelUsage),
      permissionDenials = Nil,
      errors = List("Tool execution failed")
    )

  val errorOutcomeMaxTurns: ResultOutcome.Error =
    ResultOutcome.Error(
      reason = ErrorReason.MaxTurns,
      durationMs = 10000,
      durationApiMs = 9000,
      numTurns = 10,
      totalCostUsd = 0.05,
      usage = sampleUsage,
      modelUsage = Map("claude-sonnet-4-20250514" -> samplePerModelUsage),
      permissionDenials = Nil,
      errors = List("Maximum turns (10) exceeded")
    )

  // ============================================
  // Result Agent Messages
  // ============================================

  val resultSuccess: AgentMessage.Result =
    AgentMessage.Result(
      outcome = successOutcome,
      fastModeState = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

  val resultError: AgentMessage.Result =
    AgentMessage.Result(
      outcome = errorOutcome,
      fastModeState = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

  // ============================================
  // Agent Options
  // ============================================

  val minimalOptions: AgentOptions =
    AgentOptions.default

  val optionsWithModel: AgentOptions =
    AgentOptions.default.withModel(Model.Sonnet4)

  val optionsWithMaxTurns: AgentOptions =
    AgentOptions.default.withMaxTurns(5)

  val fullOptions: AgentOptions =
    AgentOptions.default
      .withModel(Model.Sonnet4)
      .withMaxTurns(10)
      .withMaxBudgetUsd(1.0)
      .withSystemPrompt(SystemPromptConfig.claudeCode)
      .withPermissionMode(PermissionMode.Default)

  // ============================================
  // Agent Errors
  // ============================================

  val permissionDeniedError: AgentError.PermissionDenied =
    AgentError.PermissionDenied(
      toolName = "Bash",
      reason = "Dangerous command blocked",
      toolUseId = Some(testToolUseId)
    )

  val rateLimitedError: AgentError.RateLimited =
    AgentError.RateLimited(retryAfterMs = 5000)

  val apiError: AgentError.ApiError =
    AgentError.ApiError(
      code = 500,
      message = "Internal server error",
      details = Some("Database connection failed")
    )

  // ============================================
  // Common Message Sequences
  // ============================================

  /** A simple successful conversation: user -> assistant -> result */
  val simpleConversation: List[AgentMessage] =
    List(userMessage, assistantMessage, resultSuccess)

  /** A conversation with tool use: user -> assistant(tool) -> user(result) -> assistant -> result */
  val toolUseConversation: List[AgentMessage] =
    List(
      userMessage,
      assistantMessageWithToolUse,
      syntheticUserMessage,
      assistantMessage,
      resultSuccess
    )

  /** An error conversation: user -> assistant -> error result */
  val errorConversation: List[AgentMessage] =
    List(userMessage, assistantMessage, resultError)

  // ============================================
  // Task Notification Messages
  // ============================================

  val taskNotification: AgentMessage.TaskNotification =
    AgentMessage.TaskNotification(
      taskId = "task-123",
      status = TaskStatus.Completed,
      outputFile = "/tmp/task-output.txt",
      summary = "Task completed successfully",
      toolUseId = None,
      usage = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

  val taskNotificationFailed: AgentMessage.TaskNotification =
    AgentMessage.TaskNotification(
      taskId = "task-456",
      status = TaskStatus.Failed,
      outputFile = "/tmp/task-output.txt",
      summary = "Task failed due to permission error",
      toolUseId = None,
      usage = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

  val toolUseSummary: AgentMessage.ToolUseSummary =
    AgentMessage.ToolUseSummary(
      summary = "Read 3 files successfully",
      precedingToolUseIds = List(testToolUseId, ToolUseId("toolu_02DEF456")),
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

  // ============================================
  // Hook Events
  // ============================================

  val hookStarted: SystemEvent.HookStarted =
    SystemEvent.HookStarted(
      hookId = "hook-123",
      hookName = "pre-commit",
      hookEvent = "PreToolUse"
    )

  val hookProgress: SystemEvent.HookProgress =
    SystemEvent.HookProgress(
      hookId = "hook-123",
      hookName = "pre-commit",
      hookEvent = "PreToolUse",
      stdout = "Running validation...",
      stderr = "",
      output = "Running validation..."
    )

  val hookResponse: SystemEvent.HookResponse =
    SystemEvent.HookResponse(
      hookId = "hook-123",
      hookName = "pre-commit",
      hookEvent = "PreToolUse",
      stdout = "Validation passed",
      stderr = "",
      output = "Validation passed",
      exitCode = Some(0),
      outcome = HookOutcome.Success
    )

  val hookResponseError: SystemEvent.HookResponse =
    SystemEvent.HookResponse(
      hookId = "hook-456",
      hookName = "lint",
      hookEvent = "PostToolUse",
      stdout = "",
      stderr = "Lint failed",
      output = "Lint failed",
      exitCode = Some(1),
      outcome = HookOutcome.Error
    )
