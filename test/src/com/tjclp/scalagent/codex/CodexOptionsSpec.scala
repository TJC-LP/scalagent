package com.tjclp.scalagent.codex

class CodexOptionsSpec extends munit.FunSuite:

  test("SandboxMode raw values"):
    assertEquals(SandboxMode.ReadOnly.raw, "read-only")
    assertEquals(SandboxMode.WorkspaceWrite.raw, "workspace-write")
    assertEquals(SandboxMode.FullAccess.raw, "danger-full-access")

  test("ApprovalMode raw values"):
    assertEquals(ApprovalMode.Never.raw, "never")
    assertEquals(ApprovalMode.OnRequest.raw, "on-request")
    assertEquals(ApprovalMode.OnFailure.raw, "on-failure")
    assertEquals(ApprovalMode.Untrusted.raw, "untrusted")

  test("CodexClientOptions.default is empty"):
    val opts = CodexClientOptions.default
    assertEquals(opts.apiKey, None)
    assertEquals(opts.baseUrl, None)
    assertEquals(opts.config, Map.empty[String, String])

  test("CodexThreadOptions.default is empty"):
    val opts = CodexThreadOptions.default
    assertEquals(opts.model, None)
    assertEquals(opts.sandboxMode, None)
    assertEquals(opts.approvalPolicy, None)
    assertEquals(opts.additionalDirectories, Nil)
    assertEquals(opts.skipGitRepoCheck, false)

  test("CodexThreadOptions.toRaw sets sandbox mode"):
    val opts = CodexThreadOptions(sandboxMode = Some(SandboxMode.ReadOnly))
    val raw = opts.toRaw
    val dyn = raw.asInstanceOf[scala.scalajs.js.Dynamic]
    assertEquals(dyn.sandboxMode.asInstanceOf[String], "read-only")

  test("CodexThreadOptions.toRaw sets model"):
    val opts = CodexThreadOptions(model = Some("o3-mini"))
    val raw = opts.toRaw
    val dyn = raw.asInstanceOf[scala.scalajs.js.Dynamic]
    assertEquals(dyn.model.asInstanceOf[String], "o3-mini")

  test("CodexThreadOptions.toRaw sets approval policy"):
    val opts = CodexThreadOptions(approvalPolicy = Some(ApprovalMode.Never))
    val raw = opts.toRaw
    val dyn = raw.asInstanceOf[scala.scalajs.js.Dynamic]
    assertEquals(dyn.approvalPolicy.asInstanceOf[String], "never")
