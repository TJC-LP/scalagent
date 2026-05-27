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

  test("Disabled encodes and decodes with type-based JSON"):
    val json = ThinkingConfig.Disabled.toJson
    assertEquals(json.fromJson[ThinkingConfig], Right(ThinkingConfig.Disabled))

  test("Disabled toRaw produces type=disabled"):
    val raw = ThinkingConfig.Disabled.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.`type`.asInstanceOf[String], "disabled")

  test("Adaptive toRaw produces type=adaptive"):
    val raw = ThinkingConfig.Adaptive.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.`type`.asInstanceOf[String], "adaptive")

  test("Enabled with budget toRaw sets budgetTokens"):
    val raw = ThinkingConfig.Enabled(Some(4096)).toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.`type`.asInstanceOf[String], "enabled")
    assertEquals(raw.budgetTokens.asInstanceOf[Int], 4096)

  test("Decoder rejects unknown type field"):
    val json = """{"type":"unknown_future_type"}"""
    assert(json.fromJson[ThinkingConfig].isLeft)
