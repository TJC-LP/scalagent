package com.tjclp.scalagent.a2a

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import munit.FunSuite
import zio.*

class A2ARuntimeRegistrySpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  test("interruptAll terminates even when a fiber finalizer re-enters the registry"):
    // Regression: interruptAll must NOT hold the registry's Ref.Synchronized
    // permit while awaiting fibers — each interrupted run's `.ensuring(remove)`
    // finalizer re-acquires the same permit, so holding it across the interrupt
    // (the old modifyZIO impl) deadlocks interrupt-vs-finalizer.
    val key = ("", "deadlock-task")
    val effect =
      for
        registry <- A2ARuntimeRegistry.make
        _        <- registry.reserve(key, 16)
        started  <- Promise.make[Nothing, Unit]
        // Mirrors a real run: blocks forever, and its finalizer removes itself
        // from the registry (which takes the same permit interruptAll would).
        fiber    <- (started.succeed(()) *> ZIO.never).ensuring(registry.remove(key)).fork
        _        <- registry.attachFiber(key, fiber)
        _        <- started.await
        _        <- registry.interruptAll
                      .timeoutFail(new RuntimeException("interruptAll deadlocked"))(5.seconds)
        remaining <- registry.bus(key)
      yield remaining

    runTask(effect).map(remaining => assertEquals(remaining, None))
end A2ARuntimeRegistrySpec
