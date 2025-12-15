package com.tjclp.claude.agent.config

import zio.json.{JsonDecoder, JsonEncoder}

/** Type-safe wrapper for slash command names.
  *
  * Command names can be built-in (like "compact", "clear") or custom. Custom commands are defined as markdown files in
  * `.claude/commands/` directories.
  *
  * Example:
  * {{{
  * val builtin = CommandName.compact
  * val custom = CommandName("my-custom-command")
  *
  * // Use with slash prefix
  * println(custom.withSlash)  // "/my-custom-command"
  *
  * // Check if built-in
  * builtin.isBuiltin  // true
  * custom.isBuiltin   // false
  * }}}
  */
opaque type CommandName = String

object CommandName:
  def apply(name: String): CommandName = name

  // Built-in commands
  val compact: CommandName = "compact"
  val clear: CommandName = "clear"
  val help: CommandName = "help"

  extension (cmd: CommandName)
    def value: String = cmd

    /** Get the command with slash prefix */
    def withSlash: String = s"/$cmd"

    /** Check if this is a built-in command */
    def isBuiltin: Boolean = cmd match
      case "compact" | "clear" | "help" => true
      case _                            => false

  // Opaque type is String at runtime, so cast is safe
  given JsonEncoder[CommandName] = JsonEncoder.string.asInstanceOf[JsonEncoder[CommandName]]
  given JsonDecoder[CommandName] = JsonDecoder.string.asInstanceOf[JsonDecoder[CommandName]]
