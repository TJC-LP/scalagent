package com.tjclp.scalagent.a2a

import java.util.concurrent.TimeoutException

import zio.*

private[a2a] object A2AClientPolling:
  // Status polling grows the interval (capped) and jitters each sleep so that N
  // clients started together don't synchronize into a thundering herd against a
  // shared agent. NB: this is terminal-status polling, not error-retry — HTTP
  // 429/503 + Retry-After awareness lives in the transport and is not honored
  // here yet (tracked follow-up).
  private val BackoffFactor    = 1.5
  private val MaxBackoffFactor = 8L
  private val JitterLow        = 0.8
  private val JitterHigh       = 1.2

  def awaitTask(
    taskId: TaskId,
    pollEvery: Duration,
    timeout: Option[Duration],
    historyLength: Option[Int],
  )(getTask: (TaskId, Option[Int]) => Task[A2ATask]
  ): Task[A2ATask] =
    val cap = pollEvery * MaxBackoffFactor.toDouble

    def nextDelay(delay: Duration): Duration =
      val grown = delay * BackoffFactor
      if grown > cap then cap else grown

    def loop(delay: Duration): Task[A2ATask] =
      getTask(taskId, historyLength).flatMap { task =>
        if task.isStreamEnding then ZIO.succeed(task)
        else
          for
            factor   <- Random.nextDoubleBetween(JitterLow, JitterHigh)
            _        <- ZIO.sleep(delay * factor)
            nextTask <- loop(nextDelay(delay))
          yield nextTask
      }

    timeout match
      case Some(duration) =>
        loop(pollEvery).timeoutFail(
          TimeoutException(s"A2A task ${taskId.value} did not reach a stream-ending state within $duration")
        )(duration)
      case None =>
        loop(pollEvery)
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
