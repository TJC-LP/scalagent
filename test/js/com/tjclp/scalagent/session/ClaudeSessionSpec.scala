package com.tjclp.scalagent.session

import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import zio.*
import com.tjclp.scalagent.errors.AgentError

class ClaudeSessionSpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: IO[AgentError, A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task.mapError(e => new Exception(e.message)))
    }

  private def promptSuggestion(text: String, uuid: String): js.Dynamic =
    js.Dynamic.literal(
      `type` = "prompt_suggestion",
      suggestion = text,
      uuid = uuid,
      session_id = "session-1"
    )

  private def fakeRawSession(
      messages: List[js.Dynamic] = Nil,
      onSend: String => js.Promise[Unit] = _ => js.Promise.resolve(()),
      onClose: () => Unit = () => (),
      onReturn: () => js.Promise[js.Any] = () => js.Promise.resolve(js.Dynamic.literal(done = true)),
      sessionIdValue: String = "session-1"
  ): RawSession =
    var streamIndex = 0
    val raw = js.Dynamic.literal(
      sessionId = sessionIdValue,
      send = (msg: String) => onSend(msg),
      stream = () =>
        streamIndex = 0
        val gen = js.Dynamic.literal(
          next = () =>
            if streamIndex < messages.length then
              val value = messages(streamIndex)
              streamIndex += 1
              js.Promise.resolve(js.Dynamic.literal(done = false, value = value))
            else
              js.Promise.resolve(js.Dynamic.literal(done = true))
        )
        gen.updateDynamic("return")((_: js.UndefOr[Unit]) => onReturn())
        gen,
      close = () => onClose()
    )
    raw.asInstanceOf[RawSession]

  test("close is idempotent"):
    var closeCalls = 0
    val raw = fakeRawSession(onClose = () => closeCalls += 1)
    val session = ClaudeSession.fromRaw(raw)

    // Call close twice — second call should be a no-op
    runTask(session.close.flatMap(_ => session.close)).map { _ =>
      assertEquals(closeCalls, 1)
    }

  test("send after close fails with SessionClosed"):
    val raw = fakeRawSession()
    val session = ClaudeSession.fromRaw(raw)

    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(
        session.close.flatMap { _ =>
          // After close, send should fail
          session.send("hello").runDrain.either.map {
            case Left(AgentError.SessionClosed(_, _)) => ()
            case other => fail(s"Expected SessionClosed, got: $other")
          }
        }.mapError(e => new Exception(e.message))
      )
    }

  test("send cleans up previous turn before starting new one"):
    var returnCalls = 0
    val raw = fakeRawSession(
      messages = List(promptSuggestion("reply", "msg-1")),
      onReturn = () =>
        returnCalls += 1
        js.Promise.resolve(js.Dynamic.literal(done = true))
    )
    val session = ClaudeSession.fromRaw(raw)

    runTask(
      session.send("first").runDrain *>
        session.send("second").runDrain
    ).map { _ =>
      // First turn cleaned up when second send started, plus second turn cleanup on drain
      assert(returnCalls >= 1, s"Expected at least 1 return call, got $returnCalls")
    }

  test("interrupt after close is a no-op"):
    val raw = fakeRawSession()
    val session = ClaudeSession.fromRaw(raw)

    // Close first, then interrupt should succeed silently
    runTask(session.close.flatMap(_ => session.interrupt)).map { _ =>
      // Should not throw
    }
