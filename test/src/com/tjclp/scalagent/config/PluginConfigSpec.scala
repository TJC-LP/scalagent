package com.tjclp.scalagent.config

import munit.FunSuite
import scala.scalajs.js
import zio.json._

class PluginConfigSpec extends FunSuite:

  // ============================================
  // Local Variant
  // ============================================

  test("Local stores path"):
    val config = PluginConfig.Local("./my-plugin")
    config match
      case PluginConfig.Local(path) => assertEquals(path, "./my-plugin")
      case other                    => fail(s"Expected Local, got $other")

  test("Local toRaw creates correct JS object"):
    val config = PluginConfig.Local("/path/to/plugin")
    val raw = config.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.`type`.asInstanceOf[String], "local")
    assertEquals(raw.path.asInstanceOf[String], "/path/to/plugin")

  test("local convenience constructor creates Local"):
    val config = PluginConfig.local("./plugin")
    config match
      case PluginConfig.Local(path) => assertEquals(path, "./plugin")
      case other                    => fail(s"Expected Local, got $other")

  test("locals creates multiple Local configs"):
    val configs = PluginConfig.locals("./plugin-a", "./plugin-b", "./plugin-c")
    assertEquals(configs.size, 3)
    configs.zipWithIndex.foreach { case (config, i) =>
      config match
        case PluginConfig.Local(path) =>
          val expected = s"./plugin-${('a' + i).toChar}"
          assertEquals(path, expected)
        case other => fail(s"Expected Local, got $other")
    }

  // ============================================
  // Custom Variant
  // ============================================

  test("Custom stores raw JS object"):
    val rawObj = js.Dynamic.literal(`type` = "remote", url = "https://example.com")
    val config = PluginConfig.Custom(rawObj.asInstanceOf[js.Object])
    config match
      case PluginConfig.Custom(raw) =>
        assertEquals(raw.asInstanceOf[js.Dynamic].`type`.asInstanceOf[String], "remote")
      case other => fail(s"Expected Custom, got $other")

  test("Custom toRaw returns the original object"):
    val rawObj = js.Dynamic.literal(`type` = "custom", value = 123)
    val config = PluginConfig.Custom(rawObj.asInstanceOf[js.Object])
    val result = config.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(result.`type`.asInstanceOf[String], "custom")
    assertEquals(result.value.asInstanceOf[Int], 123)

  // ============================================
  // Plugin Errors
  // ============================================

  test("PathNotFound error has correct message"):
    val error = PluginError.PathNotFound("/missing/path")
    assert(error.getMessage.contains("/missing/path"))
    assert(error.getMessage.contains("not found"))

  test("NotADirectory error has correct message"):
    val error = PluginError.NotADirectory("/some/file.txt")
    assert(error.getMessage.contains("/some/file.txt"))
    assert(error.getMessage.contains("not a directory"))

  test("MissingManifest error has correct message"):
    val error = PluginError.MissingManifest("/plugin", "/plugin/.claude-plugin/plugin.json")
    assert(error.getMessage.contains("/plugin"))
    assert(error.getMessage.contains("manifest"))

  // ============================================
  // JSON Serialization
  // ============================================

  test("Local encodes to JSON"):
    val config = PluginConfig.Local("./my-plugin")
    val json = config.toJson
    assert(json.contains("local"))
    assert(json.contains("./my-plugin"))

  test("Local decodes from JSON"):
    val json = """{"type":"local","path":"./test-plugin"}"""
    val result = json.fromJson[PluginConfig]
    result match
      case Right(PluginConfig.Local(path)) => assertEquals(path, "./test-plugin")
      case other                           => fail(s"Expected Right(Local), got $other")

  test("JSON round-trip for Local"):
    val original = PluginConfig.Local("/absolute/path/plugin")
    val json = original.toJson
    val parsed = json.fromJson[PluginConfig]
    assertEquals(parsed, Right(original))

  test("JSON decode fails for invalid type"):
    val json = """{"type":"unknown","path":"./plugin"}"""
    val result = json.fromJson[PluginConfig]
    assert(result.isLeft)

  test("JSON decode fails for missing path"):
    val json = """{"type":"local"}"""
    val result = json.fromJson[PluginConfig]
    assert(result.isLeft)
