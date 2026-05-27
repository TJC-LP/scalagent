package com.tjclp.scalagent.a2a

private[a2a] object A2APathRouting:
  private val knownPrefixes = Set("message:send", "message:stream", "tasks", "extendedAgentCard")

  def splitTenant(pathname: String): (Option[String], String) =
    val stripped = pathname.stripPrefix("/")
    val segments = if stripped.isEmpty then Nil else stripped.split("/", -1).toList
    segments match
      case first :: rest if first.nonEmpty && !knownPrefixes.contains(first) =>
        Some(first) -> ("/" + rest.mkString("/"))
      case _ =>
        None -> pathname
