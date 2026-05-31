package com.tjclp.scalagent.a2a

import java.util.concurrent.TimeoutException

import zio.*

private[scalagent] object A2ATaskTimeout:
  def apply[A](
    taskId: TaskId,
    timeout: Option[Duration],
    effect: Task[A],
  ): Task[A] =
    timeout match
      case Some(duration) =>
        effect.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} timed out after $duration"))(duration)
      case None =>
        effect
