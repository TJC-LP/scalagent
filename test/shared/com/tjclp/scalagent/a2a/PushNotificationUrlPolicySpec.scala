package com.tjclp.scalagent.a2a

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import munit.FunSuite
import zio.*

class PushNotificationUrlPolicySpec extends FunSuite:
  private val runtime = Runtime.default

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  test("externalOnly rejects numeric IPv4 loopback aliases"):
    val urls = List(
      "http://2130706433/cb",
      "http://0x7f000001/cb",
      "http://017700000001/cb",
      "http://127.1/cb",
    )

    runTask(ZIO.foreach(urls)(url => PushNotificationUrlPolicy.externalOnly.validate(url).either)).map { results =>
      assert(results.forall(_.left.exists {
        case error: A2AError => error.code == A2AErrorCode.InvalidParams && error.message.contains("not allowed")
        case _               => false
      }))
    }

  test("externalOnly accepts external callback hosts"):
    runTask(PushNotificationUrlPolicy.externalOnly.validate("https://callback.example.test/a2a")).map(_ => ())
end PushNotificationUrlPolicySpec
