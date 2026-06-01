package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.config.AgentOptions
import com.tjclp.scalagent.core.{AgentWorkspace, WorkspaceInput}
import zio.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

/**
 * A2A → [[AgentWorkspace]] bridge.
 *
 * Stages every `Part.File.Bytes` on an incoming [[A2AMessage]] to a
 * per-task tmp directory under `os.tmpdir()`, builds a typed
 * [[AgentWorkspace]] over those files, and (after the inner agent
 * settles) diffs the directory to find files the agent wrote so they
 * can be returned as A2A [[Artifact]]s.
 *
 * `Part.File.Uri` payloads are surfaced in the default prompt as
 * references (`Files referenced by URI: …`); this bridge does NOT
 * fetch them, since URI fetch is per-environment (auth, allow-lists)
 * and belongs in a higher-level adapter.
 *
 * == Default usage ==
 *
 * Wire into an [[A2AServerLive.Config]] via the
 * [[A2AServer.workspaceStaging]] sugar:
 *
 * {{{
 * A2AServerLive.Config(
 *   ...,
 *   invocationPreparer = Some(A2AServer.workspaceStaging),
 * )
 * }}}
 *
 * == Power-user usage ==
 *
 * Customize the prompt or skip URI surfacing by writing the preparer
 * inline:
 *
 * {{{
 * invocationPreparer = Some { (message, taskId) =>
 *   ZIO.attempt {
 *     val staged = WorkspaceStaging.stageFromMessage(message, taskId)
 *     val ndaPath = staged.workspace.inputs
 *       .find(_.mimeType.exists(_.contains("wordprocessingml")))
 *       .map(_.path)
 *     val prompt = ndaPath.fold(message.text) { p =>
 *       s"Redline ${'$'}p per the TJC playbook."
 *     }
 *     InvocationContext(
 *       prompt = prompt,
 *       workspace = Some(staged.workspace),
 *       artifactsAfter = staged.collectArtifacts,
 *       cleanup = staged.cleanup,
 *     )
 *   }
 * }
 * }}}
 */
object WorkspaceStaging:

  /**
   * Result of staging one [[A2AMessage]]: the typed workspace, the
   * default prompt (user text + manifest), and the deferred
   * artifact-collection / cleanup tasks the executor should run on
   * completion.
   */
  final case class StagedWorkspace(
    workspace: AgentWorkspace,
    defaultPrompt: String,
    collectArtifacts: Task[List[Artifact]],
    cleanup: Task[Unit]):

    /**
     * Convert to an [[InvocationContext]] using the default prompt
     * (user text + manifest). Agents that want a custom prompt
     * should build the `InvocationContext` themselves and reference
     * `staged.workspace`, `staged.collectArtifacts`, `staged.cleanup`.
     */
    def toInvocationContext: InvocationContext =
      InvocationContext(
        prompt = defaultPrompt,
        optionsModifier = addWorkspaceRootToOptions(workspace),
        workspace = Some(workspace),
        artifactsAfter = collectArtifacts,
        cleanup = cleanup,
      )
  end StagedWorkspace

  /**
   * Stage every byte-encoded file part on `message` to a fresh
   * per-task tmp dir, plus a sibling `output/` subdir the agent uses
   * for deliverables. Build the workspace and the post-run helpers.
   *
   * Raises no exceptions in the happy path. Throws if the OS rejects
   * mkdtempSync or the base64 decode fails on a malformed payload —
   * such failures bubble through the executor's `Task` boundary as
   * usual and result in a `failed` task status.
   */
  def stageFromMessage(message: A2AMessage, taskId: TaskId): StagedWorkspace =
    val taskRoot  = Fs.mkdtempSync(s"${Os.tmpdir()}/tjc-a2a-${A2AFileNames.safeStem(taskId.value)}-")
    val outputDir = Path.join(taskRoot, "output")
    Fs.mkdirSync(outputDir, js.Dynamic.literal(recursive = true))
    val (staged, uriRefs) = decodeFiles(message, taskRoot)
    val workspace         = AgentWorkspace(
      rootDir = taskRoot,
      outputDir = outputDir,
      inputs = staged,
    )
    val userText      = message.text.trim
    val defaultPrompt = augmentPrompt(userText, workspace.renderManifest, uriRefs)
    StagedWorkspace(
      workspace = workspace,
      defaultPrompt = defaultPrompt,
      collectArtifacts = ZIO.attempt(collectArtifacts(outputDir)),
      cleanup = ZIO.attempt(cleanup(taskRoot)).ignore,
    )

  // ------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------

  private def decodeFiles(
    message: A2AMessage,
    taskRoot: String,
  ): (List[WorkspaceInput], List[FileContent.Uri]) =
    val fileParts             = message.parts.collect { case f: Part.File => f.file }
    val (uriParts, byteParts) = fileParts.partitionMap {
      case u: FileContent.Uri   => Left(u)
      case b: FileContent.Bytes => Right(b)
    }

    val usedNames = scala.collection.mutable.Set.empty[String]
    val staged    = byteParts.zipWithIndex.map {
      case (bytes, idx) =>
        val safeName =
          uniqueName(
            sanitizeName(bytes.name.getOrElse(s"upload-$idx"), idx),
            idx,
            usedNames,
          )
        val target = Path.join(taskRoot, safeName)
        writeBase64(target, bytes.bytes)
        WorkspaceInput(
          path = target,
          originalName = bytes.name.getOrElse(safeName),
          mimeType = bytes.mimeType,
        )
    }

    (staged, uriParts)
  end decodeFiles

  /**
   * Compose the default prompt: file manifest + URI references (if any)
   * + the user text.
   */
  private def augmentPrompt(
    userText: String,
    manifest: String,
    uriRefs: List[FileContent.Uri],
  ): String =
    if manifest.isEmpty && uriRefs.isEmpty then userText
    else
      val sb = new StringBuilder
      if manifest.nonEmpty then sb.append(manifest)
      if uriRefs.nonEmpty then
        sb.append("Files referenced by URI (fetch with your own tools):\n")
        uriRefs.foreach { u =>
          val mime = u.mimeType.fold("")(m => s" ($m)")
          sb.append(s"  - ${u.uri}$mime\n")
        }
      sb.append("\n")
      if userText.nonEmpty then sb.append(userText)
      sb.toString

  /** Reject path-traversal-shaped names; otherwise pass through. */
  private def sanitizeName(name: String, idx: Int): String =
    val trimmed    = name.trim
    val isPathLike =
      trimmed.isEmpty ||
        trimmed == "." ||
        trimmed == ".." ||
        trimmed.exists(ch => ch == '/' || ch == '\\' || ch == 0)
    if isPathLike then s"upload-$idx" else trimmed

  private def uniqueName(
    name: String,
    idx: Int,
    usedNames: scala.collection.mutable.Set[String],
  ): String =
    if !usedNames.contains(name) then
      usedNames += name
      name
    else
      var attempt   = 0
      var candidate = disambiguateName(name, idx.toString)
      while usedNames.contains(candidate) do
        attempt += 1
        candidate = disambiguateName(name, s"$idx-$attempt")
      usedNames += candidate
      candidate

  private def disambiguateName(name: String, suffix: String): String =
    val dot = name.lastIndexOf('.')
    if dot > 0 then s"${name.substring(0, dot)}-$suffix${name.substring(dot)}"
    else s"$name-$suffix"

  private def addWorkspaceRootToOptions(workspace: AgentWorkspace)(options: AgentOptions): AgentOptions =
    if options.cwd.contains(workspace.rootDir) ||
      options.additionalDirectories.contains(workspace.rootDir)
    then options
    else options.copy(additionalDirectories = options.additionalDirectories :+ workspace.rootDir)

  private def writeBase64(target: String, b64: String): Unit =
    val buffer = js.Dynamic.global.Buffer.from(b64, "base64")
    Fs.writeFileSync(target, buffer.asInstanceOf[Uint8Array])

  private def readFileBase64(path: String): String =
    val buffer = Fs.readFileSync(path)
    js.Dynamic.global.Buffer
      .from(buffer)
      .applyDynamic("toString")("base64")
      .asInstanceOf[String]

  /**
   * Walk `outputDir` and build an [[Artifact]] (single byte-encoded
   * file part) for every file the agent wrote there. Inputs and
   * scratch files live elsewhere under the workspace root and are
   * deliberately excluded — only what the agent decided to save under
   * `outputDir` flows back to the requester.
   */
  private def collectArtifacts(outputDir: String): List[Artifact] =
    listAllFiles(outputDir).sortBy(path => relativePath(outputDir, path)).map { path =>
      val relative = relativePath(outputDir, path)
      val name     = Path.basename(path)
      val mime     = A2AArtifactMimes.guess(name)
      val bytes    = readFileBase64(path)
      Artifact(
        artifactId = relative,
        parts = List(
          Part.File(
            FileContent.Bytes(
              bytes = bytes,
              name = Some(name),
              mimeType = mime,
            )
          )
        ),
        name = Some(name),
      )
    }
  end collectArtifacts

  private def cleanup(taskRoot: String): Unit =
    try Fs.rmSync(taskRoot, js.Dynamic.literal(recursive = true, force = true))
    catch case _: Throwable => ()

  private def listAllFiles(dir: String): List[String] =
    val acc = scala.collection.mutable.ListBuffer.empty[String]
    walk(dir, acc)
    acc.toList

  private def walk(path: String, acc: scala.collection.mutable.ListBuffer[String]): Unit =
    val stat = Fs.statSync(path)
    if stat.isDirectory().asInstanceOf[Boolean] then
      Fs.readdirSync(path).foreach(child => walk(Path.join(path, child), acc))
    else
      val _ = acc.append(path)

  private def relativePath(from: String, to: String): String =
    Path.relative(from, to).replace('\\', '/')

  // ------------------------------------------------------------------
  // node:fs / node:os / node:path facade — local to this module so
  // callers don't have to import a shared facade module.
  // ------------------------------------------------------------------

  @js.native
  @JSImport("node:fs", JSImport.Namespace)
  private object Fs extends js.Object:
    def mkdtempSync(prefix: String): String                 = js.native
    def mkdirSync(path: String, options: js.Object): Unit   = js.native
    def writeFileSync(path: String, data: Uint8Array): Unit = js.native
    def readFileSync(path: String): Uint8Array              = js.native
    def readdirSync(path: String): js.Array[String]         = js.native
    def statSync(path: String): js.Dynamic                  = js.native
    def rmSync(path: String, options: js.Object): Unit      = js.native

  @js.native
  @JSImport("node:os", JSImport.Namespace)
  private object Os extends js.Object:
    def tmpdir(): String = js.native

  @js.native
  @JSImport("node:path", JSImport.Namespace)
  private object Path extends js.Object:
    def join(a: String, b: String): String         = js.native
    def relative(from: String, to: String): String = js.native
    def basename(path: String): String             = js.native
end WorkspaceStaging
