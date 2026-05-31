package com.tjclp.scalagent.a2a

import munit.FunSuite

class A2AServerAgentCardSpec extends FunSuite:
  test("shared server AgentCard builder advertises JSON-RPC and REST interfaces with tenant"):
    val capabilities = AgentCapabilities.default.copy(streaming = false)
    val skill        = AgentSkill("skill-a", "Skill A", "A test skill", tags = List("custom"))
    val card = A2AServerAgentCard(
      name = "CardBuilder",
      description = "Shared card builder",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = capabilities,
      skills = List(skill),
      tenant = Some("tenant-a"),
    )

    assertEquals(card.name, "CardBuilder")
    assertEquals(card.description, "Shared card builder")
    assertEquals(card.capabilities, capabilities)
    assertEquals(
      card.supportedInterfaces,
      List(
        AgentInterface.jsonRpc("https://agent.example.test/a2a", Some("tenant-a")),
        AgentInterface.rest("https://agent.example.test/a2a", Some("tenant-a")),
      ),
    )
    assertEquals(card.skills, List(AgentSkill.withRequiredTags(skill)))

  test("shared server AgentCard builder creates required default skill"):
    val card = A2AServerAgentCard(
      name = "DefaultSkill",
      description = "Default skill description",
      baseUrl = "https://agent.example.test/a2a",
      capabilities = AgentCapabilities.default,
      skills = Nil,
      tenant = None,
    )

    assertEquals(card.skills, AgentCard.requiredSkills("DefaultSkill", "Default skill description", Nil))
end A2AServerAgentCardSpec
