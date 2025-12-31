package com.tjclp.scalagent.examples

import zio.*
import com.tjclp.scalagent.*
import com.tjclp.scalagent.config.CommandName

/** Example demonstrating type-safe slash command references.
  *
  * This example shows how to:
  * - Use CommandName opaque type for type safety
  * - Reference built-in commands (compact, clear, help)
  * - Create custom command references
  * - Use extension methods (withSlash, isBuiltin)
  * - Handle commands from SystemEvent.Init
  *
  * Run with: EXAMPLE=command mill examples.run
  *
  * Requires ANTHROPIC_API_KEY environment variable to be set.
  */
object SlashCommandExample extends ZIOAppDefault:

  val run: ZIO[Any, Any, Unit] =
    for
      _ <- Console.printLine("=== Slash Command Example ===\n").orDie

      // =====================================================
      // 1. Built-in Commands
      // =====================================================
      _ <- Console.printLine("1. Built-in Commands:").orDie

      compactCmd = CommandName.compact
      clearCmd = CommandName.clear
      helpCmd = CommandName.help

      _ <- Console.printLine(s"   compact: value='${compactCmd.value}', withSlash='${compactCmd.withSlash}', isBuiltin=${compactCmd.isBuiltin}").orDie
      _ <- Console.printLine(s"   clear:   value='${clearCmd.value}', withSlash='${clearCmd.withSlash}', isBuiltin=${clearCmd.isBuiltin}").orDie
      _ <- Console.printLine(s"   help:    value='${helpCmd.value}', withSlash='${helpCmd.withSlash}', isBuiltin=${helpCmd.isBuiltin}\n").orDie

      // =====================================================
      // 2. Custom Commands
      // =====================================================
      _ <- Console.printLine("2. Custom Commands:").orDie

      // Custom commands are typically defined in .claude/commands/*.md
      deployCmd = CommandName("deploy")
      testAllCmd = CommandName("test-all")
      reviewPrCmd = CommandName("review-pr")

      _ <- Console.printLine(s"   deploy:    withSlash='${deployCmd.withSlash}', isBuiltin=${deployCmd.isBuiltin}").orDie
      _ <- Console.printLine(s"   test-all:  withSlash='${testAllCmd.withSlash}', isBuiltin=${testAllCmd.isBuiltin}").orDie
      _ <- Console.printLine(s"   review-pr: withSlash='${reviewPrCmd.withSlash}', isBuiltin=${reviewPrCmd.isBuiltin}\n").orDie

      // =====================================================
      // 3. Working with Command Lists
      // =====================================================
      _ <- Console.printLine("3. Working with Command Lists:").orDie

      allCommands = List(compactCmd, clearCmd, helpCmd, deployCmd, testAllCmd, reviewPrCmd)
      builtinCommands = allCommands.filter(_.isBuiltin)
      customCommands = allCommands.filterNot(_.isBuiltin)

      _ <- Console.printLine(s"   Total commands: ${allCommands.size}").orDie
      _ <- Console.printLine(s"   Built-in: ${builtinCommands.map(_.withSlash).mkString(", ")}").orDie
      _ <- Console.printLine(s"   Custom: ${customCommands.map(_.withSlash).mkString(", ")}\n").orDie

      // =====================================================
      // 4. Commands from Init Event (streaming context)
      // =====================================================
      _ <- Console.printLine("4. Commands from Init Event:").orDie
      _ <- Console.printLine("   In a streaming context, commands come from SystemEvent.Init:").orDie
      _ <- Console.printLine("   ```scala").orDie
      _ <- Console.printLine("   ClaudeAgent.query(prompt, options)").orDie
      _ <- Console.printLine("     .tap {").orDie
      _ <- Console.printLine("       case AgentMessage.System(SystemEvent.Init(_, _, _, _, _, _, _, slashCommands, _, _, _, _, _), _, _) =>").orDie
      _ <- Console.printLine("         ZIO.foreach(slashCommands)(cmd =>").orDie
      _ <- Console.printLine("           Console.printLine(s\"Available: ${cmd.withSlash}\")").orDie
      _ <- Console.printLine("         )").orDie
      _ <- Console.printLine("       case _ => ZIO.unit").orDie
      _ <- Console.printLine("     }").orDie
      _ <- Console.printLine("   ```\n").orDie

      // =====================================================
      // 5. Live Demo: Discover Commands via Query
      // =====================================================
      _ <- Console.printLine("5. Live Demo - Discovering Available Commands:").orDie

      options = AgentOptions.default
        .withModel(Model.Sonnet4_5)
        .withPermissionMode(PermissionMode.DontAsk)
        .withMaxTurns(1)

      // Stream and look for Init event to see available commands
      _ <- ClaudeAgent.query("What is 1+1?", options)
        .tap {
          case AgentMessage.System(init: SystemEvent.Init, _, _) =>
            for
              _ <- Console.printLine(s"   Found Init event with ${init.slashCommands.size} commands:").orDie
              _ <- ZIO.foreach(init.slashCommands.take(5))(cmd =>
                Console.printLine(s"     ${cmd.withSlash} (builtin: ${cmd.isBuiltin})").orDie
              )
              _ <- ZIO.when(init.slashCommands.size > 5)(
                Console.printLine(s"     ... and ${init.slashCommands.size - 5} more").orDie
              )
            yield ()
          case _ => ZIO.unit
        }
        .runDrain
        .provide(ClaudeAgent.live)

      _ <- Console.printLine("\n=== Slash Command Example Complete ===").orDie
    yield ()
