package com.tjclp.scalagent.core

/**
 * One staged input file the agent can read from its filesystem.
 *
 * Bridges produce these from whatever wire-level shape they handle
 * (A2A `Part.File`, CLI `--input-file`, etc.); agents consume them
 * via [[AgentWorkspace.inputs]] without knowing the source.
 *
 * @param path
 *   absolute path on the agent's filesystem
 * @param originalName
 *   the basename as the client originally sent it (may differ from
 *   the actual on-disk name, which is sanitized)
 * @param mimeType
 *   declared MIME type (e.g., from A2A `FileContent.mimeType`); may
 *   be `None` if the source didn't declare one
 */
final case class WorkspaceInput(
  path: String,
  originalName: String,
  mimeType: Option[String])

/**
 * Per-invocation typed workspace: a tmp directory split into a
 * scratch root and a dedicated `outputDir`, plus the files staged
 * into the root.
 *
 * The DSL layer is content-agnostic — `inputs` may originate from
 * any bridge (A2A, CLI, future Codex, test harness). The
 * `rootDir` / `outputDir` distinction is the workspace-vs-output
 * contract: the agent uses `rootDir` freely as scratch (unpacking,
 * intermediate scripts, partial state), and only files written under
 * `outputDir` are returned to the requester as task artifacts. This
 * keeps the artifact stream clean — no batch scripts, no
 * pack/unpack tree noise — and lets the agent fail or retry without
 * leaking work-in-progress back over the wire.
 *
 * @param rootDir
 *   absolute path of the per-invocation tmp dir; bridges typically
 *   sweep this whole tree on cleanup
 * @param outputDir
 *   absolute path where the agent saves deliverables; usually
 *   `rootDir + "/output"`. Bridges collect artifacts from here only
 * @param inputs
 *   files staged into `rootDir` (NOT under `outputDir`) that the
 *   agent can read
 */
final case class AgentWorkspace(
  rootDir: String,
  outputDir: String,
  inputs: List[WorkspaceInput]):

  /**
   * Default LLM-readable manifest of staged inputs. Bridges typically
   * append this (followed by the user-text part) to produce the prompt
   * the agent actually sees. Returns the empty string for an empty
   * workspace so it's a no-op for text-only requests.
   *
   * The manifest spells out the workspace-vs-output contract so the
   * agent knows scratch from deliverable: anything under `rootDir` is
   * private; only files written under `outputDir` flow back as task
   * artifacts.
   */
  def renderManifest: String =
    if inputs.isEmpty then ""
    else
      val sb = new StringBuilder
      sb.append(s"Workspace directory for this task: $rootDir\n")
      sb.append(
        "Use this directory freely for scratch work (unpacking, intermediate" +
          " scripts, partial outputs, etc.).\n\n"
      )
      sb.append("Files staged for this task:\n")
      inputs.foreach { f =>
        val mime = f.mimeType.fold("")(m => s" ($m)")
        sb.append(s"  - ${f.path}$mime\n")
      }
      sb.append(s"\nOutput directory: $outputDir\n")
      sb.append(
        "Save the deliverables you want returned to the requester (e.g.," +
          " redlined documents, summary reports) into the output directory" +
          " above. Only files placed there are returned as task artifacts;" +
          " files elsewhere in the workspace are scratch and will be" +
          " discarded.\n"
      )
      sb.toString
end AgentWorkspace
