package com.tjclp.scalagent.hooks

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Hook configuration supporting both shell commands (serializable) and callbacks (runtime-only).
  *
  * Claude Code supports two hook styles:
  *   - Shell command hooks: Defined in config files, run as subprocesses
  *   - Callback hooks: Defined in code, run as in-process functions
  *
  * Example usage:
  * {{{
  * // Shell command hook (serializable to JSON)
  * val shellHook = HookConfig.shell(
  *   matcher = "Bash",
  *   command = "./validate.sh",
  *   timeout = Some(5000),
  *   once = true
  * )
  *
  * // Callback hook (runtime-only)
  * val callbackHook = HookConfig.callback { input =>
  *   ZIO.succeed(HookOutput.continue)
  * }
  *
  * // Use in AgentDefinition
  * AgentDefinition(
  *   description = "...",
  *   prompt = "...",
  *   hooks = Map(HookEvent.PreToolUse -> List(shellHook, callbackHook))
  * )
  * }}}
  */
sealed trait HookConfig:
  /** Convert to raw JavaScript object for SDK */
  def toRaw(runtime: Runtime[Any]): js.Object

object HookConfig:

  /** Shell command hook - runs a shell command when triggered.
    *
    * This style is serializable and can be defined in config files.
    *
    * @param matcher
    *   Regex pattern to match tool names (e.g., "Bash", "Edit|Write", ".*")
    * @param command
    *   Shell command to execute
    * @param timeout
    *   Optional timeout in milliseconds
    * @param once
    *   If true, hook only runs once then is disabled (added in v2.1.0)
    */
  final case class Shell(
      matcher: String,
      command: String,
      timeout: Option[Int] = None,
      once: Boolean = false
  ) extends HookConfig:
    def toRaw(runtime: Runtime[Any]): js.Object =
      val obj = js.Dynamic.literal(
        matcher = matcher,
        command = command
      )
      timeout.foreach(t => obj.timeout = t)
      if once then obj.once = true
      obj.asInstanceOf[js.Object]

  /** Callback hook - runs a Scala/ZIO function when triggered.
    *
    * This style is NOT serializable and can only be defined in code.
    *
    * @param matcher
    *   Optional regex pattern to match tool names (None = match all)
    * @param callback
    *   The hook callback function
    * @param timeout
    *   Optional timeout in milliseconds
    * @param once
    *   If true, hook only runs once then is disabled (added in v2.1.0)
    */
  final case class Callback(
      callback: HookCallback,
      matcher: Option[String] = None,
      timeout: Option[Int] = None,
      once: Boolean = false
  ) extends HookConfig:
    def toRaw(runtime: Runtime[Any]): js.Object =
      val obj = js.Dynamic.literal(
        hooks = js.Array(HookCallback.toRawJs(callback, runtime))
      )
      matcher.foreach(m => obj.matcher = m)
      timeout.foreach(t => obj.timeout = t)
      if once then obj.once = true
      obj.asInstanceOf[js.Object]

  // Convenience constructors

  /** Create a shell command hook */
  def shell(
      matcher: String,
      command: String,
      timeout: Option[Int] = None,
      once: Boolean = false
  ): HookConfig = Shell(matcher, command, timeout, once)

  /** Create a callback hook that matches all tools */
  def callback(cb: HookCallback): HookConfig = Callback(cb)

  /** Create a callback hook with a matcher pattern */
  def callback(matcher: String, cb: HookCallback): HookConfig =
    Callback(cb, matcher = Some(matcher))

  /** Create a callback hook with all options */
  def callback(
      matcher: String,
      cb: HookCallback,
      timeout: Option[Int] = None,
      once: Boolean = false
  ): HookConfig = Callback(cb, Some(matcher), timeout, once)

  /** Create a one-time shell hook */
  def shellOnce(matcher: String, command: String): HookConfig =
    Shell(matcher, command, once = true)

  /** Create a one-time callback hook */
  def callbackOnce(cb: HookCallback): HookConfig =
    Callback(cb, once = true)

  /** Create a one-time callback hook with matcher */
  def callbackOnce(matcher: String, cb: HookCallback): HookConfig =
    Callback(cb, Some(matcher), once = true)

  // JSON codecs (only Shell hooks are serializable)

  given JsonEncoder[HookConfig] = JsonEncoder[Json].contramap {
    case Shell(matcher, command, timeout, once) =>
      var fields: List[(String, Json)] = List(
        "type" -> Json.Str("shell"),
        "matcher" -> Json.Str(matcher),
        "command" -> Json.Str(command)
      )
      timeout.foreach(t => fields = fields :+ ("timeout" -> Json.Num(t)))
      if once then fields = fields :+ ("once" -> Json.Bool(true))
      Json.Obj(zio.Chunk.fromIterable(fields)*)

    case Callback(_, matcher, timeout, once) =>
      // Callbacks serialize as a marker - they can't be fully restored
      var fields: List[(String, Json)] = List("type" -> Json.Str("callback"))
      matcher.foreach(m => fields = fields :+ ("matcher" -> Json.Str(m)))
      timeout.foreach(t => fields = fields :+ ("timeout" -> Json.Num(t)))
      if once then fields = fields :+ ("once" -> Json.Bool(true))
      Json.Obj(zio.Chunk.fromIterable(fields)*)
  }

  given JsonDecoder[HookConfig] = JsonDecoder[Json].mapOrFail {
    case json: Json.Obj =>
      val fields = json.fields.toMap
      fields.get("type").flatMap(_.asString) match
        case Some("shell") =>
          for
            matcher <- fields.get("matcher").flatMap(_.asString).toRight("Missing matcher")
            command <- fields.get("command").flatMap(_.asString).toRight("Missing command")
          yield Shell(
            matcher = matcher,
            command = command,
            timeout = fields.get("timeout").flatMap(_.asNumber).map(_.value.intValue),
            once = fields.get("once").flatMap(_.asBoolean).getOrElse(false)
          )

        case Some("callback") =>
          // Callbacks can't be deserialized - return a no-op placeholder
          Left("Callback hooks cannot be deserialized from JSON - define them in code")

        case Some(other) =>
          Left(s"Unknown hook type: $other")

        case None =>
          // Legacy format without type field - assume shell
          for
            matcher <- fields.get("matcher").flatMap(_.asString).toRight("Missing matcher")
            command <- fields.get("command").flatMap(_.asString).toRight("Missing command")
          yield Shell(
            matcher = matcher,
            command = command,
            timeout = fields.get("timeout").flatMap(_.asNumber).map(_.value.intValue),
            once = fields.get("once").flatMap(_.asBoolean).getOrElse(false)
          )

    case _ => Left("Expected JSON object")
  }

  // Extension methods
  extension (config: HookConfig)
    /** Check if this is a shell command hook */
    def isShell: Boolean = config match
      case _: Shell    => true
      case _: Callback => false

    /** Check if this is a callback hook */
    def isCallback: Boolean = config match
      case _: Shell    => false
      case _: Callback => true

    /** Get the matcher pattern if any */
    def matcherPattern: Option[String] = config match
      case Shell(m, _, _, _)    => Some(m)
      case Callback(_, m, _, _) => m

    /** Check if this is a one-time hook */
    def isOnce: Boolean = config match
      case Shell(_, _, _, once)    => once
      case Callback(_, _, _, once) => once
