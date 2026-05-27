package com.tjclp.scalagent.core.mcp

class McpSurfaceSpec extends munit.FunSuite:

  // --- McpToolSurface ---

  test("McpToolSurface.empty has no tools"):
    val surface = McpToolSurface.empty("test-server")
    assert(surface.isEmpty)
    assertEquals(surface.serverName, "test-server")

  test("McpToolSurface converts to ToolSurface"):
    val surface = McpToolSurface("server", Nil)
    val ts = surface.toToolSurface
    assert(ts.isEmpty)

  // --- McpResource ---

  test("McpResource construction"):
    val resource = McpResource(
      uri = "file:///data/config.json",
      name = "config",
      description = Some("Application configuration"),
      mimeType = Some("application/json")
    )
    assertEquals(resource.uri, "file:///data/config.json")
    assertEquals(resource.name, "config")

  test("McpResource minimal construction"):
    val resource = McpResource(uri = "memory://cache", name = "cache")
    assertEquals(resource.description, None)
    assertEquals(resource.mimeType, None)

  // --- McpResourceContent ---

  test("McpResourceContent.Text construction"):
    val content = McpResourceContent.Text("hello world", Some("text/plain"))
    content match
      case McpResourceContent.Text(text, mime) =>
        assertEquals(text, "hello world")
        assertEquals(mime, Some("text/plain"))
      case _ => fail("Expected Text")

  test("McpResourceContent.Blob construction"):
    val data = Array[Byte](1, 2, 3)
    val content = McpResourceContent.Blob(data, Some("application/octet-stream"))
    content match
      case McpResourceContent.Blob(bytes, mime) =>
        assertEquals(bytes.length, 3)
        assertEquals(mime, Some("application/octet-stream"))
      case _ => fail("Expected Blob")

  // --- McpPrompt ---

  test("McpPrompt construction"):
    val prompt = McpPrompt(
      name = "code-review",
      description = Some("Review code for quality"),
      arguments = List(
        McpPromptArgument("language", Some("Programming language"), required = true),
        McpPromptArgument("style", Some("Review style"), required = false)
      )
    )
    assertEquals(prompt.name, "code-review")
    assertEquals(prompt.arguments.size, 2)
    assert(prompt.arguments.head.required)
    assert(!prompt.arguments(1).required)

  test("McpPrompt minimal construction"):
    val prompt = McpPrompt(name = "summarize")
    assertEquals(prompt.description, None)
    assert(prompt.arguments.isEmpty)

  // --- McpCapability markers ---

  test("HasMcpTools is a Capability"):
    // Compile-time: these extend Capability
    summon[HasMcpTools <:< com.tjclp.scalagent.core.Capability]

  test("HasMcpResources is a Capability"):
    summon[HasMcpResources <:< com.tjclp.scalagent.core.Capability]

  test("HasMcpPrompts is a Capability"):
    summon[HasMcpPrompts <:< com.tjclp.scalagent.core.Capability]

  test("FullMcpCaps composes all three"):
    summon[FullMcpCaps <:< HasMcpTools]
    summon[FullMcpCaps <:< HasMcpResources]
    summon[FullMcpCaps <:< HasMcpPrompts]
