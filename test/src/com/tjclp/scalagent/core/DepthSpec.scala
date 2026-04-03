package com.tjclp.scalagent.core

import scala.compiletime.testing.{typeChecks, typeCheckErrors}

class DepthSpec extends munit.FunSuite:

  // --- DepthLTE: valid comparisons ---

  test("DepthLTE: Z <= Z"):
    summon[DepthLTE[Z, Z]] // compiles

  test("DepthLTE: Z <= S[Z]"):
    summon[DepthLTE[Z, S[Z]]] // compiles

  test("DepthLTE: Z <= S[S[Z]]"):
    summon[DepthLTE[Z, S[S[Z]]]] // compiles

  test("DepthLTE: S[Z] <= S[Z]"):
    summon[DepthLTE[S[Z], S[Z]]] // compiles

  test("DepthLTE: S[Z] <= S[S[Z]]"):
    summon[DepthLTE[S[Z], S[S[Z]]]] // compiles

  test("DepthLTE: S[S[Z]] <= S[S[S[Z]]]"):
    summon[DepthLTE[S[S[Z]], S[S[S[Z]]]]] // compiles

  // --- DepthLTE: invalid comparisons (should NOT compile) ---

  test("DepthLTE: S[Z] > Z does not compile"):
    assert(!typeChecks("summon[DepthLTE[S[Z], Z]]"))

  test("DepthLTE: S[S[Z]] > S[Z] does not compile"):
    assert(!typeChecks("summon[DepthLTE[S[S[Z]], S[Z]]]"))

  test("DepthLTE: S[S[S[Z]]] > S[S[Z]] does not compile"):
    assert(!typeChecks("summon[DepthLTE[S[S[S[Z]]], S[S[Z]]]]"))

  // --- DepthValue: type-level to runtime ---

  test("DepthValue[Z] == 0"):
    assertEquals(summon[DepthValue[Z]].value, 0)

  test("DepthValue[Depth1] == 1"):
    assertEquals(summon[DepthValue[Depth1]].value, 1)

  test("DepthValue[Depth2] == 2"):
    assertEquals(summon[DepthValue[Depth2]].value, 2)

  test("DepthValue[Depth3] == 3"):
    assertEquals(summon[DepthValue[Depth3]].value, 3)

  // --- Type aliases ---

  test("Depth aliases match Peano encoding"):
    summon[Depth0 =:= Z]
    summon[Depth1 =:= S[Z]]
    summon[Depth2 =:= S[S[Z]]]
    summon[Depth3 =:= S[S[S[Z]]]]
