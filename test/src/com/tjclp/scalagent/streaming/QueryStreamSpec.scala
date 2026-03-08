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

  private def promptSuggestion(text: String, uuid: String): js.Dynamic =
    js.Dynamic.literal(
      `type` = "prompt_suggestion",
      suggestion = text,
      uuid = uuid,
      session_id = "session-1"
    )

  private def rawQueryFromMessages(
      messages: List[js.Dynamic],
      onReturn: () => js.Promise[js.Any] = () => js.Promise.resolve(js.Dynamic.literal(done = true)),
      onInterrupt: () => js.Promise[Unit] = () => js.Promise.resolve(()),
      onClose: () => Unit = () => ()
  ): RawQuery =
    var index = 0
    val query = js.Dynamic.literal(
      next = () =>
        if index < messages.length then
          val value = messages(index)
          index += 1
          js.Promise.resolve(js.Dynamic.literal(done = false, value = value))
        else
          js.Promise.resolve(js.Dynamic.literal(done = true)),
      interrupt = () => onInterrupt(),
      close = () => onClose(),
      streamInput = (_: js.Any) => js.Promise.resolve(())
    )
    query.updateDynamic("return")((_: js.UndefOr[Unit]) => onReturn())
    query.asInstanceOf[RawQuery]

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

  test("messages cleanup runs on early consumer termination"):
    var returnCalls = 0
    val rawQuery = rawQueryFromMessages(
      messages = List(promptSuggestion("first", "msg-1"), promptSuggestion("second", "msg-2")),
      onReturn = () =>
        returnCalls += 1
        js.Promise.resolve(js.Dynamic.literal(done = true))
    )

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.messages.take(1).runCollect).map { messages =>
      assertEquals(messages.size, 1)
      assertEquals(returnCalls, 1)
    }

  test("close is idempotent"):
    var returnCalls = 0
    var closeCalls = 0
    val rawQuery = rawQueryFromMessages(
      messages = Nil,
      onReturn = () =>
        returnCalls += 1
        js.Promise.resolve(js.Dynamic.literal(done = true)),
      onClose = () => closeCalls += 1
    )

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.close() *> queryStream.close() *> queryStream.cleanupFailures).map { failures =>
      assertEquals(returnCalls, 1)
      assertEquals(closeCalls, 1)
      assertEquals(failures, Nil)
    }

  test("cleanup failures are recorded without failing close"):
    var closeCalls = 0
    val rawQuery = rawQueryFromMessages(
      messages = Nil,
      onReturn = () => js.Promise.reject(js.Dynamic.literal(message = "boom")),
      onClose = () => closeCalls += 1
    )

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.close() *> queryStream.cleanupFailures).map { failures =>
      assertEquals(closeCalls, 1)
      assertEquals(failures.map(_.operation), List("return"))
      assert(failures.headOption.exists(_.message.nonEmpty))
    }

  test("normal completion runs cleanup"):
    var returnCalls = 0
    val rawQuery = rawQueryFromMessages(
      messages = List(promptSuggestion("only", "msg-1")),
      onReturn = () =>
        returnCalls += 1
        js.Promise.resolve(js.Dynamic.literal(done = true))
    )

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.messages.runCollect).map { messages =>
      assertEquals(messages.size, 1)
      assertEquals(returnCalls, 1)
    }

  test("interrupt runs cleanup"):
    var returnCalls = 0
    var interruptCalls = 0
    val rawQuery = rawQueryFromMessages(
      messages = Nil,
      onReturn = () =>
        returnCalls += 1
        js.Promise.resolve(js.Dynamic.literal(done = true)),
      onInterrupt = () =>
        interruptCalls += 1
        js.Promise.resolve(())
    )

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.interrupt *> queryStream.cleanupFailures).map { failures =>
      assertEquals(interruptCalls, 1)
      assertEquals(returnCalls, 1)
      assertEquals(failures, Nil)
    }

  test("close after normal completion is a no-op"):
    var returnCalls = 0
    var closeCalls = 0
    val rawQuery = rawQueryFromMessages(
      messages = List(promptSuggestion("only", "msg-1")),
      onReturn = () =>
        returnCalls += 1
        js.Promise.resolve(js.Dynamic.literal(done = true)),
      onClose = () => closeCalls += 1
    )

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.messages.runDrain *> queryStream.close() *> queryStream.cleanupFailures).map { failures =>
      assertEquals(returnCalls, 1)
      assertEquals(closeCalls, 0)
      assertEquals(failures, Nil)
    }

  test("double interrupt is idempotent"):
    var interruptCalls = 0
    var returnCalls = 0
    val rawQuery = rawQueryFromMessages(
      messages = Nil,
      onReturn = () =>
        returnCalls += 1
        js.Promise.resolve(js.Dynamic.literal(done = true)),
      onInterrupt = () =>
        interruptCalls += 1
        js.Promise.resolve(())
    )

    val queryStream = QueryStream(rawQuery)

    runTask(queryStream.interrupt *> queryStream.interrupt).map { _ =>
      assertEquals(interruptCalls, 1)
      assertEquals(returnCalls, 1)
    }
