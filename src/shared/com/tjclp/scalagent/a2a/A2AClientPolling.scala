package com.tjclp.scalagent.a2a

import java.util.concurrent.TimeoutException

import zio.*

private[a2a] object A2AClientPolling:
  def awaitTask(
    taskId: TaskId,
    pollEvery: Duration,
    timeout: Option[Duration],
    historyLength: Option[Int],
  )(getTask: (TaskId, Option[Int]) => Task[A2ATask]
  ): Task[A2ATask] =
    def loop: Task[A2ATask] =
      getTask(taskId, historyLength).flatMap { task =>
        if task.isStreamEnding then ZIO.succeed(task)
        else ZIO.sleep(pollEvery) *> loop
      }

    timeout match
      case Some(duration) =>
        loop.timeoutFail(
          TimeoutException(s"A2A task ${taskId.value} did not reach a stream-ending state within $duration")
        )(duration)
      case None =>
        loop
  end awaitTask

  def sendAndPoll(
    message: A2AMessage,
    config: Option[MessageSendConfiguration],
    pollEvery: Duration,
    timeout: Option[Duration],
    historyLength: Option[Int],
  )(submit: (A2AMessage, Option[MessageSendConfiguration]) => Task[A2ATask],
    getTask: (TaskId, Option[Int]) => Task[A2ATask],
  ): Task[A2ATask] =
    submit(message, config).flatMap { task =>
      if task.isStreamEnding then ZIO.succeed(task)
      else awaitTask(task.id, pollEvery, timeout, historyLength)(getTask)
    }
end A2AClientPolling
