package com.tjclp.scalagent.examples

import scala.scalajs.js

/** Runtime dispatcher for examples.
  *
  * All examples are linked into a single JS module. This dispatcher
  * reads the EXAMPLE env var at runtime and calls the corresponding
  * main class. This avoids the Mill task graph issue where `mainClass`
  * is evaluated before `Task.Command` bodies run.
  *
  * Usage: EXAMPLE=dsl-basic bun run main.js
  *   or: ./mill examples.go --example dsl-basic
  */
object ExampleRunner:
  def main(args: Array[String]): Unit =
    val example = envOrDefault("EXAMPLE", "macro")
    val runner: Runnable = example match
      case "simple"         => () => SimpleQuery.main(args)
      case "macro"          => () => MacroToolExample.main(args)
      case "custom"         => () => CustomToolExample.main(args)
      case "hook"           => () => HookExample.main(args)
      case "permission"     => () => PermissionExample.main(args)
      case "session"        => () => SessionExample.main(args)
      case "structured"     => () => StructuredOutputExample.main(args)
      case "subagent"       => () => SubagentExample.main(args)
      case "plugin"         => () => PluginExample.main(args)
      case "prompt"         => () => SystemPromptExample.main(args)
      case "command"        => () => SlashCommandExample.main(args)
      case "a2a"            => () => A2AExample.main(args)
      case "agent-hooks"    => () => AgentHooksExample.main(args)
      case "dsl-basic"      => () => DslBasicExample.main(args)
      case "dsl-builder"    => () => DslBuilderExample.main(args)
      case "dsl-delegation" => () => DslDelegationExample.main(args)
      case "dsl-codex"      => () => DslCodexExample.main(args)
      case "dsl-cross"      => () => DslCrossProviderExample.main(args)
      case "capture"        => () => CaptureCheckingExample.main(args)
      case other =>
        System.err.println(s"Unknown example: '$other'")
        System.err.println("Available: simple, macro, custom, hook, permission, session, structured,")
        System.err.println("  subagent, plugin, prompt, command, a2a, agent-hooks, dsl-basic,")
        System.err.println("  dsl-builder, dsl-delegation, dsl-codex, dsl-cross, capture")
        () => throw new IllegalArgumentException(s"Unknown example: '$other'")
    runner.run()

  private def envOrDefault(key: String, default: String): String =
    val env = js.Dynamic.global.process.env
    val value = env.selectDynamic(key)
    if js.isUndefined(value) || value == null then default
    else value.asInstanceOf[String]
