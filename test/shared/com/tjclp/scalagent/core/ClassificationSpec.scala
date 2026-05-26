package com.tjclp.scalagent.core

import scala.compiletime.testing.typeChecks

class ClassificationSpec extends munit.FunSuite:

  test("clearance lattice allows higher levels to see lower levels"):
    summon[CanSee[Public, Public]]
    summon[CanSee[Internal, Public]]
    summon[CanSee[Internal, Internal]]
    summon[CanSee[Secret, Internal]]
    summon[CanSee[TopSecret, Secret]]

  test("clearance lattice rejects lower levels seeing higher levels"):
    assert(!typeChecks("summon[CanSee[Public, Internal]]"))
    assert(!typeChecks("summon[CanSee[Internal, Secret]]"))
    assert(!typeChecks("summon[CanSee[Secret, TopSecret]]"))

  test("Classified wraps typed output with a visibility label"):
    val classified: Classified[String, Secret] = Classified("sensitive")
    assertEquals(classified.value, "sensitive")
