package com.tjclp.scalagent.streaming

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.*

class QueryStreamSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def firstStreamValue(stream: js.Any): Future[js.Dynamic] =
    val streamDyn = stream.asInstanceOf[js.Dynamic]
    val iteratorFactory = js.Dynamic.global.Reflect
      .get(streamDyn, js.Symbol.asyncIterator)
      .asInstanceOf[js.Function0[js.Dynamic]]
    val iterator = iteratorFactory()
    iterator.next().asInstanceOf[js.Promise[js.Dynamic]].toFuture.map(_.value.asInstanceOf[js.Dynamic])

  test("streamUserMessage emits SDKUserMessage shape"):
    var capturedInput: Option[js.Any] = None
    val rawQuery = js.Dynamic.literal(
      streamInput = (input: js.Any) => {
        capturedInput = Some(input)
        js.Promise.resolve(())
      },
      next = () => js.Promise.resolve(js.Dynamic.literal(done = true))
    ).asInstanceOf[RawQuery]

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.streamUserMessage("follow-up", Some(MessagePriority.Next))).flatMap { _ =>
      val input = capturedInput.getOrElse(fail("Expected streamInput to be called"))
      firstStreamValue(input).map { firstMessage =>
        assertEquals(firstMessage.selectDynamic("type").asInstanceOf[String], "user")
        assertEquals(firstMessage.selectDynamic("session_id").asInstanceOf[String], "")
        assert(firstMessage.selectDynamic("parent_tool_use_id") == null)
        assertEquals(firstMessage.selectDynamic("priority").asInstanceOf[String], "next")

        val message = firstMessage.selectDynamic("message").asInstanceOf[js.Dynamic]
        assertEquals(message.selectDynamic("role").asInstanceOf[String], "user")

        val content = message.selectDynamic("content").asInstanceOf[js.Array[js.Dynamic]]
        assertEquals(content.length, 1)
        assertEquals(content(0).selectDynamic("type").asInstanceOf[String], "text")
        assertEquals(content(0).selectDynamic("text").asInstanceOf[String], "follow-up")
      }
    }

