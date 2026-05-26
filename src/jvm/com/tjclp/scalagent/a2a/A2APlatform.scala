package com.tjclp.scalagent.a2a

private[a2a] object A2APlatform:
  def randomUUID(): String = java.util.UUID.randomUUID().toString
end A2APlatform
