package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import zio.*
import zio.json.*

/** Node.js fs module facade for plugin validation */
@js.native
@JSImport("fs", JSImport.Namespace)
private object NodeFs extends js.Object:
  def existsSync(path: String): Boolean = js.native
  def statSync(path: String): js.Dynamic = js.native

/** Plugin validation errors */
sealed abstract class PluginError(msg: String) extends Exception(msg)

object PluginError:
  final case class PathNotFound(path: String) extends PluginError(s"Plugin path not found: $path")

  final case class NotADirectory(path: String) extends PluginError(s"Plugin path is not a directory: $path")

  final case class MissingManifest(path: String, expected: String)
      extends PluginError(s"Plugin manifest not found at $path (expected: $expected)")

  given JsonEncoder[PluginError] = JsonEncoder[String].contramap(_.getMessage)

/** Plugin configuration for loading custom plugins.
  *
  * Plugins extend Claude Code with custom commands, agents, skills, hooks, and MCP servers. Currently only local
  * filesystem plugins are supported.
  *
  * Example:
  * {{{
  * val options = AgentOptions.default
  *   .withPlugin(PluginConfig.local("./my-plugin"))
  *   .withPlugin(PluginConfig.local("/absolute/path/to/plugin"))
  * }}}
  */
enum PluginConfig:
  /** Local filesystem plugin.
    * @param path
    *   Path to plugin directory (contains .claude-plugin/plugin.json)
    */
  case Local(path: String)

  /** Custom plugin configuration for future plugin types. */
  case Custom(raw: js.Object)

  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object = this match
    case Local(path) =>
      js.Dynamic.literal(`type` = "local", path = path).asInstanceOf[js.Object]
    case Custom(raw) =>
      raw

object PluginConfig:
  /** Create a local plugin configuration (pure, no validation) */
  def local(path: String): PluginConfig = Local(path)

  /** Create multiple local plugins from paths (pure, no validation) */
  def locals(paths: String*): List[PluginConfig] = paths.map(Local(_)).toList

  /** Create a local plugin with path validation (effectful).
    *
    * Validates:
    *   - Path exists
    *   - Path is a directory
    *   - Contains .claude-plugin/plugin.json manifest
    *
    * Example:
    * {{{
    * for
    *   plugin <- PluginConfig.localValidated("./my-plugin")
    *   result <- Claude.ask("Hello", AgentOptions.default.withPlugin(plugin))
    * yield result
    * }}}
    */
  def localValidated(path: String): IO[PluginError, PluginConfig] =
    ZIO.attempt {
      import PluginError.*

      // Check path exists
      if !NodeFs.existsSync(path) then
        throw PathNotFound(path)

      // Check is directory
      val stat = NodeFs.statSync(path)
      if !stat.isDirectory().asInstanceOf[Boolean] then
        throw NotADirectory(path)

      // Check manifest exists
      val manifestPath = s"$path/.claude-plugin/plugin.json"
      if !NodeFs.existsSync(manifestPath) then
        throw MissingManifest(path, manifestPath)

      Local(path)
    }.refineOrDie { case e: PluginError => e }

  /** Validate multiple plugin paths, failing on first error */
  def localsValidated(paths: String*): IO[PluginError, List[PluginConfig]] =
    ZIO.foreach(paths.toList)(localValidated)

  /** Validate multiple plugin paths, collecting all errors */
  def localsValidatedAll(paths: String*): IO[::[PluginError], List[PluginConfig]] =
    ZIO.validatePar(paths.toList)(localValidated)

  // JSON codecs for serialization (if needed)
  given JsonEncoder[PluginConfig] = JsonEncoder[zio.json.ast.Json].contramap {
    case Local(path) =>
      zio.json.ast.Json.Obj("type" -> zio.json.ast.Json.Str("local"), "path" -> zio.json.ast.Json.Str(path))
    case Custom(_) => zio.json.ast.Json.Null // Custom can't be JSON serialized
  }

  given JsonDecoder[PluginConfig] = JsonDecoder[zio.json.ast.Json].mapOrFail {
    case obj: zio.json.ast.Json.Obj =>
      obj.get("type") match
        case Some(zio.json.ast.Json.Str("local")) =>
          obj.get("path") match
            case Some(zio.json.ast.Json.Str(path)) => Right(Local(path))
            case _                                 => Left("Missing 'path' field for local plugin")
        case _ => Left("Unknown plugin type")
    case _ => Left("Expected JSON object for PluginConfig")
  }
