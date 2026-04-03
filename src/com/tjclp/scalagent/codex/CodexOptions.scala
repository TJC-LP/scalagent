package com.tjclp.scalagent.codex

import scala.scalajs.js

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

/** Options for creating a CodexClient (maps to `new Codex(options)`). */
final case class CodexClientOptions(
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    codexPathOverride: Option[String] = None,
    config: Map[String, String] = Map.empty,
    env: Map[String, String] = Map.empty
):
  def toRaw: js.Dynamic =
    val obj = js.Dynamic.literal()
    apiKey.foreach(v => obj.apiKey = v)
    baseUrl.foreach(v => obj.baseUrl = v)
    codexPathOverride.foreach(v => obj.codexPathOverride = v)
    if config.nonEmpty then
      val configObj = js.Dynamic.literal()
      config.foreach { (k, v) => configObj.updateDynamic(k)(v) }
      obj.config = configObj
    if env.nonEmpty then
      val envObj = js.Dynamic.literal()
      env.foreach { (k, v) => envObj.updateDynamic(k)(v) }
      obj.env = envObj
    obj

object CodexClientOptions:
  val default: CodexClientOptions = CodexClientOptions()

/** Options for creating or configuring a thread (maps to `ThreadOptions`). */
final case class CodexThreadOptions(
    model: Option[String] = None,
    sandboxMode: Option[SandboxMode] = None,
    workingDirectory: Option[String] = None,
    approvalPolicy: Option[ApprovalMode] = None,
    additionalDirectories: List[String] = Nil,
    skipGitRepoCheck: Boolean = false,
    networkAccessEnabled: Option[Boolean] = None,
    webSearchMode: Option[String] = None
):
  def toRaw: js.Dynamic =
    val obj = js.Dynamic.literal()
    model.foreach(v => obj.model = v)
    sandboxMode.foreach(v => obj.sandboxMode = v.raw)
    workingDirectory.foreach(v => obj.workingDirectory = v)
    approvalPolicy.foreach(v => obj.approvalPolicy = v.raw)
    if additionalDirectories.nonEmpty then
      obj.additionalDirectories = js.Array(additionalDirectories*)
    if skipGitRepoCheck then obj.skipGitRepoCheck = true
    networkAccessEnabled.foreach(v => obj.networkAccessEnabled = v)
    webSearchMode.foreach(v => obj.webSearchMode = v)
    obj

object CodexThreadOptions:
  val default: CodexThreadOptions = CodexThreadOptions()
