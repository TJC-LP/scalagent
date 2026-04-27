package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.config.AgentOptions
import munit.FunSuite

class A2AServerAppSpec extends FunSuite:

  private object TestServer extends A2AServerApp[TestServer.type]:
    override def description: String = "Test A2A server"
    override def host: String        = "127.0.0.1"
    override def port: Int           = 3999
    override def agentOptions: AgentOptions =
      AgentOptions.default.withMaxTurns(1)
    override def skills: List[AgentSkill] =
      List(AgentSkill(id = "test", name = "Test skill", description = "Test skill"))

  test("A2AServerApp builds A2AServer config from overrides"):
    val config = TestServer.config

    assertEquals(config.name, "TestServer")
    assertEquals(config.description, "Test A2A server")
    assertEquals(config.host, "127.0.0.1")
    assertEquals(config.port, 3999)
    assertEquals(config.agentOptions.maxTurns, Some(1))
    assertEquals(config.skills.map(_.id), List("test"))
    assertEquals(config.url, "http://127.0.0.1:3999")
end A2AServerAppSpec
