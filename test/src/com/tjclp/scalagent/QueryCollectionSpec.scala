package com.tjclp.scalagent

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import zio.*
import zio.stream.*
import com.tjclp.scalagent.messages.*
import com.tjclp.scalagent.TestFixtures.*

class QueryCollectionSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  test("ResultOnly keeps semantic results without transcript retention"):
    runTask(QueryCollector.collect(ZStream.fromIterable(simpleConversation), CollectionPolicy.ResultOnly)).map { result =>
      assertEquals(result.messages, Nil)
      assertEquals(result.totalMessages, simpleConversation.size)
      assert(result.hasFormalResult)
      assertEquals(result.semanticText, Right(successOutcome.result))
    }

  test("semanticText falls back to assistant text when no formal result exists"):
    runTask(QueryCollector.collect(ZStream.fromIterable(List(userMessage, assistantMessage)), CollectionPolicy.Full)).map { result =>
      assert(!result.hasFormalResult)
      assertEquals(result.semanticText, Right("Hello, I'm Claude!"))
    }

  test("BoundedRecent retains only the most recent messages"):
    runTask(QueryCollector.collect(ZStream.fromIterable(simpleConversation), CollectionPolicy.BoundedRecent(limit = 1))).map {
      result =>
        assertEquals(result.messages, List(resultSuccess))
        assertEquals(result.totalMessages, simpleConversation.size)
    }

  test("NoStreamingDeltas drops stream events from retained transcript"):
    val streamEvent = AgentMessage.StreamEvent(
      event = RawStreamEvent(
        eventType = "content_block_delta",
        index = Some(0),
        contentBlock = None,
        delta = Some(StreamDelta.TextDelta("partial"))
      ),
      parentToolUseId = None,
      uuid = testMessageUuid,
      sessionId = testSessionId
    )

    runTask(
      QueryCollector.collect(
        ZStream.fromIterable(List(streamEvent, resultSuccess)),
        CollectionPolicy.NoStreamingDeltas
      )
    ).map { result =>
      assertEquals(result.messages, List(resultSuccess))
      assertEquals(result.totalMessages, 2)
    }
