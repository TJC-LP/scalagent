package com.tjclp.scalagent.hooks

import munit.FunSuite
import zio.json.*

class HookInputEnumSpec extends FunSuite:
  private def assertStringEnumRoundTrip[A: JsonEncoder: JsonDecoder](value: A, raw: String): Unit =
    val json = value.toJson
    assertEquals(json, s"\"$raw\"")
    assertEquals(json.fromJson[A], Right(value))

  test("PermissionBehavior JSON codec round-trips known values"):
    assertStringEnumRoundTrip(PermissionBehavior.Allow, "allow")
    assertStringEnumRoundTrip(PermissionBehavior.Deny, "deny")
    assertStringEnumRoundTrip(PermissionBehavior.Ask, "ask")

  test("PermissionBehavior JSON decoder fails on unknown values"):
    "\"maybe\"".fromJson[PermissionBehavior] match
      case Left(error) =>
        assert(error.contains("Unknown permission behavior: maybe"))
      case Right(value) =>
        fail(s"Expected decode failure, got $value")

  test("HookInput string enums JSON codec round-trip known and custom values"):
    assertStringEnumRoundTrip(ExitReason.Clear, "clear")
    assertStringEnumRoundTrip(ExitReason.Custom("future_exit_reason"), "future_exit_reason")
    assertStringEnumRoundTrip(SessionStartSource.Startup, "startup")
    assertStringEnumRoundTrip(SessionStartSource.Custom("future_start_source"), "future_start_source")
    assertStringEnumRoundTrip(SetupTrigger.Init, "init")
    assertStringEnumRoundTrip(SetupTrigger.Custom("future_setup_trigger"), "future_setup_trigger")
    assertStringEnumRoundTrip(CompactTrigger.Manual, "manual")
    assertStringEnumRoundTrip(CompactTrigger.Custom("future_compact_trigger"), "future_compact_trigger")
    assertStringEnumRoundTrip(ElicitationMode.Form, "form")
    assertStringEnumRoundTrip(ElicitationMode.Custom("future_elicitation_mode"), "future_elicitation_mode")
    assertStringEnumRoundTrip(ElicitationAction.Accept, "accept")
    assertStringEnumRoundTrip(ElicitationAction.Custom("future_elicitation_action"), "future_elicitation_action")
    assertStringEnumRoundTrip(ConfigChangeSource.Skills, "skills")
    assertStringEnumRoundTrip(ConfigChangeSource.Custom("future_config_source"), "future_config_source")
    assertStringEnumRoundTrip(InstructionsLoadReason.Compact, "compact")
    assertStringEnumRoundTrip(InstructionsLoadReason.Custom("future_load_reason"), "future_load_reason")
