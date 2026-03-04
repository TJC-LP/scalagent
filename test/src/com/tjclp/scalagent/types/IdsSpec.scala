package com.tjclp.scalagent.types

import munit.FunSuite
import zio.json.*

class IdsSpec extends FunSuite:

  test("SessionUuid validates UUID strings"):
    val uuid = "123e4567-e89b-12d3-a456-426614174000"
    assertEquals(SessionUuid(uuid), Right(SessionUuid.unsafe(uuid)))

  test("SessionUuid rejects invalid UUID strings"):
    assert(SessionUuid("not-a-uuid").isLeft)

  test("SessionUuid JSON decoder rejects invalid UUID strings"):
    val decoded = """"not-a-uuid"""".fromJson[SessionUuid]
    assert(decoded.isLeft)

  test("SessionUuid JSON round-trip preserves value"):
    val uuid = SessionUuid("123e4567-e89b-12d3-a456-426614174000").toOption.get
    val decoded = uuid.toJson.fromJson[SessionUuid]
    assertEquals(decoded, Right(uuid))
