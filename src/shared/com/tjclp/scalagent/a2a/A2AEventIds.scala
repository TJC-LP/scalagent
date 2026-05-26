package com.tjclp.scalagent.a2a

/** Stable identity derivation for A2A streaming events. Pure helpers used
  * by both the JS A2AInternals event parser and the JVM A2AResponse
  * convenience constructors. */
private[a2a] object A2AEventIds:
  def taskIdFor(message: A2AMessage): TaskId =
    message.taskId.getOrElse(TaskId(message.messageId.value))

  def contextIdFor(message: A2AMessage): ContextId =
    message.contextId
      .orElse(message.taskId.map(taskId => ContextId(taskId.value)))
      .getOrElse(ContextId(message.messageId.value))

  def contextIdFor(taskId: TaskId, explicitContextId: Option[String]): ContextId =
    explicitContextId.map(ContextId(_)).getOrElse(ContextId(taskId.value))

  def artifactIdFor(taskId: TaskId, explicitArtifactId: Option[String]): String =
    explicitArtifactId.getOrElse(taskId.value)
end A2AEventIds
