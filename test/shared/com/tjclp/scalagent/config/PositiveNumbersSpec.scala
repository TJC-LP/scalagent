package com.tjclp.scalagent.config

import munit.FunSuite

class PositiveNumbersSpec extends FunSuite:

  test("PositiveInt.literal builds a positive value"):
    val value = PositiveInt.literal(5)
    assertEquals(value.value, 5)

  test("PositiveDouble.literal builds a positive value"):
    val value = PositiveDouble.literal(1.25)
    assertEquals(value.value, 1.25)
