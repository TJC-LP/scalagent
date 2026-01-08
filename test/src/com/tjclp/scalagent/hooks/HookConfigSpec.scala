package com.tjclp.scalagent.hooks

import munit.FunSuite
import scala.scalajs.js
import zio.*
import zio.json.*

class HookConfigSpec extends FunSuite:

  // ============================================
  // Shell Hook Construction
  // ============================================

  test("Shell hook stores matcher and command"):
    val hook = HookConfig.shell("Bash", "./validate.sh")
    hook match
      case HookConfig.Shell(matcher, command, timeout, once) =>
        assertEquals(matcher, "Bash")
        assertEquals(command, "./validate.sh")
        assertEquals(timeout, None)
        assertEquals(once, false)
      case _ => fail("Expected Shell hook")

  test("Shell hook can have timeout"):
    val hook = HookConfig.shell("Bash", "./script.sh", timeout = Some(5000))
    hook match
      case HookConfig.Shell(_, _, timeout, _) =>
        assertEquals(timeout, Some(5000))
      case _ => fail("Expected Shell hook")

  test("Shell hook can be one-time"):
    val hook = HookConfig.shell("Edit", "./check.sh", once = true)
    hook match
      case HookConfig.Shell(_, _, _, once) =>
        assertEquals(once, true)
      case _ => fail("Expected Shell hook")

  test("shellOnce creates one-time shell hook"):
    val hook = HookConfig.shellOnce("Write", "./validate.sh")
    hook match
      case HookConfig.Shell(matcher, command, _, once) =>
        assertEquals(matcher, "Write")
        assertEquals(command, "./validate.sh")
        assertEquals(once, true)
      case _ => fail("Expected Shell hook")

  // ============================================
  // Callback Hook Construction
  // ============================================

  test("Callback hook stores callback function"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback(cb)
    hook match
      case HookConfig.Callback(callback, matcher, timeout, once) =>
        assertEquals(matcher, None)
        assertEquals(timeout, None)
        assertEquals(once, false)
      case _ => fail("Expected Callback hook")

  test("Callback hook can have matcher"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback("Bash|Edit", cb)
    hook match
      case HookConfig.Callback(_, matcher, _, _) =>
        assertEquals(matcher, Some("Bash|Edit"))
      case _ => fail("Expected Callback hook")

  test("Callback hook can have timeout and once"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback(".*", cb, timeout = Some(3000), once = true)
    hook match
      case HookConfig.Callback(_, matcher, timeout, once) =>
        assertEquals(matcher, Some(".*"))
        assertEquals(timeout, Some(3000))
        assertEquals(once, true)
      case _ => fail("Expected Callback hook")

  test("callbackOnce creates one-time callback hook"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callbackOnce(cb)
    hook match
      case HookConfig.Callback(_, _, _, once) =>
        assertEquals(once, true)
      case _ => fail("Expected Callback hook")

  test("callbackOnce with matcher creates one-time callback hook"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callbackOnce("Read", cb)
    hook match
      case HookConfig.Callback(_, matcher, _, once) =>
        assertEquals(matcher, Some("Read"))
        assertEquals(once, true)
      case _ => fail("Expected Callback hook")

  // ============================================
  // Extension Methods
  // ============================================

  test("isShell returns true for Shell hook"):
    val hook = HookConfig.shell("Bash", "./script.sh")
    assert(hook.isShell)
    assert(!hook.isCallback)

  test("isCallback returns true for Callback hook"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback(cb)
    assert(hook.isCallback)
    assert(!hook.isShell)

  test("matcherPattern returns pattern for Shell hook"):
    val hook = HookConfig.shell("Edit|Write", "./validate.sh")
    assertEquals(hook.matcherPattern, Some("Edit|Write"))

  test("matcherPattern returns pattern for Callback hook with matcher"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback("Bash", cb)
    assertEquals(hook.matcherPattern, Some("Bash"))

  test("matcherPattern returns None for Callback hook without matcher"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback(cb)
    assertEquals(hook.matcherPattern, None)

  test("isOnce returns true for one-time hooks"):
    val shellOnce = HookConfig.shellOnce("Bash", "./script.sh")
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val callbackOnce = HookConfig.callbackOnce(cb)
    assert(shellOnce.isOnce)
    assert(callbackOnce.isOnce)

  test("isOnce returns false for regular hooks"):
    val shell = HookConfig.shell("Bash", "./script.sh")
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val callback = HookConfig.callback(cb)
    assert(!shell.isOnce)
    assert(!callback.isOnce)

  // ============================================
  // JSON Serialization - Shell Hooks
  // ============================================

  test("Shell hook encodes to JSON"):
    val hook = HookConfig.shell("Bash", "./validate.sh")
    val json = hook.toJson
    assert(json.contains("\"type\":\"shell\""))
    assert(json.contains("\"matcher\":\"Bash\""))
    assert(json.contains("\"command\":\"./validate.sh\""))

  test("Shell hook with timeout encodes to JSON"):
    val hook = HookConfig.shell("Edit", "./check.sh", timeout = Some(5000))
    val json = hook.toJson
    assert(json.contains("\"timeout\":5000"))

  test("Shell hook with once encodes to JSON"):
    val hook = HookConfig.shell("Write", "./audit.sh", once = true)
    val json = hook.toJson
    assert(json.contains("\"once\":true"))

  test("Shell hook JSON round-trip"):
    val original = HookConfig.shell("Bash|Edit", "./validate.sh", timeout = Some(3000), once = true)
    val json = original.toJson
    val parsed = json.fromJson[HookConfig]
    assertEquals(parsed, Right(original))

  test("Shell hook decodes from JSON"):
    val json = """{"type":"shell","matcher":"Glob","command":"./check.sh"}"""
    val parsed = json.fromJson[HookConfig]
    parsed match
      case Right(HookConfig.Shell(matcher, command, timeout, once)) =>
        assertEquals(matcher, "Glob")
        assertEquals(command, "./check.sh")
        assertEquals(timeout, None)
        assertEquals(once, false)
      case other => fail(s"Expected Shell hook, got: $other")

  test("Shell hook decodes legacy format without type field"):
    val json = """{"matcher":"Read","command":"./script.sh"}"""
    val parsed = json.fromJson[HookConfig]
    parsed match
      case Right(HookConfig.Shell(matcher, command, _, _)) =>
        assertEquals(matcher, "Read")
        assertEquals(command, "./script.sh")
      case other => fail(s"Expected Shell hook, got: $other")

  // ============================================
  // JSON Serialization - Callback Hooks
  // ============================================

  test("Callback hook encodes to JSON with marker"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback("Bash", cb)
    val json = hook.toJson
    assert(json.contains("\"type\":\"callback\""))
    assert(json.contains("\"matcher\":\"Bash\""))

  test("Callback hook with options encodes to JSON"):
    val cb: HookCallback = _ => ZIO.succeed(HookOutput.continue)
    val hook = HookConfig.callback("Edit", cb, timeout = Some(2000), once = true)
    val json = hook.toJson
    assert(json.contains("\"timeout\":2000"))
    assert(json.contains("\"once\":true"))

  test("Callback hook cannot be decoded from JSON"):
    val json = """{"type":"callback","matcher":"Bash"}"""
    val parsed = json.fromJson[HookConfig]
    assert(parsed.isLeft)
    assert(parsed.left.exists(_.contains("cannot be deserialized")))

  // ============================================
  // Error Cases
  // ============================================

  test("JSON decode fails for missing matcher in shell hook"):
    val json = """{"type":"shell","command":"./script.sh"}"""
    val parsed = json.fromJson[HookConfig]
    assert(parsed.isLeft)

  test("JSON decode fails for missing command in shell hook"):
    val json = """{"type":"shell","matcher":"Bash"}"""
    val parsed = json.fromJson[HookConfig]
    assert(parsed.isLeft)

  test("JSON decode fails for unknown type"):
    val json = """{"type":"unknown","matcher":"Bash"}"""
    val parsed = json.fromJson[HookConfig]
    assert(parsed.isLeft)

  test("JSON decode fails for non-object"):
    val json = """"just a string""""
    val parsed = json.fromJson[HookConfig]
    assert(parsed.isLeft)
