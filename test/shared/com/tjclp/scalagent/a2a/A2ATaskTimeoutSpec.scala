package com.tjclp.scalagent.a2a

import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import munit.FunSuite

import zio.*

class A2ATaskTimeoutSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
    }

  test("disabled task timeout returns the original effect"):
    runTask(A2ATaskTimeout(TaskId("task-1"), None, ZIO.succeed("ok"))).map { result =>
      assertEquals(result, "ok")
    }

  test("enabled task timeout uses the shared A2A task timeout message"):
    runTask(A2ATaskTimeout(TaskId("task-1"), Some(10.millis), ZIO.never).either).map {
      case Left(error: TimeoutException) =>
        assert(error.getMessage.startsWith("A2A task task-1 timed out after"))
      case other =>
        fail(s"expected TimeoutException, got $other")
    }
