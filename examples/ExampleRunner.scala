package com.tjclp.scalagent.examples

import scala.scalajs.js

/** Runtime dispatcher for examples.
  *
  * All examples are linked into a single JS module. This dispatcher
  * selects the example to run from CLI args or EXAMPLE env var.
  *
  * Usage:
  *   ./mill examples.run dsl-basic
  *   ./mill examples.run --help
  *   EXAMPLE=dsl-basic ./mill examples.run
  */
object ExampleRunner:
  private val examples = Map(
    "simple"         -> "SimpleQuery",
    "macro"          -> "MacroToolExample",
    "custom"         -> "CustomToolExample",
    "hook"           -> "HookExample",
    "permission"     -> "PermissionExample",
    "session"        -> "SessionExample",
    "structured"     -> "StructuredOutputExample",
    "subagent"       -> "SubagentExample",
    "plugin"         -> "PluginExample",
    "prompt"         -> "SystemPromptExample",
    "command"        -> "SlashCommandExample",
    "a2a"            -> "A2AExample",
    "agent-hooks"    -> "AgentHooksExample",
    "dsl-basic"      -> "DslBasicExample",
    "dsl-builder"    -> "DslBuilderExample",
    "dsl-delegation" -> "DslDelegationExample",
    "dsl-review"     -> "DslReviewExample",
    "dsl-cells"      -> "DslClandestineCellExample",
    "dsl-codex"      -> "DslCodexExample",
    "dsl-cross"      -> "DslCrossProviderExample",
    "capture"        -> "CaptureCheckingExample"
  )

  private val dispatchers: Map[String, Array[String] => Unit] = Map(
    "simple"         -> (SimpleQuery.main(_)),
    "macro"          -> (MacroToolExample.main(_)),
    "custom"         -> (CustomToolExample.main(_)),
    "hook"           -> (HookExample.main(_)),
    "permission"     -> (PermissionExample.main(_)),
    "session"        -> (SessionExample.main(_)),
    "structured"     -> (StructuredOutputExample.main(_)),
    "subagent"       -> (SubagentExample.main(_)),
    "plugin"         -> (PluginExample.main(_)),
    "prompt"         -> (SystemPromptExample.main(_)),
    "command"        -> (SlashCommandExample.main(_)),
    "a2a"            -> (A2AExample.main(_)),
    "agent-hooks"    -> (AgentHooksExample.main(_)),
    "dsl-basic"      -> (DslBasicExample.main(_)),
    "dsl-builder"    -> (DslBuilderExample.main(_)),
    "dsl-delegation" -> (DslDelegationExample.main(_)),
    "dsl-review"     -> (DslReviewExample.main(_)),
    "dsl-cells"      -> (DslClandestineCellExample.main(_)),
    "dsl-codex"      -> (DslCodexExample.main(_)),
    "dsl-cross"      -> (DslCrossProviderExample.main(_)),
    "capture"        -> (CaptureCheckingExample.main(_))
  )

  def main(args: Array[String]): Unit =
    val example = args.headOption
      .filterNot(_.startsWith("-"))
      .orElse(Option(envOrNull("EXAMPLE")))

    example match
      case Some(name) =>
        dispatchers.get(name) match
          case Some(run) => run(args.drop(1))
          case None =>
            System.err.println(s"Unknown example: '$name'\n")
            printHelp()
      case None =>
        if args.contains("--help") || args.contains("-h") then printHelp()
        else dispatchers("macro")(args) // default

  private def printHelp(): Unit =
    println("Usage: ./mill examples.run <example-name>")
    println()
    println("Available examples:")
    examples.toSeq.sortBy(_._1).foreach { (name, cls) =>
      println(f"  $name%-18s $cls")
    }
    println()
    println("Examples:")
    println("  ./mill examples.run dsl-basic        # DSL one-shot + streaming + eval")
    println("  ./mill examples.run dsl-cells        # zero-trust clandestine cell simulation")
    println("  ./mill examples.run dsl-codex         # Codex interpreter")
    println("  ./mill examples.run dsl-cross         # Claude <> Codex cross-provider")
    println("  ./mill examples.run macro             # Default macro tool example")

  private def envOrNull(key: String): String =
    val env = js.Dynamic.global.process.env
    val value = env.selectDynamic(key)
    if js.isUndefined(value) || value == null then null
    else value.asInstanceOf[String]
