package com.tjclp.scalagent.interop.fastmcp

import com.tjclp.fastmcp.core.{
  Content as FastContent,
  EmbeddedResource as FastEmbeddedResource,
  ImageContent as FastImageContent,
  TextContent as FastTextContent,
  ToolDefinition,
  toJsonString,
}
import com.tjclp.fastmcp.server.{JsMcpServer, McpServerApp, Transport}
import com.tjclp.scalagent.config.McpServerConfig
import com.tjclp.scalagent.core.mcp.McpToolSurface
import com.tjclp.scalagent.interop.mcp.McpToolLoader
import com.tjclp.scalagent.tools.{JsonSchema, ResourceContents, ToolContent, ToolDef, ToolResult}

import zio.*
import zio.json.*
import zio.json.ast.Json

/**
 * Adapts a fast-mcp-scala server into scalagent's in-process SDK MCP shape.
 *
 * fast-mcp-scala remains the neutral typed MCP DSL/runtime. This module is
 * the optional scalagent-side bridge for agents that want to mount those
 * tools in-process instead of spawning the same server over stdio.
 */
object FastMcpMount:

  /**
   * Build an MCP tool surface from a fast-mcp-scala `McpServerApp`.
   *
   * The app is built but not run; its registered tools are wrapped as
   * scalagent `ToolDef`s whose handlers delegate back through fast-mcp's
   * manager layer.
   */
  def toolSurfaceFromApp[T <: Transport, Self <: Singleton](
    serverName: String,
    app: McpServerApp[T, Self],
  ): Task[McpToolSurface] =
    app.buildCore.flatMap {
      case server: JsMcpServer => ZIO.fromEither(toolSurface(serverName, server))
      case other               =>
        ZIO.fail(
          new IllegalArgumentException(
            s"fast-mcp scalagent mounting currently requires JsMcpServer, got ${other.getClass.getName}"
          )
        )
    }

  /** Build an in-process MCP server factory from a fast-mcp-scala app. */
  def serverFactoryFromApp[T <: Transport, Self <: Singleton](
    serverName: String,
    app: McpServerApp[T, Self],
    version: String = "1.0.0",
    runtime: Runtime[Any] = Runtime.default,
  ): Task[McpServerConfig.SdkFactory] =
    toolSurfaceFromApp(serverName, app).map { surface =>
      McpToolLoader.toServerFactory(surface, runtime).copy(version = version)
    }

  /** Build a tool surface from an already-built JS fast-mcp server. */
  def toolSurface(serverName: String, server: JsMcpServer): Either[Throwable, McpToolSurface] =
    val tools =
      server.toolManager
        .listDefinitions()
        .sortBy(_.name)
        .map(definition => toolDef(server, definition))
    collectThrowable(tools).map(McpToolSurface(serverName, _))

  private def toolDef(
    server: JsMcpServer,
    definition: ToolDefinition,
  ): Either[Throwable, ToolDef[Json]] =
    schemaFromFastMcp(definition.inputSchema.toJsonString).map { schema =>
      ToolDef[Json](
        name = definition.name,
        description = definition.description.getOrElse(""),
        inputSchema = schema,
        handler = input =>
          server.toolManager
            .callTool(definition.name, jsonObjectToMap(input), None)
            .map(fastResultToToolResult)
            .catchAll(err => ZIO.succeed(ToolResult.error(Option(err.getMessage).getOrElse(err.getClass.getName)))),
      )
    }

  private def fastResultToToolResult(result: Any): ToolResult =
    result match
      case contents: List[?] if contents.forall(_.isInstanceOf[FastContent]) =>
        ToolResult.Multi(contents.asInstanceOf[List[FastContent]].map(contentToToolContent))
      case content: FastContent =>
        ToolResult.Multi(List(contentToToolContent(content)))
      case s: String =>
        ToolResult.text(s)
      case null =>
        ToolResult.text("null")
      case other =>
        ToolResult.text(other.toString)

  private def contentToToolContent(content: FastContent): ToolContent =
    content match
      case FastTextContent(text, _, _) =>
        ToolContent.Text(text)
      case FastImageContent(data, mimeType, _, _) =>
        ToolContent.Image(data, mimeType)
      case FastEmbeddedResource(resource, _, _) =>
        val contents =
          resource.text match
            case Some(text) =>
              ResourceContents.Text(resource.uri, text, Some(resource.mimeType))
            case None =>
              ResourceContents.Blob(resource.uri, resource.blob.getOrElse(""), Some(resource.mimeType))
        ToolContent.EmbeddedResource(contents)

  private def schemaFromFastMcp(schemaJson: String): Either[Throwable, JsonSchema] =
    Json.decoder
      .decodeJson(schemaJson)
      .left
      .map(err => new IllegalArgumentException(s"Invalid fast-mcp tool schema JSON: $err"))
      .flatMap(json =>
        schemaFromJson(json).left.map(err => new IllegalArgumentException(s"Unsupported fast-mcp tool schema: $err"))
      )

  private def schemaFromJson(json: Json): Either[String, JsonSchema] =
    json.asObject match
      case None      => Left("expected object schema")
      case Some(obj) =>
        val fields = obj.fields.toMap
        fields.get("anyOf").orElse(fields.get("oneOf")).flatMap(_.asArray) match
          case Some(items) =>
            collectString(items.toList.map(schemaFromJson)).map(items => JsonSchema.anyOf(items*))
          case None =>
            schemaFromTypedObject(fields)

  private def schemaFromTypedObject(fields: Map[String, Json]): Either[String, JsonSchema] =
    fields.get("enum").flatMap(_.asArray) match
      case Some(values) =>
        Right(JsonSchema.enumOf(values.flatMap(_.asString).toSeq*))
      case None =>
        fields.get("type") match
          case Some(Json.Str("string"))  => Right(JsonSchema.string)
          case Some(Json.Str("integer")) => Right(JsonSchema.int)
          case Some(Json.Str("number"))  => Right(JsonSchema.number)
          case Some(Json.Str("boolean")) => Right(JsonSchema.boolean)
          case Some(Json.Str("array"))   =>
            fields.get("items") match
              case Some(items) => schemaFromJson(items).map(JsonSchema.array)
              case None        => Right(JsonSchema.array(JsonSchema.obj().additionalProperties))
          case Some(Json.Str("object")) =>
            objectSchema(fields)
          case Some(Json.Arr(types)) =>
            val schemas =
              types.toList.collect {
                case Json.Str("string")  => schemaFromTypedObject(Map("type" -> Json.Str("string")))
                case Json.Str("integer") => schemaFromTypedObject(Map("type" -> Json.Str("integer")))
                case Json.Str("number")  => schemaFromTypedObject(Map("type" -> Json.Str("number")))
                case Json.Str("boolean") => schemaFromTypedObject(Map("type" -> Json.Str("boolean")))
                case Json.Str("object")  => objectSchema(fields.updated("type", Json.Str("object")))
                case Json.Str("array")   => schemaFromTypedObject(fields.updated("type", Json.Str("array")))
              }
            collectString(schemas).map(items => JsonSchema.anyOf(items*))
          case other =>
            Left(s"unsupported schema type: ${other.map(_.toJson).getOrElse("<missing>")}")

  private def objectSchema(fields: Map[String, Json]): Either[String, JsonSchema] =
    val required =
      fields
        .get("required")
        .flatMap(_.asArray)
        .map(_.flatMap(_.asString).toList)
        .getOrElse(Nil)
    val allowAdditional =
      fields
        .get("additionalProperties")
        .flatMap(_.asBoolean)
        .getOrElse(false)

    val propertiesJson =
      fields
        .get("properties")
        .flatMap(_.asObject)
        .map(_.fields.toMap)
        .getOrElse(Map.empty)

    collectString(propertiesJson.toList.map {
      case (name, prop) =>
        schemaFromJson(prop).map(name -> _)
    }).map { properties => JsonSchema.ObjectType(properties.toMap, required, allowAdditional) }
  end objectSchema

  private def jsonObjectToMap(json: Json): Map[String, Any] =
    json.asObject match
      case Some(obj) => obj.fields.toMap.view.mapValues(jsonToAny).toMap
      case None      => Map.empty

  private def jsonToAny(json: Json): Any =
    json match
      case Json.Str(s)   => s
      case Json.Num(n)   => n.doubleValue
      case Json.Bool(b)  => b
      case Json.Null     => null
      case Json.Arr(a)   => a.toList.map(jsonToAny)
      case obj: Json.Obj =>
        obj.fields.toMap.view.mapValues(jsonToAny).toMap

  private def collectString[A](values: List[Either[String, A]]): Either[String, List[A]] =
    values.foldRight[Either[String, List[A]]](Right(Nil)) { (next, acc) =>
      for
        value <- next
        rest  <- acc
      yield value :: rest
    }

  private def collectThrowable[A](values: List[Either[Throwable, A]]): Either[Throwable, List[A]] =
    values.foldRight[Either[Throwable, List[A]]](Right(Nil)) { (next, acc) =>
      for
        value <- next
        rest  <- acc
      yield value :: rest
    }
end FastMcpMount
