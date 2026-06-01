package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.config.AgentOptions
import munit.FunSuite
import scala.scalajs.js
import zio.*

class A2AServerAppSpec extends FunSuite:

  private object TestServer extends A2AServerApp[TestServer.type]:
    override def description: String = "Test A2A server"
    override def host: String        = "127.0.0.1"
    override def port: Int           = 3999
    override def agentOptions: AgentOptions =
      AgentOptions.default.withMaxTurns(1)
    override def executionMode: ExecutionMode = ExecutionMode.Synchronous
    override def taskTimeout: Option[Duration] = Some(30.seconds)
    override def capabilities: AgentCapabilities =
      AgentCapabilities.default.copy(pushNotifications = true)
    override def skills: List[AgentSkill] =
      List(AgentSkill(id = "test", name = "Test skill", description = "Test skill"))

  private object EnvServer extends A2AServerApp[EnvServer.type]:
    override def description: String = "Env A2A server"

  private object EnvServerV03 extends A2AServerAppV03[EnvServerV03.type]:
    override def description: String = "Env A2A v0.3 server"

  test("A2AServerApp builds A2AServer config from overrides"):
    val config = TestServer.config

    assertEquals(config.name, "TestServer")
    assertEquals(config.description, "Test A2A server")
    assertEquals(config.host, "127.0.0.1")
    assertEquals(config.port, 3999)
    assertEquals(config.agentOptions.maxTurns, Some(1))
    assertEquals(config.executionMode, ExecutionMode.Synchronous)
    assertEquals(config.taskTimeout, Some(30.seconds))
    assertEquals(config.capabilities.pushNotifications, true)
    assertEquals(config.skills.map(_.id), List("test"))
    assertEquals(config.url, "http://127.0.0.1:3999")

  test("A2AServerApp supports effectful agent options"):
    val config = zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(TestServer.configZIO).getOrThrowFiberFailure()
    }

    assertEquals(config.agentOptions.maxTurns, Some(1))

  test("A2AServerApp reads JS process env for host and port"):
    withProcessEnv(
      "A2A_HOST"     -> Some("127.0.0.42"),
      "A2A_PORT"     -> Some("4017"),
      "SERVICE_HOST" -> Some("127.0.0.99"),
      "SERVICE_PORT" -> Some("4999"),
    ) {
      val config = EnvServer.config
      assertEquals(config.host, "127.0.0.42")
      assertEquals(config.port, 4017)
    }

  test("A2AServerApp falls back to SERVICE_* JS process env"):
    withProcessEnv(
      "A2A_HOST"     -> None,
      "A2A_PORT"     -> None,
      "SERVICE_HOST" -> Some("127.0.0.88"),
      "SERVICE_PORT" -> Some("4021"),
    ) {
      val config = EnvServer.config
      assertEquals(config.host, "127.0.0.88")
      assertEquals(config.port, 4021)
    }

  test("A2AServerAppV03 reads JS process env for host and port"):
    withProcessEnv(
      "A2A_HOST" -> Some("127.0.0.43"),
      "A2A_PORT" -> Some("4018"),
    ) {
      val config = EnvServerV03.config
      assertEquals(config.host, "127.0.0.43")
      assertEquals(config.port, 4018)
    }

  private def withProcessEnv(values: (String, Option[String])*)(body: => Unit): Unit =
    val env = js.Dynamic.global.process.env
    val previous = values.map { case (name, _) =>
      val value = env.selectDynamic(name)
      name -> (if js.isUndefined(value) then None else Some(value.asInstanceOf[String]))
    }
    try
      values.foreach {
        case (name, Some(value)) => env.updateDynamic(name)(value)
        case (name, None)        => js.special.delete(env, name)
      }
      body
    finally
      previous.foreach {
        case (name, Some(value)) => env.updateDynamic(name)(value)
        case (name, None)        => js.special.delete(env, name)
      }
end A2AServerAppSpec
