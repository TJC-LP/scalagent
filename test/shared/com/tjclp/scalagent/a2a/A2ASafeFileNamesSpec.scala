package com.tjclp.scalagent.a2a

import munit.FunSuite

class A2ASafeFileNamesSpec extends FunSuite:
  private val hash64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

  private def safe(value: String, fallback: String = "task", maxLength: Int = 96): String =
    A2ASafeFileNames.safeSegment(value, fallback, maxLength)(_ => hash64)

  test("safe segments preserve already safe opaque ids"):
    assertEquals(safe("task-1"), "task-1")
    assertEquals(safe("tenant.v1_2-3"), "tenant.v1_2-3")

  test("unsafe segments sanitize stems and append hash suffixes"):
    val unsafe = safe("../task/with spaces")
    val spaced = safe(" ../task/with spaces ")

    assert(unsafe.endsWith(s"-$hash64"))
    assert(spaced.endsWith(s"-$hash64"))
    assert(!unsafe.contains("/"))
    assert(!unsafe.contains(".."))
    assert(!spaced.contains("/"))
    assert(!spaced.contains(".."))
    assert(unsafe != "task_with_spaces")
    assert(spaced != unsafe)

  test("unsafe segments hash the exact raw opaque id"):
    var seen = List.empty[String]

    A2ASafeFileNames.safeSegment(" task-1 ", fallback = "task", maxLength = 96) { raw =>
      seen = seen :+ raw
      hash64
    }

    assertEquals(seen, List(" task-1 "))

  test("unsafe fallback is sanitized before use"):
    assertEquals(safe(".", fallback = "../bad"), s"___bad-$hash64")
    assertEquals(safe("", fallback = "..."), s"item-$hash64")
end A2ASafeFileNamesSpec
