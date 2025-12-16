package com.tjclp.claude.agent.config

import munit.FunSuite
import scala.scalajs.js
import zio.json._

class SystemPromptConfigSpec extends FunSuite:

  // ============================================
  // Custom Variant
  // ============================================

  test("Custom preserves prompt string"):
    val prompt = "You are a helpful assistant."
    val config = SystemPromptConfig.Custom(prompt)
    config match
      case SystemPromptConfig.Custom(p) => assertEquals(p, prompt)
      case other                        => fail(s"Expected Custom, got $other")

  test("Custom toRaw returns the prompt string"):
    val prompt = "Custom system prompt"
    val config = SystemPromptConfig.Custom(prompt)
    val raw = config.toRaw
    assertEquals(raw.asInstanceOf[String], prompt)

  // ============================================
  // Preset Variant
  // ============================================

  test("Preset stores preset name"):
    val config = SystemPromptConfig.Preset("claude_code")
    config match
      case SystemPromptConfig.Preset(preset, append) =>
        assertEquals(preset, "claude_code")
        assertEquals(append, None)
      case other => fail(s"Expected Preset, got $other")

  test("Preset with append stores both"):
    val config = SystemPromptConfig.Preset("claude_code", Some("Always be concise."))
    config match
      case SystemPromptConfig.Preset(preset, append) =>
        assertEquals(preset, "claude_code")
        assertEquals(append, Some("Always be concise."))
      case other => fail(s"Expected Preset, got $other")

  test("Preset toRaw creates correct JS object"):
    val config = SystemPromptConfig.Preset("claude_code")
    val raw = config.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.`type`.asInstanceOf[String], "preset")
    assertEquals(raw.preset.asInstanceOf[String], "claude_code")

  test("Preset with append toRaw includes append field"):
    val config = SystemPromptConfig.Preset("claude_code", Some("Be helpful."))
    val raw = config.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.`type`.asInstanceOf[String], "preset")
    assertEquals(raw.preset.asInstanceOf[String], "claude_code")
    assertEquals(raw.append.asInstanceOf[String], "Be helpful.")

  // ============================================
  // Convenience Constructors
  // ============================================

  test("claudeCode creates Preset with claude_code"):
    SystemPromptConfig.claudeCode match
      case SystemPromptConfig.Preset(preset, None) =>
        assertEquals(preset, "claude_code")
      case other => fail(s"Expected Preset(claude_code, None), got $other")

  test("claudeCodeWith creates Preset with append"):
    val config = SystemPromptConfig.claudeCodeWith("Be concise.")
    config match
      case SystemPromptConfig.Preset(preset, Some(append)) =>
        assertEquals(preset, "claude_code")
        assertEquals(append, "Be concise.")
      case other => fail(s"Expected Preset with append, got $other")

  // ============================================
  // JSON Serialization
  // ============================================

  test("Custom encodes to JSON"):
    val config = SystemPromptConfig.Custom("Test prompt")
    val json = config.toJson
    assert(json.contains("Custom"))
    assert(json.contains("Test prompt"))

  test("Preset encodes to JSON"):
    val config = SystemPromptConfig.Preset("claude_code", Some("Extra"))
    val json = config.toJson
    assert(json.contains("Preset"))
    assert(json.contains("claude_code"))
    assert(json.contains("Extra"))

  test("JSON round-trip for Custom"):
    val original = SystemPromptConfig.Custom("My custom prompt")
    val json = original.toJson
    val parsed = json.fromJson[SystemPromptConfig]
    assertEquals(parsed, Right(original))

  test("JSON round-trip for Preset without append"):
    val original = SystemPromptConfig.Preset("claude_code")
    val json = original.toJson
    val parsed = json.fromJson[SystemPromptConfig]
    assertEquals(parsed, Right(original))

  test("JSON round-trip for Preset with append"):
    val original = SystemPromptConfig.Preset("claude_code", Some("Be helpful"))
    val json = original.toJson
    val parsed = json.fromJson[SystemPromptConfig]
    assertEquals(parsed, Right(original))
