package com.tjclp.scalagent.a2a

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import zio.*
import zio.json.ast.Json

class A2AServerStreamSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def publishStatus(
    taskId: TaskId,
    contextId: ContextId,
    publisher: A2AEventPublisher,
    status: TaskStatus,
    finalUpdate: Boolean = false,
  ): UIO[Unit] =
    publisher.publish(
      A2AResponse.StreamEvent.TaskStatusUpdate(
        id = taskId,
        contextId = contextId,
        status = status,
        `final` = finalUpdate,
      )
    )

  private def delayedExecution(
    message: A2AMessage,
    taskId: TaskId,
    contextId: ContextId,
    publisher: A2AEventPublisher,
  ): Task[Unit] =
    for
      _ <- ZIO.sleep(25.millis)
      step1 = A2AMessage.agentText("step 1", Some(contextId)).copy(taskId = Some(taskId))
      _ <- publishStatus(taskId, contextId, publisher, TaskStatus.working(Some(step1)))
      _ <- ZIO.sleep(50.millis)
      step2 = A2AMessage.agentText("step 2", Some(contextId)).copy(taskId = Some(taskId))
      _ <- publishStatus(taskId, contextId, publisher, TaskStatus.working(Some(step2)))
      _ <- ZIO.sleep(50.millis)
      toolMessage = A2AMessage(
        role = A2ARole.Agent,
        parts = List(
          Part.Text("Calling Read"),
          Part.Data(Json.Obj("kind" -> Json.Str("tool_use"), "name" -> Json.Str("Read"))),
        ),
        contextId = Some(contextId),
        taskId = Some(taskId),
      )
      _ <- publishStatus(taskId, contextId, publisher, TaskStatus.working(Some(toolMessage)))
      _ <- ZIO.sleep(50.millis)
      finalMessage = A2AMessage.agentText("done", Some(contextId)).copy(taskId = Some(taskId))
      _ <- publishStatus(taskId, contextId, publisher, TaskStatus.completed(finalMessage), finalUpdate = true)
    yield ()

  test("message/stream receives delayed status updates before terminal status"):
    val config = A2AServer.Config(
      name = "StreamTest",
      description = "Stream test server",
      host = "127.0.0.1",
      port = 0,
      capabilities = AgentCapabilities.default.copy(streaming = true),
      executionOverride = Some(delayedExecution),
    )

    val program =
      ZIO.scoped {
        for
          server <- A2AServer.create(config)
          client <- A2AClient.discover(server.url)
          events <- client.stream(A2AMessage.userText("hello")).runCollect
        yield events.toList
      }

    runTask(program).map { events =>
      assertEquals(events.length, 5)
      assert(events.head.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot])

      val statuses = events.collect { case status: A2AResponse.StreamEvent.TaskStatusUpdate => status }
      assertEquals(statuses.length, 4)
      assertEquals(statuses.take(2).flatMap(_.status.message.map(_.text)), List("step 1", "step 2"))
      assertEquals(statuses.last.isFinal, true)
      assertEquals(statuses.last.status.state, TaskState.Completed)

      val structuredParts = statuses(2).status.message.map(_.parts).getOrElse(Nil)
      assert(structuredParts.exists(_.isInstanceOf[Part.Data]))
    }
end A2AServerStreamSpec
