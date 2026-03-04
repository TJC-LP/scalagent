package com.tjclp.scalagent.config

import munit.FunSuite
import scala.scalajs.js
import zio.json.*

class ThinkingConfigSpec extends FunSuite:

  test("Adaptive encodes and decodes with type-based JSON"):
    val json = ThinkingConfig.Adaptive.toJson
    assertEquals(json.fromJson[ThinkingConfig], Right(ThinkingConfig.Adaptive))

  test("Enabled with budget encodes and decodes with budgetTokens"):
    val original = ThinkingConfig.Enabled(Some(2048))
    val json = original.toJson
    assertEquals(json.fromJson[ThinkingConfig], Right(original))

  test("Enabled without budget round-trips without budgetTokens"):
    val original = ThinkingConfig.Enabled(None)
    val json = original.toJson
    assertEquals(json.fromJson[ThinkingConfig], Right(original))
    assert(!json.contains("budgetTokens"))

  test("Decoder accepts null budgetTokens as None"):
    val json = """{"type":"enabled","budgetTokens":null}"""
    assertEquals(json.fromJson[ThinkingConfig], Right(ThinkingConfig.Enabled(None)))

  test("toRaw omits budgetTokens when not set"):
    val raw = ThinkingConfig.Enabled(None).toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.budgetTokens))
