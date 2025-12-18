package com.tjclp.scalagent.tools

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** File helpers for tool content (Node.js runtime). */
object ToolFiles:
  @js.native
  @JSImport("node:fs", JSImport.Namespace)
  private object Fs extends js.Object:
    def readFileSync(path: String): js.Any = js.native
    def existsSync(path: String): Boolean = js.native

  @js.native
  @JSImport("node:path", JSImport.Namespace)
  private object Path extends js.Object:
    def join(parts: String*): String = js.native
    def dirname(path: String): String = js.native
    def isAbsolute(path: String): Boolean = js.native

  @js.native
  @JSImport("node:buffer", "Buffer")
  private object NodeBuffer extends js.Object:
    def from(data: js.Any): Buffer = js.native

  @js.native
  private trait Buffer extends js.Object:
    def toString(encoding: String): String = js.native

  /** Read a file and return its contents as base64. */
  def readBase64(path: String, rootMarker: String = "build.mill", maxDepth: Int = 8): String =
    val resolved = resolvePath(path, rootMarker, maxDepth)
    NodeBuffer.from(Fs.readFileSync(resolved)).toString("base64")

  /** Resolve a path relative to the repo root (by marker) or leave absolute paths untouched. */
  def resolvePath(path: String, rootMarker: String = "build.mill", maxDepth: Int = 8): String =
    if Path.isAbsolute(path) then path
    else
      val cwd = js.Dynamic.global.process.cwd().asInstanceOf[String]
      val root = findRepoRoot(cwd, rootMarker, maxDepth)
      Path.join(root, path)

  private def findRepoRoot(start: String, marker: String, maxDepth: Int): String =
    var current = start
    var depth = 0
    while depth < maxDepth && !Fs.existsSync(Path.join(current, marker)) do
      val parent = Path.dirname(current)
      if parent == current then depth = maxDepth
      else
        current = parent
        depth += 1
    current

  /** Create an image content block from a file path. */
  def imageFromFile(path: String, mime: String = "image/png"): ToolContent.Image =
    ToolContent.Image(readBase64(path), mime)

  /** Create an audio content block from a file path. */
  def audioFromFile(path: String, mime: String = "audio/wav"): ToolContent.Audio =
    ToolContent.Audio(readBase64(path), mime)
