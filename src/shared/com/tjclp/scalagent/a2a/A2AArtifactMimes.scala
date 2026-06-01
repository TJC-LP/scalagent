package com.tjclp.scalagent.a2a

private[a2a] object A2AArtifactMimes:
  def guess(name: String): Option[String] =
    val lower = name.toLowerCase
    if lower.endsWith(".docx") then Some("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    else if lower.endsWith(".xlsx") then Some("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    else if lower.endsWith(".csv") then Some("text/csv")
    else if lower.endsWith(".doc") then Some("application/msword")
    else if lower.endsWith(".pdf") then Some("application/pdf")
    else if lower.endsWith(".txt") then Some("text/plain")
    else if lower.endsWith(".json") then Some("application/json")
    else if lower.endsWith(".md") then Some("text/markdown")
    else None
end A2AArtifactMimes
