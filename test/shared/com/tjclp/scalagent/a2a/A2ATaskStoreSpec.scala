package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import munit.FunSuite
import zio.*
import zio.json.*

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

  private def taskAt(id: String, timestamp: String): A2ATask =
    val base = task(TaskId(id), ContextId(s"ctx-$id"), id)
    base.copy(status = base.status.copy(timestamp = Some(timestamp)))

  test("transformIfNotTerminal: transforms non-terminal, preserves terminal, skips absent"):
    val ctx             = ContextId("cas-ctx")
    def msg(id: TaskId) = A2AMessage.agentText("m", Some(ctx)).copy(taskId = Some(id))
    val working         = A2ATask(TaskId("cas-working"), ctx, TaskStatus.working(Some(msg(TaskId("cas-working")))))
    val completed       = A2ATask(TaskId("cas-done"), ctx, TaskStatus.completed(msg(TaskId("cas-done"))))
    def toFailed(t: A2ATask): A2ATask = t.copy(status = TaskStatus.failed(msg(t.id)))

    val effect =
      for
        store <- ZIO.succeed(A2ATaskStore.inMemory)
        _     <- store.save(working, None)
        _     <- store.save(completed, None)
        r1    <- store.transformIfNotTerminal(working.id, None)(toFailed)
        s1    <- store.load(working.id, None)
        r2    <- store.transformIfNotTerminal(completed.id, None)(toFailed)
        s2    <- store.load(completed.id, None)
        r3    <- store.transformIfNotTerminal(TaskId("cas-absent"), None)(toFailed)
      yield (r1, s1, r2, s2, r3)

    runTask(effect).map { case (r1, s1, r2, s2, r3) =>
      assertEquals(r1.map(_.status.state), Some(TaskState.Failed))    // non-terminal -> transformed
      assertEquals(s1.map(_.status.state), Some(TaskState.Failed))    // persisted
      assertEquals(r2.map(_.status.state), Some(TaskState.Completed)) // terminal -> returned unchanged
      assertEquals(s2.map(_.status.state), Some(TaskState.Completed)) // NOT clobbered
      assertEquals(r3, None)                                          // absent -> None
    }

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

  test("in-memory task store uses opaque cursor page tokens"):
    val program =
      for
        store <- ZIO.succeed(A2ATaskStore.inMemory)
        _     <- store.save(taskAt("task-1", "2026-01-01T00:00:01Z"), None)
        _     <- store.save(taskAt("task-2", "2026-01-01T00:00:02Z"), None)
        _     <- store.save(taskAt("task-3", "2026-01-01T00:00:03Z"), None)
        first <- store.list(A2ARequest.TasksList(pageSize = Some(2)), None)
        second <- store.list(
          A2ARequest.TasksList(pageSize = Some(2), pageToken = first.nextPageToken),
          None,
        )
        legacy <- store.list(A2ARequest.TasksList(pageSize = Some(1), pageToken = Some("1")), None)
        negative <- store
          .list(A2ARequest.TasksList(pageToken = Some("-1")), None)
          .either
      yield (first, second, legacy, negative)

    runTask(program).map { case (first, second, legacy, negative) =>
      assertEquals(first.tasks.map(_.id), List(TaskId("task-3"), TaskId("task-2")))
      assert(first.nextPageToken.exists(token => token.startsWith("v1:") && token.toIntOption.isEmpty))
      assertEquals(second.tasks.map(_.id), List(TaskId("task-1")))
      assertEquals(second.nextPageToken, None)
      assertEquals(legacy.tasks.map(_.id), List(TaskId("task-2")))
      assert(negative.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidParams
        case _               => false
      })
    }

  test("cursor page tokens are stable when newer tasks arrive between pages"):
    val program =
      for
        store <- ZIO.succeed(A2ATaskStore.inMemory)
        _     <- store.save(taskAt("task-1", "2026-01-01T00:00:01Z"), None)
        _     <- store.save(taskAt("task-2", "2026-01-01T00:00:02Z"), None)
        _     <- store.save(taskAt("task-3", "2026-01-01T00:00:03Z"), None)
        first <- store.list(A2ARequest.TasksList(pageSize = Some(2)), None)
        _     <- store.save(taskAt("task-4", "2026-01-01T00:00:04Z"), None)
        second <- store.list(
          A2ARequest.TasksList(pageSize = Some(2), pageToken = first.nextPageToken),
          None,
        )
      yield (first, second)

    runTask(program).map { case (first, second) =>
      assertEquals(first.tasks.map(_.id), List(TaskId("task-3"), TaskId("task-2")))
      assertEquals(second.tasks.map(_.id), List(TaskId("task-1")))
      assertEquals(second.nextPageToken, None)
    }

  test("in-memory task store filters and sorts status timestamps as instants"):
    val program =
      for
        store <- ZIO.succeed(A2ATaskStore.inMemory)
        _     <- store.save(taskAt("early", "2026-01-01T00:00:00Z"), None)
        _     <- store.save(taskAt("late", "2026-01-01T01:00:00Z"), None)
        all   <- store.list(A2ARequest.TasksList(), None)
        filtered <- store.list(
          A2ARequest.TasksList(statusTimestampAfter = Some("2026-01-01T00:30:00Z")),
          None,
        )
        invalid <- store
          .list(A2ARequest.TasksList(statusTimestampAfter = Some("not-a-timestamp")), None)
          .either
        offset <- store
          .list(A2ARequest.TasksList(statusTimestampAfter = Some("2025-12-31T20:00:00-05:00")), None)
          .either
      yield (all, filtered, invalid, offset)

    runTask(program).map { case (all, filtered, invalid, offset) =>
      assertEquals(all.tasks.map(_.id), List(TaskId("late"), TaskId("early")))
      assertEquals(filtered.tasks.map(_.id), List(TaskId("late")))
      assert(invalid.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidParams
        case _               => false
      })
      assert(offset.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidParams
        case _               => false
      })
    }

  test("in-memory task store encodes empty artifacts when includeArtifacts is true"):
    val program =
      for
        store <- ZIO.succeed(A2ATaskStore.inMemory)
        _     <- store.save(task(TaskId("empty-artifacts"), ContextId("ctx-empty-artifacts"), "empty"), None)
        hidden <- store.list(
          A2ARequest.TasksList(includeArtifacts = Some(false)),
          None,
        )
        visible <- store.list(
          A2ARequest.TasksList(includeArtifacts = Some(true)),
          None,
        )
      yield (hidden.toJson, visible.toJson)

    runTask(program).map { case (hidden, visible) =>
      assert(!hidden.contains(""""artifacts""""))
      assert(visible.contains(""""artifacts":[]"""))
    }
end A2ATaskStoreSpec
