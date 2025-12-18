package com.tjclp.scalagent.macros

import scala.annotation.StaticAnnotation

/** Mark a method as an MCP tool.
  *
  * Methods annotated with @Tool will be discovered by ToolMacros.createServer and converted into tool definitions.
  *
  * Example:
  * {{{
  * @Tool("get_weather", "Get current weather for a location")
  * def getWeather(
  *   @Param("City name") location: String,
  *   @Param("Temperature unit") unit: Option[String] = None
  * ): Task[ToolResult] = ???
  * }}}
  *
  * @param name
  *   The tool name (used in API calls)
  * @param description
  *   Human-readable description of what the tool does
  */
final class Tool(
    val name: String,
    val description: String
) extends StaticAnnotation

/** Describe a tool parameter.
  *
  * Use on method parameters to provide descriptions that will be included in the JSON schema.
  *
  * Example:
  * {{{
  * def search(
  *   @Param("Search query string") query: String,
  *   @Param("Maximum results to return") limit: Option[Int] = None
  * ): Task[ToolResult] = ???
  * }}}
  *
  * @param description
  *   Human-readable description of the parameter
  */
final class Param(
    val description: String
) extends StaticAnnotation

/** Describe a field in a structured output case class.
  *
  * Use on case class fields to provide descriptions that will be included in the JSON schema for structured output.
  *
  * Example:
  * {{{
  * case class Analysis(
  *   @description("A brief summary of the findings") summary: String,
  *   @description("Confidence score from 0.0 to 1.0") confidence: Double,
  *   @description("List of identified issues") issues: List[String]
  * )
  *
  * given StructuredOutput[Analysis] = StructuredOutput.derive[Analysis]
  * }}}
  *
  * @param text
  *   Human-readable description of the field
  */
final class description(
    val text: String
) extends StaticAnnotation
