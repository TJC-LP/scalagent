package com.tjclp.claude.agent.config

import munit.FunSuite
import scala.scalajs.js
import zio.json._
import com.tjclp.claude.agent.tools.ToolName
import com.tjclp.claude.agent.mcp.McpToolName

class AgentDefinitionSpec extends FunSuite:

  // ============================================
  // Basic Construction
  // ============================================

  test("AgentDefinition stores description and prompt"):
    val agent = AgentDefinition(
      description = "Test agent",
      prompt = "You are a test agent."
    )
    assertEquals(agent.description, "Test agent")
    assertEquals(agent.prompt, "You are a test agent.")

  test("AgentDefinition defaults to no tool restrictions"):
    val agent = AgentDefinition(
      description = "Agent",
      prompt = "Prompt"
    )
    assertEquals(agent.tools, None)
    assertEquals(agent.disallowedTools, None)
    assertEquals(agent.model, None)
    assertEquals(agent.inheritMcpTools, true)

  test("AgentDefinition can specify allowed tools"):
    val agent = AgentDefinition(
      description = "Reader",
      prompt = "You read files.",
      tools = Some(List(ToolName.Read, ToolName.Glob))
    )
    assertEquals(agent.tools, Some(List(ToolName.Read, ToolName.Glob)))

  test("AgentDefinition can specify model"):
    val agent = AgentDefinition(
      description = "Fast agent",
      prompt = "Quick responses",
      model = Some(AgentModel.Haiku)
    )
    assertEquals(agent.model, Some(AgentModel.Haiku))

  // ============================================
  // Factory Methods
  // ============================================

  test("readOnly creates agent with Read, Grep, Glob tools"):
    val agent = AgentDefinition.readOnly(
      description = "Analyzer",
      prompt = "You analyze code."
    )
    assertEquals(agent.description, "Analyzer")
    assertEquals(agent.tools, Some(List(ToolName.Read, ToolName.Grep, ToolName.Glob)))

  test("readOnly can specify model"):
    val agent = AgentDefinition.readOnly(
      description = "Analyzer",
      prompt = "Analyze",
      model = Some(AgentModel.Sonnet)
    )
    assertEquals(agent.model, Some(AgentModel.Sonnet))

  test("fullAccess creates agent with no tool restrictions"):
    val agent = AgentDefinition.fullAccess(
      description = "Full agent",
      prompt = "Full access"
    )
    assertEquals(agent.tools, None)

  test("noMcpTools creates agent with explicit tools and no MCP inheritance"):
    val agent = AgentDefinition.noMcpTools(
      description = "Isolated",
      prompt = "No MCP",
      ToolName.Read, ToolName.Write
    )
    assertEquals(agent.tools, Some(List(ToolName.Read, ToolName.Write)))
    assertEquals(agent.inheritMcpTools, false)

  test("withMcpTools combines builtin and MCP tools"):
    val mcpTool = McpToolName("myserver", "mytool")
    val agent = AgentDefinition.withMcpTools(
      description = "MCP agent",
      prompt = "Uses MCP"
    )(mcpTool)(ToolName.Read)

    assertEquals(agent.description, "MCP agent")
    agent.tools match
      case Some(tools) =>
        assert(tools.contains(ToolName.Read))
        assert(tools.exists(_.raw == "mcp__myserver__mytool"))
      case None => fail("Expected tools to be defined")

  // ============================================
  // toRaw Serialization
  // ============================================

  test("toRaw includes description and prompt"):
    val agent = AgentDefinition(
      description = "Test",
      prompt = "Prompt"
    )
    val raw = agent.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.description.asInstanceOf[String], "Test")
    assertEquals(raw.prompt.asInstanceOf[String], "Prompt")

  test("toRaw includes tools when specified"):
    val agent = AgentDefinition(
      description = "Test",
      prompt = "Prompt",
      tools = Some(List(ToolName.Read, ToolName.Bash))
    )
    val raw = agent.toRaw.asInstanceOf[js.Dynamic]
    val tools = raw.tools.asInstanceOf[js.Array[String]]
    assert(tools.contains("Read"))
    assert(tools.contains("Bash"))

  test("toRaw includes model when specified"):
    val agent = AgentDefinition(
      description = "Test",
      prompt = "Prompt",
      model = Some(AgentModel.Opus)
    )
    val raw = agent.toRaw.asInstanceOf[js.Dynamic]
    assertEquals(raw.model.asInstanceOf[String], "opus")

  test("toRaw omits undefined optional fields"):
    val agent = AgentDefinition(
      description = "Test",
      prompt = "Prompt"
    )
    val raw = agent.toRaw.asInstanceOf[js.Dynamic]
    assert(js.isUndefined(raw.tools))
    assert(js.isUndefined(raw.model))

  // ============================================
  // JSON Serialization
  // ============================================

  test("AgentDefinition encodes to JSON"):
    val agent = AgentDefinition(
      description = "Test",
      prompt = "Prompt",
      tools = Some(List(ToolName.Read)),
      model = Some(AgentModel.Sonnet)
    )
    val json = agent.toJson
    assert(json.contains("Test"))
    assert(json.contains("Prompt"))
    assert(json.contains("Read"))
    assert(json.contains("sonnet"))

  test("JSON round-trip preserves AgentDefinition"):
    val original = AgentDefinition(
      description = "Analyzer",
      prompt = "You analyze things.",
      tools = Some(List(ToolName.Read, ToolName.Glob)),
      disallowedTools = Some(List(ToolName.Bash)),
      model = Some(AgentModel.Haiku),
      inheritMcpTools = false
    )
    val json = original.toJson
    val parsed = json.fromJson[AgentDefinition]
    assertEquals(parsed, Right(original))

  test("JSON round-trip for minimal AgentDefinition"):
    val original = AgentDefinition(
      description = "Simple",
      prompt = "Simple prompt"
    )
    val json = original.toJson
    val parsed = json.fromJson[AgentDefinition]
    assertEquals(parsed, Right(original))

  // ============================================
  // AgentModel
  // ============================================

  test("AgentModel has correct raw values"):
    assertEquals(AgentModel.Sonnet.raw, "sonnet")
    assertEquals(AgentModel.Opus.raw, "opus")
    assertEquals(AgentModel.Haiku.raw, "haiku")
    assertEquals(AgentModel.Inherit.raw, "inherit")

  test("AgentModel.fromString parses known values"):
    assertEquals(AgentModel.fromString("sonnet"), AgentModel.Sonnet)
    assertEquals(AgentModel.fromString("opus"), AgentModel.Opus)
    assertEquals(AgentModel.fromString("haiku"), AgentModel.Haiku)
    assertEquals(AgentModel.fromString("inherit"), AgentModel.Inherit)

  test("AgentModel.fromString is case-insensitive"):
    assertEquals(AgentModel.fromString("SONNET"), AgentModel.Sonnet)
    assertEquals(AgentModel.fromString("Opus"), AgentModel.Opus)

  test("AgentModel.fromString defaults to Inherit for unknown"):
    assertEquals(AgentModel.fromString("unknown"), AgentModel.Inherit)
    assertEquals(AgentModel.fromString(""), AgentModel.Inherit)
