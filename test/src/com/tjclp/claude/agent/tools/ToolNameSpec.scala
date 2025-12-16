package com.tjclp.claude.agent.tools

import munit.FunSuite
import zio.json._

class ToolNameSpec extends FunSuite:

  // ============================================
  // Built-in Tools
  // ============================================

  test("file operation tools have correct raw names"):
    assertEquals(ToolName.Read.raw, "Read")
    assertEquals(ToolName.Write.raw, "Write")
    assertEquals(ToolName.Edit.raw, "Edit")
    assertEquals(ToolName.Glob.raw, "Glob")
    assertEquals(ToolName.Grep.raw, "Grep")
    assertEquals(ToolName.NotebookEdit.raw, "NotebookEdit")

  test("execution tools have correct raw names"):
    assertEquals(ToolName.Bash.raw, "Bash")
    assertEquals(ToolName.Task.raw, "Task")

  test("web tools have correct raw names"):
    assertEquals(ToolName.WebFetch.raw, "WebFetch")
    assertEquals(ToolName.WebSearch.raw, "WebSearch")

  test("MCP tools have correct raw names"):
    assertEquals(ToolName.McpResolveLibraryId.raw, "mcp__context7__resolve-library-id")
    assertEquals(ToolName.McpGetLibraryDocs.raw, "mcp__context7__get-library-docs")

  // ============================================
  // fromString Parsing
  // ============================================

  test("fromString parses built-in tool names"):
    assertEquals(ToolName.fromString("Read"), ToolName.Read)
    assertEquals(ToolName.fromString("Write"), ToolName.Write)
    assertEquals(ToolName.fromString("Bash"), ToolName.Bash)
    assertEquals(ToolName.fromString("WebSearch"), ToolName.WebSearch)

  test("fromString parses MCP tool names"):
    assertEquals(
      ToolName.fromString("mcp__context7__resolve-library-id"),
      ToolName.McpResolveLibraryId
    )

  test("fromString returns Custom for unknown tools"):
    val result = ToolName.fromString("my-custom-tool")
    result match
      case ToolName.Custom(name) => assertEquals(name, "my-custom-tool")
      case other                 => fail(s"Expected Custom, got $other")

  test("apply is an alias for fromString"):
    assertEquals(ToolName("Read"), ToolName.Read)
    assertEquals(ToolName("unknown"), ToolName.Custom("unknown"))

  // ============================================
  // Category Helpers
  // ============================================

  test("isFileOperation returns true for file tools"):
    assert(ToolName.isFileOperation(ToolName.Read))
    assert(ToolName.isFileOperation(ToolName.Write))
    assert(ToolName.isFileOperation(ToolName.Edit))
    assert(ToolName.isFileOperation(ToolName.Glob))
    assert(ToolName.isFileOperation(ToolName.Grep))
    assert(ToolName.isFileOperation(ToolName.NotebookEdit))

  test("isFileOperation returns false for non-file tools"):
    assert(!ToolName.isFileOperation(ToolName.Bash))
    assert(!ToolName.isFileOperation(ToolName.WebSearch))
    assert(!ToolName.isFileOperation(ToolName.Task))

  test("isDangerous returns true for dangerous tools"):
    assert(ToolName.isDangerous(ToolName.Bash))
    assert(ToolName.isDangerous(ToolName.Write))
    assert(ToolName.isDangerous(ToolName.Edit))
    assert(ToolName.isDangerous(ToolName.NotebookEdit))

  test("isDangerous returns false for safe tools"):
    assert(!ToolName.isDangerous(ToolName.Read))
    assert(!ToolName.isDangerous(ToolName.Glob))
    assert(!ToolName.isDangerous(ToolName.WebSearch))

  test("isReadOnly returns true for read-only tools"):
    assert(ToolName.isReadOnly(ToolName.Read))
    assert(ToolName.isReadOnly(ToolName.Glob))
    assert(ToolName.isReadOnly(ToolName.Grep))
    assert(ToolName.isReadOnly(ToolName.WebFetch))
    assert(ToolName.isReadOnly(ToolName.WebSearch))

  test("isReadOnly returns false for write tools"):
    assert(!ToolName.isReadOnly(ToolName.Write))
    assert(!ToolName.isReadOnly(ToolName.Edit))
    assert(!ToolName.isReadOnly(ToolName.Bash))

  // ============================================
  // Extension Methods
  // ============================================

  test("isBuiltIn returns true for built-in tools"):
    assert(ToolName.Read.isBuiltIn)
    assert(ToolName.Bash.isBuiltIn)
    assert(ToolName.WebSearch.isBuiltIn)

  test("isBuiltIn returns false for custom tools"):
    assert(!ToolName.Custom("my-tool").isBuiltIn)

  test("isCustom returns true for custom tools"):
    assert(ToolName.Custom("my-tool").isCustom)

  test("isCustom returns false for built-in tools"):
    assert(!ToolName.Read.isCustom)
    assert(!ToolName.Bash.isCustom)

  test("isMcp returns true for MCP tools"):
    assert(ToolName.McpResolveLibraryId.isMcp)
    assert(ToolName.McpGetLibraryDocs.isMcp)
    assert(ToolName.GetDiagnostics.isMcp)

  test("isMcp returns true for custom MCP tools"):
    val customMcp = ToolName.Custom("mcp__myserver__mytool")
    assert(customMcp.isMcp)

  test("isMcp returns false for non-MCP tools"):
    assert(!ToolName.Read.isMcp)
    assert(!ToolName.Bash.isMcp)
    assert(!ToolName.Custom("my-tool").isMcp)

  // ============================================
  // JSON Serialization
  // ============================================

  test("ToolName encodes to JSON string"):
    assertEquals(ToolName.Read.toJson, """"Read"""")
    assertEquals(ToolName.Bash.toJson, """"Bash"""")

  test("ToolName decodes from JSON string"):
    assertEquals(""""Read"""".fromJson[ToolName], Right(ToolName.Read))
    assertEquals(""""Bash"""".fromJson[ToolName], Right(ToolName.Bash))

  test("Custom tool encodes to JSON"):
    val custom = ToolName.Custom("my-tool")
    assertEquals(custom.toJson, """"my-tool"""")

  test("Unknown tool decodes as Custom"):
    val result = """"unknown-tool"""".fromJson[ToolName]
    result match
      case Right(ToolName.Custom(name)) => assertEquals(name, "unknown-tool")
      case other                        => fail(s"Expected Right(Custom), got $other")

  test("JSON round-trip preserves tool name"):
    val tools = List(
      ToolName.Read,
      ToolName.Bash,
      ToolName.WebSearch,
      ToolName.McpResolveLibraryId,
      ToolName.Custom("test-tool")
    )
    tools.foreach { tool =>
      val json = tool.toJson
      val parsed = json.fromJson[ToolName]
      assertEquals(parsed, Right(tool))
    }
