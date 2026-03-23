package com.tjclp.scalagent.examples

import zio.*
import com.tjclp.scalagent.*

/** Example demonstrating custom and preset system prompts.
  *
  * This example shows how to:
  * - Use SystemPromptConfig.Custom for custom prompt text
  * - Use SystemPromptConfig.Preset for named presets
  * - Use SystemPromptConfig.claudeCode convenience value
  * - Append instructions to presets with claudeCodeWith()
  *
  * Run with: ./mill examples.prompt.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set when Claude Code auth is not already available.
  */
object SystemPromptExample extends ZIOAppDefault:

  // Custom system prompt for a specialized assistant
  val scalaExpertPrompt = SystemPromptConfig.Custom(
    """You are an expert Scala developer specializing in functional programming.
      |
      |Guidelines:
      |1. Always prefer immutable data structures
      |2. Use pattern matching over if/else when appropriate
      |3. Leverage type inference but be explicit for public APIs
      |4. Favor composition over inheritance
      |5. Use ZIO for effectful code
      |
      |When explaining concepts, provide concise code examples.
      |Format your responses in clear sections with markdown.""".stripMargin
  )

  // Using the claude_code preset
  val claudeCodePrompt = SystemPromptConfig.claudeCode

  // Claude Code preset with additional instructions
  val enhancedClaudeCode = SystemPromptConfig.claudeCodeWith(
    """Additional instructions:
      |- Focus on Scala and functional programming patterns
      |- Prefer ZIO over cats-effect when suggesting libraries
      |- Always consider type safety and compile-time guarantees""".stripMargin
  )

  val run: ZIO[Any, Any, Unit] =
    for
      _ <- Console.printLine("=== System Prompt Configuration Example ===\n").orDie

      // =====================================================
      // 1. Custom System Prompt
      // =====================================================
      _ <- Console.printLine("1. Custom System Prompt:").orDie

      customOptions = AgentOptions.default
        .withModel(Model.sonnet)
        .withPermissionMode(PermissionMode.DontAsk)
        .withMaxTurns(3)
        .withSystemPrompt(scalaExpertPrompt)

      _ <- Console.printLine("   Querying with custom Scala expert prompt...").orDie

      customResult <- Claude.ask(
        "What's the idiomatic way to handle optional values in Scala 3?",
        customOptions
      )

      _ <- Console.printLine(s"\n   Response:\n   ${customResult.take(200)}...\n").orDie

      // =====================================================
      // 2. Preset System Prompt (claude_code)
      // =====================================================
      _ <- Console.printLine("2. Preset System Prompt (claude_code):").orDie

      presetOptions = AgentOptions.default
        .withModel(Model.sonnet)
        .withPermissionMode(PermissionMode.DontAsk)
        .withMaxTurns(3)
        .withSystemPrompt(claudeCodePrompt)

      _ <- Console.printLine("   Using claude_code preset...").orDie
      _ <- Console.printLine(s"   Preset config: $claudeCodePrompt\n").orDie

      // =====================================================
      // 3. Enhanced Preset with Append
      // =====================================================
      _ <- Console.printLine("3. Enhanced Preset with Appended Instructions:").orDie

      enhancedOptions = AgentOptions.default
        .withModel(Model.sonnet)
        .withPermissionMode(PermissionMode.DontAsk)
        .withMaxTurns(3)
        .withSystemPrompt(enhancedClaudeCode)

      _ <- Console.printLine("   Querying with enhanced claude_code preset...").orDie

      enhancedResult <- Claude.ask(
        "Suggest a library for HTTP client requests in Scala.",
        enhancedOptions
      )

      _ <- Console.printLine(s"\n   Response:\n   ${enhancedResult.take(200)}...\n").orDie

      // =====================================================
      // 4. Show toRaw output for each type
      // =====================================================
      _ <- Console.printLine("4. System Prompt Serialization (toRaw):").orDie
      _ <- Console.printLine(s"   Custom -> String: \"${scalaExpertPrompt.toRaw.toString.take(50)}...\"").orDie
      _ <- Console.printLine(s"   Preset -> Object: ${claudeCodePrompt.toRaw}").orDie
      _ <- Console.printLine(s"   Enhanced -> Object with append: ${enhancedClaudeCode.toRaw}").orDie

      _ <- Console.printLine("\n=== System Prompt Example Complete ===").orDie
    yield ()
