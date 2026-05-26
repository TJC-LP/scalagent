package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import munit.FunSuite
import zio.*

class A2ATaskStoreSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def task(id: TaskId, contextId: ContextId, text: String): A2ATask =
    val message = A2AMessage.agentText(text, Some(contextId)).copy(taskId = Some(id))
    A2ATask(
      id = id,
      contextId = contextId,
      status = TaskStatus.completed(message),
      history = List(message),
    )

  test("in-memory task store isolates tasks by tenant"):
    val sharedId = TaskId("shared")
    val taskA    = task(sharedId, ContextId("ctx-a"), "tenant-a")
    val taskB    = task(sharedId, ContextId("ctx-b"), "tenant-b")

    val program =
      for
        store       <- ZIO.succeed(A2ATaskStore.inMemory)
        _           <- store.save(taskA, Some("tenant-a"))
        _           <- store.save(taskB, Some("tenant-b"))
        loadedA     <- store.load(sharedId, Some("tenant-a"))
        loadedB     <- store.load(sharedId, Some("tenant-b"))
        loadedNone  <- store.load(sharedId, None)
        listedA     <- store.list(A2ARequest.TasksList(), Some("tenant-a"))
        listedB     <- store.list(A2ARequest.TasksList(), Some("tenant-b"))
        listedNone  <- store.list(A2ARequest.TasksList(), None)
      yield (loadedA, loadedB, loadedNone, listedA, listedB, listedNone)

    runTask(program).map { case (loadedA, loadedB, loadedNone, listedA, listedB, listedNone) =>
      assertEquals(loadedA.map(_.contextId), Some(ContextId("ctx-a")))
      assertEquals(loadedB.map(_.contextId), Some(ContextId("ctx-b")))
      assertEquals(loadedNone, None)
      assertEquals(listedA.tasks.map(_.contextId), List(ContextId("ctx-a")))
      assertEquals(listedB.tasks.map(_.contextId), List(ContextId("ctx-b")))
      assertEquals(listedNone.tasks, Nil)
    }
end A2ATaskStoreSpec
