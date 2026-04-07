package com.tjclp.scalagent.codex

import scala.scalajs.js
import zio.json.ast.Json

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

  test("ModelReasoningEffort raw values"):
    assertEquals(ModelReasoningEffort.Minimal.raw, "minimal")
    assertEquals(ModelReasoningEffort.XHigh.raw, "xhigh")

  test("WebSearchMode raw values"):
    assertEquals(WebSearchMode.Disabled.raw, "disabled")
    assertEquals(WebSearchMode.Cached.raw, "cached")
    assertEquals(WebSearchMode.Live.raw, "live")

  test("CodexClientOptions.default is empty"):
    val opts = CodexClientOptions.default
    assertEquals(opts.apiKey, None)
    assertEquals(opts.baseUrl, None)
    assertEquals(opts.config, Map.empty[String, CodexConfigValue])

  test("CodexClientOptions preserves structured config overrides"):
    val opts = CodexClientOptions(
      config = Map(
        "approval_policy" -> CodexConfigValue.str("never"),
        "retry_budget" -> 3,
        "tool_rules" -> CodexConfigValue.obj(
          "allow" -> CodexConfigValue.arr("git status", "git diff")
        ),
        "sandbox_workspace_write" -> CodexConfigValue.obj(
          "network_access" -> true
        )
      )
    )
    val raw = opts.toRaw
    val dyn = raw.asInstanceOf[js.Dynamic]
    val config = dyn.config.asInstanceOf[js.Dynamic]
    val toolRules = config.tool_rules.asInstanceOf[js.Dynamic]
    val allow = toolRules.allow.asInstanceOf[js.Array[String]]
    val sandboxWorkspaceWrite = config.sandbox_workspace_write.asInstanceOf[js.Dynamic]

    assertEquals(config.approval_policy.asInstanceOf[String], "never")
    assertEquals(config.retry_budget.asInstanceOf[Double], 3.0)
    assertEquals(allow.toSeq, Seq("git status", "git diff"))
    assertEquals(sandboxWorkspaceWrite.network_access.asInstanceOf[Boolean], true)

  test("CodexThreadOptions.default is empty"):
    val opts = CodexThreadOptions.default
    assertEquals(opts.model, None)
    assertEquals(opts.sandboxMode, None)
    assertEquals(opts.approvalPolicy, None)
    assertEquals(opts.additionalDirectories, Nil)
    assertEquals(opts.skipGitRepoCheck, false)
    assertEquals(opts.modelReasoningEffort, None)
    assertEquals(opts.webSearchMode, None)
    assertEquals(opts.webSearchEnabled, None)

  test("CodexThreadOptions.toRaw sets extended thread options"):
    val opts = CodexThreadOptions(
      model = Some("o3-mini"),
      sandboxMode = Some(SandboxMode.ReadOnly),
      approvalPolicy = Some(ApprovalMode.Never),
      additionalDirectories = List("../backend"),
      skipGitRepoCheck = true,
      modelReasoningEffort = Some(ModelReasoningEffort.High),
      networkAccessEnabled = Some(true),
      webSearchMode = Some(WebSearchMode.Cached),
      webSearchEnabled = Some(false)
    )
    val raw = opts.toRaw
    val dyn = raw.asInstanceOf[js.Dynamic]

    assertEquals(dyn.model.asInstanceOf[String], "o3-mini")
    assertEquals(dyn.sandboxMode.asInstanceOf[String], "read-only")
    assertEquals(dyn.approvalPolicy.asInstanceOf[String], "never")
    assertEquals(dyn.additionalDirectories.asInstanceOf[js.Array[String]].toSeq, Seq("../backend"))
    assertEquals(dyn.skipGitRepoCheck.asInstanceOf[Boolean], true)
    assertEquals(dyn.modelReasoningEffort.asInstanceOf[String], "high")
    assertEquals(dyn.networkAccessEnabled.asInstanceOf[Boolean], true)
    assertEquals(dyn.webSearchMode.asInstanceOf[String], "cached")
    assertEquals(dyn.webSearchEnabled.asInstanceOf[Boolean], false)

  test("CodexInputItem.toRaw matches upstream SDK shape"):
    val text = CodexInputItem.Text("Describe the image").toRaw.asInstanceOf[js.Dynamic]
    val image = CodexInputItem.LocalImage("./ui.png").toRaw.asInstanceOf[js.Dynamic]

    assertEquals(text.`type`.asInstanceOf[String], "text")
    assertEquals(text.text.asInstanceOf[String], "Describe the image")
    assertEquals(image.`type`.asInstanceOf[String], "local_image")
    assertEquals(image.path.asInstanceOf[String], "./ui.png")

  test("CodexTurnOptions.toRaw includes output schema"):
    val opts = CodexTurnOptions(outputSchema = Some(Json.Obj(
      "type" -> Json.Str("object"),
      "required" -> Json.Arr(Json.Str("summary"))
    )))
    val raw = opts.toRaw.get.asInstanceOf[js.Dynamic]
    val schema = raw.outputSchema.asInstanceOf[js.Dynamic]

    assertEquals(schema.`type`.asInstanceOf[String], "object")
    assertEquals(schema.required.asInstanceOf[js.Array[String]].toSeq, Seq("summary"))

  test("AbortController creates an AbortSignal"):
    val controller = AbortController.create()
    assert(controller.signal != null)
