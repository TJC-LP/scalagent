package com.tjclp.scalagent.core

import zio.*
import zio.json.ast.Json
import com.tjclp.scalagent.hooks.{HookInput, HookOutput}
import com.tjclp.scalagent.tools.ToolName
import com.tjclp.scalagent.types.{SessionId, ToolUseId}

class DirectoryScopeSpec extends munit.FunSuite:

  private val scope = DirectoryScope(
    cwd = "/tmp/sandbox/reports",
    additionalDirectories = List("/tmp/sandbox/shared")
  )

  private def preToolUse(tool: ToolName, input: Json): HookInput.PreToolUse =
    HookInput.PreToolUse(
      sessionId = SessionId("test"),
      cwd = "/tmp/sandbox/reports",
      transcriptPath = "/tmp/transcript.jsonl",
      toolName = tool,
      toolInput = input,
      toolUseId = ToolUseId("tu-1")
    )

  private def runHook(input: HookInput): HookOutput =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(scope.toHook(input)).getOrThrow()
    }

  private def assertDenied(output: HookOutput): Unit =
    output match
      case HookOutput.ToolPermission(allow, _, _) => assert(!allow)
      case other => fail(s"Expected ToolPermission(deny), got $other")

  private def assertAllowed(output: HookOutput): Unit =
    output match
      case _: HookOutput.Continue => () // ok
      case HookOutput.ToolPermission(allow, _, _) => assert(allow)
      case other => fail(s"Expected Continue or ToolPermission(allow), got $other")

  // --- Path extraction ---

  test("extractScopeTargets reads file_path for Read"):
    val json = Json.Obj("file_path" -> Json.Str("/tmp/sandbox/reports/q3.txt"))
    assertEquals(
      DirectoryScope.extractScopeTargets(ToolName.Read, json, scope.cwd),
      List("/tmp/sandbox/reports/q3.txt")
    )

  test("extractScopeTargets reads file_path for Write"):
    val json = Json.Obj("file_path" -> Json.Str("/tmp/out.txt"), "content" -> Json.Str("hi"))
    assertEquals(DirectoryScope.extractScopeTargets(ToolName.Write, json, scope.cwd), List("/tmp/out.txt"))

  test("extractScopeTargets reads path for Grep"):
    val json = Json.Obj("pattern" -> Json.Str("revenue"), "path" -> Json.Str("/tmp/sandbox/reports"))
    assertEquals(
      DirectoryScope.extractScopeTargets(ToolName.Grep, json, scope.cwd),
      List("/tmp/sandbox/reports")
    )

  test("extractScopeTargets returns Nil for Grep without path"):
    val json = Json.Obj("pattern" -> Json.Str("revenue"))
    assertEquals(DirectoryScope.extractScopeTargets(ToolName.Grep, json, scope.cwd), Nil)

  test("extractScopeTargets reads notebook_path for NotebookEdit"):
    val json = Json.Obj(
      "notebook_path" -> Json.Str("/tmp/sandbox/reports/analysis.ipynb"),
      "new_source" -> Json.Str("print(1)")
    )
    assertEquals(
      DirectoryScope.extractScopeTargets(ToolName.NotebookEdit, json, scope.cwd),
      List("/tmp/sandbox/reports/analysis.ipynb")
    )

  test("extractScopeTargets uses pattern for Glob without path"):
    val json = Json.Obj("pattern" -> Json.Str("../**/*"))
    assertEquals(DirectoryScope.extractScopeTargets(ToolName.Glob, json, scope.cwd), List("../**/*"))

  test("extractScopeTargets checks both path and pattern target for Glob"):
    val json = Json.Obj(
      "path" -> Json.Str("/tmp/sandbox/reports"),
      "pattern" -> Json.Str("../shared/**/*.txt")
    )
    assertEquals(
      DirectoryScope.extractScopeTargets(ToolName.Glob, json, scope.cwd),
      List("/tmp/sandbox/reports", "/tmp/sandbox/shared/**/*.txt")
    )

  test("extractScopeTargets returns Nil for Bash"):
    val json = Json.Obj("command" -> Json.Str("ls"))
    assertEquals(DirectoryScope.extractScopeTargets(ToolName.Bash, json, scope.cwd), Nil)

  // --- Hook behavior ---

  test("hook allows Read within cwd"):
    assertAllowed(runHook(preToolUse(ToolName.Read, Json.Obj("file_path" -> Json.Str("/tmp/sandbox/reports/q3.txt")))))

  test("hook allows Read within additional directory"):
    assertAllowed(runHook(preToolUse(ToolName.Read, Json.Obj("file_path" -> Json.Str("/tmp/sandbox/shared/dir.txt")))))

  test("hook denies Read outside scope"):
    assertDenied(runHook(preToolUse(ToolName.Read, Json.Obj("file_path" -> Json.Str("/etc/passwd")))))

  test("hook denies Read with path traversal"):
    assertDenied(runHook(preToolUse(ToolName.Read, Json.Obj("file_path" -> Json.Str("/tmp/sandbox/reports/../../etc/passwd")))))

  test("hook denies Bash (commands cannot be reliably path-checked)"):
    assertDenied(runHook(preToolUse(ToolName.Bash, Json.Obj("command" -> Json.Str("ls")))))

  test("hook allows Grep without path (defaults to cwd)"):
    assertAllowed(runHook(preToolUse(ToolName.Grep, Json.Obj("pattern" -> Json.Str("revenue")))))

  test("hook denies Grep with out-of-scope path"):
    assertDenied(runHook(preToolUse(ToolName.Grep, Json.Obj(
      "pattern" -> Json.Str("secret"),
      "path" -> Json.Str("/etc")
    ))))

  test("hook allows Glob without path when pattern stays within cwd"):
    assertAllowed(runHook(preToolUse(ToolName.Glob, Json.Obj("pattern" -> Json.Str("**/*.scala")))))

  test("hook denies Glob without path traversal"):
    assertDenied(runHook(preToolUse(ToolName.Glob, Json.Obj("pattern" -> Json.Str("../**/*")))))

  test("hook denies Glob when pattern escapes scoped path"):
    assertDenied(runHook(preToolUse(ToolName.Glob, Json.Obj(
      "path" -> Json.Str("/tmp/sandbox/reports"),
      "pattern" -> Json.Str("../classified/**/*")
    ))))

  test("hook denies NotebookEdit outside scope"):
    assertDenied(runHook(preToolUse(ToolName.NotebookEdit, Json.Obj(
      "notebook_path" -> Json.Str("/etc/analysis.ipynb"),
      "new_source" -> Json.Str("print(1)")
    ))))

  test("hook allows non-file non-Bash tools"):
    assertAllowed(runHook(preToolUse(ToolName.WebFetch, Json.Obj("url" -> Json.Str("https://example.com")))))

  test("hook allows relative path within cwd"):
    assertAllowed(runHook(preToolUse(ToolName.Read, Json.Obj("file_path" -> Json.Str("q3.txt")))))

  test("hook denies sibling directory"):
    assertDenied(runHook(preToolUse(ToolName.Read, Json.Obj("file_path" -> Json.Str("/tmp/sandbox/classified.txt")))))

  // --- ToolPermission.toRaw ---

  test("ToolPermission deny produces correct JS structure"):
    val output = HookOutput.ToolPermission(allow = false, reason = Some("outside scope"))
    val raw = output.toRaw.asInstanceOf[scala.scalajs.js.Dynamic]
    val specific = raw.hookSpecificOutput
    assertEquals(specific.hookEventName.asInstanceOf[String], "PreToolUse")
    assertEquals(specific.permissionDecision.asInstanceOf[String], "deny")
    assertEquals(specific.permissionDecisionReason.asInstanceOf[String], "outside scope")

  test("ToolPermission allow produces correct JS structure"):
    val output = HookOutput.ToolPermission(allow = true)
    val raw = output.toRaw.asInstanceOf[scala.scalajs.js.Dynamic]
    val specific = raw.hookSpecificOutput
    assertEquals(specific.hookEventName.asInstanceOf[String], "PreToolUse")
    assertEquals(specific.permissionDecision.asInstanceOf[String], "allow")

  // --- isWithinScope ---

  test("isWithinScope allows path in cwd"):
    assert(scope.isWithinScope("/tmp/sandbox/reports/file.txt"))

  test("isWithinScope allows path in additional directory"):
    assert(scope.isWithinScope("/tmp/sandbox/shared/file.txt"))

  test("isWithinScope rejects path outside all roots"):
    assert(!scope.isWithinScope("/etc/passwd"))

  test("isWithinScope rejects traversal escape"):
    assert(!scope.isWithinScope("/tmp/sandbox/reports/../classified.txt"))

  test("isWithinScope resolves relative paths against cwd"):
    assert(scope.isWithinScope("subdir/file.txt"))

  test("isWithinScope rejects relative traversal escape"):
    assert(!scope.isWithinScope("../../etc/passwd"))
