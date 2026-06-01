package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.annotation.*

private[a2a] object A2AFileNames:
  @js.native
  @JSImport("node:crypto", JSImport.Namespace)
  private object Crypto extends js.Object:
    def createHash(algorithm: String): Hash = js.native

  @js.native
  private trait Hash extends js.Object:
    def update(data: String, inputEncoding: String): Hash = js.native
    def digest(encoding: String): String                  = js.native

  def safeStem(
    value: String,
    fallback: String = "task",
    maxLength: Int = 96,
  ): String =
    A2ASafeFileNames.safeSegment(value, fallback, maxLength)(sha256Hex)

  private def sha256Hex(value: String): String =
    Crypto.createHash("sha256").update(value, "utf8").digest("hex")
end A2AFileNames
