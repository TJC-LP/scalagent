package com.tjclp.scalagent.config

import zio.json.*

import munit.FunSuite

class EffortSpec extends FunSuite:

  test("XHigh uses SDK wire value and round-trips"):
    assertEquals(Effort.XHigh.toRaw, "xhigh")
    assertEquals(Effort.fromString(Effort.XHigh.toRaw), Effort.XHigh)
    assertEquals(Effort.XHigh.toJson, "\"xhigh\"")
    assertEquals("\"xhigh\"".fromJson[Effort], Right(Effort.XHigh))
