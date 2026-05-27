package com.tjclp.scalagent.config

import munit.FunSuite
import zio.json._

class ModelSpec extends FunSuite:

  // ============================================
  // Enum Values
  // ============================================

  test("Model enum has correct IDs for Claude 4.5 family"):
    assertEquals(Model.Sonnet4_5.id, "claude-sonnet-4-5-20250929")
    assertEquals(Model.Haiku4_5.id, "claude-haiku-4-5-20251001")
    assertEquals(Model.Opus4_5.id, "claude-opus-4-5-20251101")

  test("Model enum has correct IDs for Claude 4.x family"):
    assertEquals(Model.Opus4_1.id, "claude-opus-4-1-20250805")
    assertEquals(Model.Opus4.id, "claude-opus-4-20250514")
    assertEquals(Model.Sonnet4.id, "claude-sonnet-4-20250514")

  test("Model enum has correct IDs for Claude 3.7 family"):
    assertEquals(Model.Sonnet3_7.id, "claude-3-7-sonnet-20250219")

  test("Model enum has correct IDs for Claude 3.5 family"):
    assertEquals(Model.Haiku3_5.id, "claude-3-5-haiku-20241022")

  test("Model enum has correct IDs for Claude 3 family"):
    assertEquals(Model.Opus3.id, "claude-3-opus-latest")
    assertEquals(Model.Sonnet3.id, "claude-3-sonnet-20240229")
    assertEquals(Model.Haiku3.id, "claude-3-haiku-20240307")

  test("Custom model preserves ID"):
    val custom = Model.Custom("my-custom-model-123")
    assertEquals(custom.id, "my-custom-model-123")

  // ============================================
  // fromId Parsing
  // ============================================

  test("fromId parses known Claude 4.5 models"):
    assertEquals(Model.fromId("claude-sonnet-4-5-20250929"), Model.Sonnet4_5)
    assertEquals(Model.fromId("claude-haiku-4-5-20251001"), Model.Haiku4_5)
    assertEquals(Model.fromId("claude-opus-4-5-20251101"), Model.Opus4_5)

  test("fromId parses known Claude 4.x models"):
    assertEquals(Model.fromId("claude-opus-4-1-20250805"), Model.Opus4_1)
    assertEquals(Model.fromId("claude-opus-4-20250514"), Model.Opus4)
    assertEquals(Model.fromId("claude-sonnet-4-20250514"), Model.Sonnet4)

  test("fromId parses known Claude 3.7 models"):
    assertEquals(Model.fromId("claude-3-7-sonnet-20250219"), Model.Sonnet3_7)

  test("fromId parses known Claude 3.5 models"):
    assertEquals(Model.fromId("claude-3-5-haiku-20241022"), Model.Haiku3_5)

  test("fromId parses known Claude 3 models"):
    assertEquals(Model.fromId("claude-3-opus-latest"), Model.Opus3)
    assertEquals(Model.fromId("claude-3-sonnet-20240229"), Model.Sonnet3)
    assertEquals(Model.fromId("claude-3-haiku-20240307"), Model.Haiku3)

  test("fromId returns Custom for unknown model IDs"):
    val result = Model.fromId("unknown-model-xyz")
    result match
      case Model.Custom(id) => assertEquals(id, "unknown-model-xyz")
      case other            => fail(s"Expected Custom, got $other")

  test("fromId round-trips with all known models"):
    val knownModels = List(
      // Claude 4.5 Family
      Model.Sonnet4_5,
      Model.Haiku4_5,
      Model.Opus4_5,
      // Claude 4.x Family
      Model.Opus4_1,
      Model.Opus4,
      Model.Sonnet4,
      // Claude 3.7 Family
      Model.Sonnet3_7,
      // Claude 3.5 Family
      Model.Haiku3_5,
      // Claude 3 Family
      Model.Opus3,
      Model.Sonnet3,
      Model.Haiku3
    )
    knownModels.foreach { model =>
      assertEquals(Model.fromId(model.id), model)
    }

  // ============================================
  // Convenience Aliases
  // ============================================

  test("convenience aliases point to current generation models"):
    assertEquals(Model.opus, Model.Opus4_7)
    assertEquals(Model.sonnet, Model.Sonnet4_6)
    assertEquals(Model.haiku, Model.Haiku4_5)

  test("Opus 4.7 maps to claude-opus-4-7 (SDK 0.2.111+)"):
    assertEquals(Model.Opus4_7.id, "claude-opus-4-7")
    assertEquals(Model.fromId("claude-opus-4-7"), Model.Opus4_7)

  test("default model is Sonnet4_6"):
    assertEquals(Model.default, Model.Sonnet4_6)

  // ============================================
  // JSON Serialization
  // ============================================

  test("Model encodes to JSON string"):
    assertEquals(Model.Sonnet4_5.toJson, """"claude-sonnet-4-5-20250929"""")
    assertEquals(Model.Opus4_5.toJson, """"claude-opus-4-5-20251101"""")
    assertEquals(Model.Haiku4_5.toJson, """"claude-haiku-4-5-20251001"""")

  test("Model decodes from JSON string"):
    assertEquals(""""claude-sonnet-4-5-20250929"""".fromJson[Model], Right(Model.Sonnet4_5))
    assertEquals(""""claude-opus-4-5-20251101"""".fromJson[Model], Right(Model.Opus4_5))
    assertEquals(""""claude-haiku-4-5-20251001"""".fromJson[Model], Right(Model.Haiku4_5))

  test("Custom model encodes to JSON"):
    val custom = Model.Custom("my-model")
    assertEquals(custom.toJson, """"my-model"""")

  test("Unknown model ID decodes as Custom"):
    val result = """"future-model-2026"""".fromJson[Model]
    result match
      case Right(Model.Custom(id)) => assertEquals(id, "future-model-2026")
      case other                   => fail(s"Expected Right(Custom), got $other")

  test("JSON round-trip preserves model"):
    val models = List(
      Model.Sonnet4_5,
      Model.Haiku4_5,
      Model.Opus4_5,
      Model.Sonnet4,
      Model.Haiku3_5,
      Model.Custom("test-model")
    )
    models.foreach { model =>
      val json = model.toJson
      val parsed = json.fromJson[Model]
      assertEquals(parsed, Right(model))
    }
