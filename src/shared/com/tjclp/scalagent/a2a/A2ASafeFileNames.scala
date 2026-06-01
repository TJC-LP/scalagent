package com.tjclp.scalagent.a2a

private[a2a] object A2ASafeFileNames:
  def safeSegment(
    value: String,
    fallback: String = "item",
    maxLength: Int = 96,
  )(sha256Hex: String => String
  ): String =
    val raw = Option(value).getOrElse("")
    if isSafeRaw(raw, maxLength) then raw
    else
      val suffix    = "-" + sha256Hex(raw)
      val stemMax   = math.max(1, maxLength - suffix.length)
      val cleaned   = safeStem(raw).take(stemMax)
      val fallback0 = safeStem(fallback).take(stemMax)
      val stem      = if isUsefulStem(cleaned) then cleaned else if isUsefulStem(fallback0) then fallback0 else ""
      s"${if stem.nonEmpty then stem else "item"}$suffix"

  private def safeStem(value: String): String =
    value.iterator
      .map(ch => if isSafeChar(ch) then ch else '_')
      .mkString
      .replace("..", "__")
      .dropWhile(_ == '.')

  private def isSafeRaw(value: String, maxLength: Int): Boolean =
    value.nonEmpty &&
      value.length <= maxLength &&
      value != "." &&
      value != ".." &&
      !value.startsWith(".") &&
      value.forall(isSafeChar)

  private def isUsefulStem(value: String): Boolean =
    value.exists(ch => (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9'))

  private def isSafeChar(ch: Char): Boolean =
    (ch >= 'a' && ch <= 'z') ||
      (ch >= 'A' && ch <= 'Z') ||
      (ch >= '0' && ch <= '9') ||
      ch == '-' ||
      ch == '_' ||
      ch == '.'
end A2ASafeFileNames
