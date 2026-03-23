package com.tjclp.scalagent.examples

import zio.*
import com.tjclp.scalagent.*

/** Example demonstrating plugin configuration and validation.
  *
  * This example shows how to:
  * - Configure plugins with PluginConfig.local()
  * - Validate plugin paths with localValidated()
  * - Handle PluginError types (PathNotFound, NotADirectory, MissingManifest)
  * - Use convenience methods like withLocalPlugins()
  *
  * Run with: EXAMPLE=plugin mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set when Claude Code auth is not already available.
  */
object PluginExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    for
      _ <- Console.printLine("=== Plugin Configuration Example ===\n").orDie

      // =====================================================
      // 1. Pure construction (no validation, SDK validates later)
      // =====================================================
      _ <- Console.printLine("1. Pure Plugin Construction (no validation):").orDie

      purePlugin = PluginConfig.local("./my-awesome-plugin")
      _ <- Console.printLine(s"   Created: $purePlugin").orDie

      // Multiple plugins at once
      multiplePlugins = PluginConfig.locals("./plugin-a", "./plugin-b", "./plugin-c")
      _ <- Console.printLine(s"   Multiple: ${multiplePlugins.length} plugins created").orDie

      // Using convenience method on AgentOptions
      optionsWithPlugins = AgentOptions.default
        .withLocalPlugins("./plugin-1", "./plugin-2")
      _ <- Console.printLine(s"   Options has ${optionsWithPlugins.plugins.size} plugins\n").orDie

      // =====================================================
      // 2. Effectful validation (fail-fast)
      // =====================================================
      _ <- Console.printLine("2. Validated Plugin (effectful, fail-fast):").orDie

      validatedResult <- PluginConfig.localValidated("./nonexistent-plugin").either
      _ <- validatedResult match
        case Left(PluginError.PathNotFound(path)) =>
          Console.printLine(s"   Expected error: PathNotFound($path)").orDie
        case Left(PluginError.NotADirectory(path)) =>
          Console.printLine(s"   Error: NotADirectory($path)").orDie
        case Left(PluginError.MissingManifest(path, expected)) =>
          Console.printLine(s"   Error: MissingManifest at $expected").orDie
        case Right(plugin) =>
          Console.printLine(s"   Success: $plugin").orDie

      // =====================================================
      // 3. Validate multiple plugins (collect all errors)
      // =====================================================
      _ <- Console.printLine("\n3. Validate Multiple Plugins (collect all errors):").orDie

      allValidated <- PluginConfig.localsValidatedAll(
        "./plugin-1",
        "./plugin-2",
        "./plugin-3"
      ).either

      _ <- allValidated match
        case Left(errors) =>
          Console.printLine(s"   Found ${errors.size} validation errors:").orDie *>
          ZIO.foreach(errors.toList)(e =>
            Console.printLine(s"     - ${e.getClass.getSimpleName}: ${e.getMessage}").orDie
          )
        case Right(plugins) =>
          Console.printLine(s"   All ${plugins.size} plugins valid!").orDie

      // =====================================================
      // 4. Real-world pattern: validate before query
      // =====================================================
      _ <- Console.printLine("\n4. Real-world Pattern (validate then query):").orDie

      // Demonstrate the pattern inline
      plugin = PluginConfig.local("./demo-plugin")
      options = AgentOptions.default
        .withModel(Model.sonnet)
        .withPermissionMode(PermissionMode.DontAsk)
        .withMaxTurns(3)
        .withPlugin(plugin)

      _ <- Console.printLine(s"   Configured options with plugin").orDie
      _ <- Console.printLine(s"   Plugin config: $plugin").orDie
      _ <- Console.printLine("   (Skipping actual query - plugin path doesn't exist)").orDie

      // Show the effectful pattern as a comment
      _ <- Console.printLine("\n   Pattern for validated plugins:").orDie
      _ <- Console.printLine("   ```scala").orDie
      _ <- Console.printLine("   for").orDie
      _ <- Console.printLine("     plugin <- PluginConfig.localValidated(\"./my-plugin\")").orDie
      _ <- Console.printLine("     result <- Claude.ask(\"Hello\", opts.withPlugin(plugin))").orDie
      _ <- Console.printLine("   yield result").orDie
      _ <- Console.printLine("   ```").orDie

      _ <- Console.printLine("\n=== Plugin Example Complete ===").orDie
    yield ()
