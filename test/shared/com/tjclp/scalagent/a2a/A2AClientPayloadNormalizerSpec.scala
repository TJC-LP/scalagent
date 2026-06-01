package com.tjclp.scalagent.a2a

import munit.FunSuite
import zio.json.*

class A2AClientPayloadNormalizerSpec extends FunSuite:
  test("client normalizer parses task history messages without messageId"):
    val wire =
      """{
        |  "id": "task-xyz-789",
        |  "contextId": "ctx-002",
        |  "status": {
        |    "state": "TASK_STATE_COMPLETED",
        |    "timestamp": "2025-05-25T20:05:00Z"
        |  },
        |  "history": [
        |    {
        |      "role": "ROLE_USER",
        |      "parts": [{"text": "original request"}]
        |    },
        |    {
        |      "role": "ROLE_AGENT",
        |      "parts": [{"text": "processing..."}]
        |    }
        |  ]
        |}""".stripMargin

    assert(wire.fromJson[A2ATask].isLeft)
    val decoded = A2AClientPayloadNormalizer.decodeString[A2ATask](wire)

    assert(decoded.isRight)
    val task = decoded.toOption.get
    assertEquals(task.history.size, 2)
    assert(task.history.forall(_.messageId.value.startsWith("client-normalized-")))

  test("client normalizer does not synthesize ids inside free-form metadata"):
    // A user object nested under `metadata` that happens to look like a message
    // must NOT receive a synthesized messageId — the heuristics are scoped to
    // known structural positions, never arbitrary user data.
    val wire =
      """{
        |  "id": "task-meta-001",
        |  "contextId": "ctx-meta-001",
        |  "status": {
        |    "state": "TASK_STATE_COMPLETED",
        |    "timestamp": "2025-05-25T20:05:00Z"
        |  },
        |  "history": [
        |    {
        |      "role": "ROLE_USER",
        |      "parts": [{"text": "hi"}],
        |      "metadata": { "echo": { "role": "user", "parts": [{"text": "nested"}] } }
        |    }
        |  ]
        |}""".stripMargin

    val decoded = A2AClientPayloadNormalizer.decodeString[A2ATask](wire)
    assert(decoded.isRight)
    val msg = decoded.toOption.get.history.head
    // The message itself sits at a structural position → gets a synthesized id…
    assert(msg.messageId.value.startsWith("client-normalized-"))
    // …but the look-alike object under `metadata` is left untouched.
    val metadataJson = msg.metadata.map(_.toJson).getOrElse("")
    assert(
      !metadataJson.contains("client-normalized-"),
      s"normalizer leaked a synthesized id into user metadata: $metadataJson",
    )

  test("client normalizer parses canonical file and fileUrl artifact wrappers"):
    val wire =
      """{
        |  "id": "task-mixed-001",
        |  "contextId": "ctx-mixed-001",
        |  "status": {
        |    "state": "TASK_STATE_COMPLETED",
        |    "timestamp": "2025-05-25T20:15:00Z"
        |  },
        |  "artifacts": [
        |    {
        |      "artifactId": "art-file",
        |      "parts": [
        |        {
        |          "file": {
        |            "name": "report.pdf",
        |            "mediaType": "application/pdf",
        |            "bytes": "JVBERi0xLjQ="
        |          }
        |        }
        |      ]
        |    },
        |    {
        |      "artifactId": "art-url",
        |      "parts": [
        |        {
        |          "fileUrl": {
        |            "url": "https://example.com/report.pdf",
        |            "name": "report.pdf",
        |            "mediaType": "application/pdf"
        |          }
        |        }
        |      ]
        |    }
        |  ]
        |}""".stripMargin

    assert(wire.fromJson[A2ATask].isLeft)
    val decoded = A2AClientPayloadNormalizer.decodeString[A2ATask](wire)

    assert(decoded.isRight)
    val parts = decoded.toOption.get.artifacts.flatMap(_.parts)
    assert(parts.exists {
      case Part.File(FileContent.Bytes("JVBERi0xLjQ=", Some("report.pdf"), Some("application/pdf")), _) => true
      case _                                                                                              => false
    })
    assert(parts.exists {
      case Part.File(FileContent.Uri("https://example.com/report.pdf", Some("report.pdf"), Some("application/pdf")), _) => true
      case _                                                                                                             => false
    })

  test("client normalizer maps canonical file assertion wrappers to proto part fields"):
    val inlineExpected =
      """{
        |  "file": {
        |    "name": {"type": "string"},
        |    "mediaType": {"type": "string"}
        |  }
        |}""".stripMargin.fromJson[zio.json.ast.Json].toOption.get
    val urlExpected =
      """{
        |  "fileUrl": {
        |    "url": {"type": "string"},
        |    "name": {"type": "string"}
        |  }
        |}""".stripMargin.fromJson[zio.json.ast.Json].toOption.get

    assertEquals(
      A2AClientPayloadNormalizer.normalize(inlineExpected).toJson,
      """{"filename":{"type":"string"},"mediaType":{"type":"string"}}""",
    )
    assertEquals(
      A2AClientPayloadNormalizer.normalize(urlExpected).toJson,
      """{"url":{"type":"string"},"filename":{"type":"string"}}""",
    )

  test("client normalizer fills missing AgentCard and AgentSkill defaults"):
    val publicWire =
      """{
        |  "name": "Example Agent",
        |  "version": "1.0.0",
        |  "capabilities": {
        |    "streaming": false,
        |    "extendedAgentCard": true
        |  },
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://example.com/.well-known/agent-card.json",
        |      "protocolBinding": "REST",
        |      "protocolVersion": "1.0"
        |    }
        |  ]
        |}""".stripMargin
    val extendedWire =
      """{
        |  "name": "Example Agent",
        |  "version": "1.0.0",
        |  "capabilities": {
        |    "streaming": true,
        |    "extendedAgentCard": true
        |  },
        |  "skills": [
        |    {
        |      "id": "extended-skill",
        |      "name": "Extended Skill"
        |    }
        |  ],
        |  "supportedInterfaces": [
        |    {
        |      "url": "https://example.com/extendedAgentCard",
        |      "protocolBinding": "REST",
        |      "protocolVersion": "1.0"
        |    }
        |  ]
        |}""".stripMargin

    assert(publicWire.fromJson[AgentCard].isLeft)
    assert(extendedWire.fromJson[AgentCard].isLeft)

    val publicCard   = A2AClientPayloadNormalizer.decodeString[AgentCard](publicWire)
    val extendedCard = A2AClientPayloadNormalizer.decodeString[AgentCard](extendedWire)

    assert(publicCard.isRight)
    assert(extendedCard.isRight)
    assertEquals(publicCard.toOption.get.description, "Example Agent")
    assertEquals(publicCard.toOption.get.defaultInputModes, List("text/plain"))
    assertEquals(publicCard.toOption.get.defaultOutputModes, List("text/plain"))
    assert(publicCard.toOption.get.skills.nonEmpty)
    assertEquals(extendedCard.toOption.get.skills.head.description, "Extended Skill")
    assertEquals(extendedCard.toOption.get.skills.head.tags, List("extended-skill"))
end A2AClientPayloadNormalizerSpec
