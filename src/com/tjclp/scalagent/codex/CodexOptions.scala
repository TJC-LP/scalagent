package com.tjclp.scalagent.codex

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.*
import scala.language.implicitConversions
import zio.json.*
import zio.json.ast.Json

/** Abort signal forwarded to the Codex SDK for turn cancellation. */
@js.native
trait AbortSignal extends js.Object

/** Abort controller for constructing cancellable Codex turn signals. */
@js.native
@JSGlobal
class AbortController() extends js.Object:
  val signal: AbortSignal = js.native
  def abort(): Unit = js.native

object AbortController:
  def create(): AbortController = new AbortController()

/** Codex sandbox mode — controls what the agent can do on the filesystem. */
enum SandboxMode(val raw: String):
  case ReadOnly extends SandboxMode("read-only")
  case WorkspaceWrite extends SandboxMode("workspace-write")
  case FullAccess extends SandboxMode("danger-full-access")

/** Codex approval policy — when the agent pauses for human approval. */
enum ApprovalMode(val raw: String):
  case Never extends ApprovalMode("never")
  case OnRequest extends ApprovalMode("on-request")
  case OnFailure extends ApprovalMode("on-failure")
  case Untrusted extends ApprovalMode("untrusted")

/** Controls Codex model reasoning effort. */
enum ModelReasoningEffort(val raw: String):
  case Minimal extends ModelReasoningEffort("minimal")
  case Low extends ModelReasoningEffort("low")
  case Medium extends ModelReasoningEffort("medium")
  case High extends ModelReasoningEffort("high")
  case XHigh extends ModelReasoningEffort("xhigh")

/** Controls Codex web search behavior. */
enum WebSearchMode(val raw: String):
  case Disabled extends WebSearchMode("disabled")
  case Cached extends WebSearchMode("cached")
  case Live extends WebSearchMode("live")

/** Recursive config value for Codex `--config` overrides. Mirrors the TypeScript SDK shape. */
enum CodexConfigValue:
  case Str(value: String)
  case Num(value: Double)
  case Bool(value: Boolean)
  case Arr(values: List[CodexConfigValue])
  case Obj(fields: Map[String, CodexConfigValue])

  def toRaw: js.Any = this match
    case CodexConfigValue.Str(value) =>
      value
    case CodexConfigValue.Num(value) =>
      value
    case CodexConfigValue.Bool(value) =>
      value
    case CodexConfigValue.Arr(values) =>
      values.map(_.toRaw).toJSArray
    case CodexConfigValue.Obj(fields) =>
      val obj = js.Dynamic.literal()
      fields.foreach { (key, value) => obj.updateDynamic(key)(value.toRaw) }
      obj

object CodexConfigValue:
  def str(value: String): CodexConfigValue = CodexConfigValue.Str(value)
  def num(value: Double): CodexConfigValue = CodexConfigValue.Num(value)
  def bool(value: Boolean): CodexConfigValue = CodexConfigValue.Bool(value)
  def arr(values: CodexConfigValue*): CodexConfigValue = CodexConfigValue.Arr(values.toList)
  def obj(fields: (String, CodexConfigValue)*): CodexConfigValue = CodexConfigValue.Obj(fields.toMap)

  given Conversion[String, CodexConfigValue] = str(_)
  given Conversion[Int, CodexConfigValue] = value => num(value.toDouble)
  given Conversion[Long, CodexConfigValue] = value => num(value.toDouble)
  given Conversion[Double, CodexConfigValue] = num(_)
  given Conversion[Float, CodexConfigValue] = value => num(value.toDouble)
  given Conversion[Boolean, CodexConfigValue] = bool(_)

/** Options for creating a CodexClient (maps to `new Codex(options)`).
  *
  * @param env Environment variables forwarded to the Codex CLI subprocess. When non-empty,
  *            the SDK will **not** inherit variables from `process.env` — callers must
  *            spread `sys.env` explicitly if they need inherited variables retained.
  */
final case class CodexClientOptions(
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    codexPathOverride: Option[String] = None,
    config: Map[String, CodexConfigValue] = Map.empty,
    env: Map[String, String] = Map.empty
):
  def toRaw: js.Dynamic =
    val obj = js.Dynamic.literal()
    apiKey.foreach(v => obj.apiKey = v)
    baseUrl.foreach(v => obj.baseUrl = v)
    codexPathOverride.foreach(v => obj.codexPathOverride = v)
    if config.nonEmpty then
      val configObj = js.Dynamic.literal()
      config.foreach { (k, v) => configObj.updateDynamic(k)(v.toRaw) }
      obj.config = configObj
    if env.nonEmpty then
      val envObj = js.Dynamic.literal()
      env.foreach { (k, v) => envObj.updateDynamic(k)(v) }
      obj.env = envObj
    obj

object CodexClientOptions:
  val default: CodexClientOptions = CodexClientOptions()

/** Structured multimodal input item for Codex turns. */
enum CodexInputItem:
  case Text(text: String)
  case LocalImage(path: String)

  def toRaw: js.Object = this match
    case CodexInputItem.Text(text) =>
      js.Dynamic.literal(`type` = "text", text = text).asInstanceOf[js.Object]
    case CodexInputItem.LocalImage(path) =>
      js.Dynamic.literal(`type` = "local_image", path = path).asInstanceOf[js.Object]

object CodexInputItem:
  def text(text: String): CodexInputItem = CodexInputItem.Text(text)
  def localImage(path: String): CodexInputItem = CodexInputItem.LocalImage(path)

type CodexInput = String | Seq[CodexInputItem]

/** Turn-specific options for Codex runs. */
final case class CodexTurnOptions(
    outputSchema: Option[Json] = None,
    signal: Option[AbortSignal] = None
):
  def toRaw: js.UndefOr[js.Dynamic] =
    if outputSchema.isEmpty && signal.isEmpty then js.undefined
    else
      val obj = js.Dynamic.literal()
      outputSchema.foreach(schema => obj.outputSchema = js.JSON.parse(schema.toJson))
      signal.foreach(sig => obj.signal = sig)
      obj

object CodexTurnOptions:
  val default: CodexTurnOptions = CodexTurnOptions()

/** Options for creating or configuring a thread (maps to `ThreadOptions`). */
final case class CodexThreadOptions(
    model: Option[String] = None,
    sandboxMode: Option[SandboxMode] = None,
    workingDirectory: Option[String] = None,
    approvalPolicy: Option[ApprovalMode] = None,
    additionalDirectories: List[String] = Nil,
    skipGitRepoCheck: Boolean = false,
    modelReasoningEffort: Option[ModelReasoningEffort] = None,
    networkAccessEnabled: Option[Boolean] = None,
    webSearchMode: Option[WebSearchMode] = None,
    webSearchEnabled: Option[Boolean] = None
):
  def toRaw: js.Dynamic =
    val obj = js.Dynamic.literal()
    model.foreach(v => obj.model = v)
    sandboxMode.foreach(v => obj.sandboxMode = v.raw)
    workingDirectory.foreach(v => obj.workingDirectory = v)
    approvalPolicy.foreach(v => obj.approvalPolicy = v.raw)
    if additionalDirectories.nonEmpty then obj.additionalDirectories = additionalDirectories.toJSArray
    if skipGitRepoCheck then obj.skipGitRepoCheck = true
    modelReasoningEffort.foreach(v => obj.modelReasoningEffort = v.raw)
    networkAccessEnabled.foreach(v => obj.networkAccessEnabled = v)
    webSearchMode.foreach(v => obj.webSearchMode = v.raw)
    webSearchEnabled.foreach(v => obj.webSearchEnabled = v)
    obj

object CodexThreadOptions:
  val default: CodexThreadOptions = CodexThreadOptions()
