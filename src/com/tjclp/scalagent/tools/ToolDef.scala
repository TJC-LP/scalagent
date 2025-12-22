package com.tjclp.scalagent.tools

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.json.*

/** Tool definition for custom tools.
  *
  * Use ToolInput.derive for case-class inputs to generate schemas automatically and keep tool
  * definitions type-safe. The SDK expects Zod schemas for validation; we generate them from our
  * JsonSchema representation.
  *
  * @tparam A
  *   The input type for the tool
  * @param name
  *   The tool name
  * @param description
  *   Human-readable description
  * @param inputSchema
  *   JSON Schema for the tool input
  * @param handler
  *   The function that executes the tool
  */
final case class ToolDef[A](
    name: String,
    description: String,
    inputSchema: JsonSchema,
    handler: A => Task[ToolResult]
)(using decoder: JsonDecoder[A]):

  /** Convert to raw JavaScript tool definition for SDK */
  def toRaw: js.Object =
    js.Dynamic
      .literal(
        name = name,
        description = description,
        inputSchema = inputSchema.toRaw
      )
      .asInstanceOf[js.Object]

  /** Convert to SDK MCP tool format with handler.
    *
    * This captures the JsonDecoder at construction time, allowing tools to be collected in List[ToolDef[?]] and still
    * retain their parsing capability.
    *
    * The SDK expects Zod schemas for inputSchema, not JSON Schema objects.
    * We convert our JsonSchema to a Zod raw shape.
    *
    * @param runtime
    *   ZIO runtime for executing the handler
    * @return
    *   JavaScript object in SDK tool format
    */
  def toSdkTool(runtime: Runtime[Any]): js.Object =
    // Convert JsonSchema to Zod raw shape - SDK expects Zod, not JSON Schema
    val zodSchema = ZodConverter.toZodRawShape(inputSchema)
    js.Dynamic
      .literal(
        name = name,
        description = description,
        inputSchema = zodSchema,
        handler = createSdkHandler(runtime)
      )
      .asInstanceOf[js.Object]

  /** Create a JavaScript handler function from the ZIO-based handler.
    *
    * The SDK expects: (args: object, extra: unknown) => Promise<CallToolResult>
    *
    * CallToolResult format: { content: [{ type: "text", text: string }], isError?: boolean }
    */
  private def createSdkHandler(
      runtime: Runtime[Any]
  ): js.Function2[js.Any, js.Any, js.Promise[js.Object]] =
    (args: js.Any, extra: js.Any) => {
      val effect = for
        // Parse input JSON to Scala type
        inputJson <- ZIO.attempt(js.JSON.stringify(args))
        input <- ZIO
          .fromEither(inputJson.fromJson[A])
          .mapError(err => new RuntimeException(s"Failed to parse tool input: $err"))

        // Execute the handler
        result <- handler(input)
      yield resultToJs(result)

      Unsafe.unsafe { implicit unsafe =>
        // Let failures propagate to the SDK; return ToolResult.Error for custom error content.
        runtime.unsafe.runToFuture(effect).toJSPromise
      }
    }

  /** Convert a ToolResult to the SDK's expected format */
  private def resultToJs(result: ToolResult): js.Object =
    result.toRaw

object ToolDef:
  /** Create a tool from a derived ToolInput instance. */
  def fromInput[A: JsonDecoder](
      name: String,
      description: String
  )(handler: A => Task[ToolResult])(using input: ToolInput[A]): ToolDef[A] =
    ToolDef(name, description, input.jsonSchema, handler)

  /** Derive the input schema from the case class type directly. */
  inline def derive[A: JsonDecoder](
      name: String,
      description: String
  )(handler: A => Task[ToolResult]): ToolDef[A] =
    val input = ToolInput.derive[A]
    ToolDef(name, description, input.jsonSchema, handler)

/** Content block types for tool results - supports multimodal output */
sealed trait ToolContent:
  def toRaw: js.Dynamic

object ToolContent:
  /** Plain text content */
  final case class Text(text: String) extends ToolContent:
    def toRaw: js.Dynamic = js.Dynamic.literal(`type` = "text", text = text)

  /** Image content (base64 encoded) */
  final case class Image(data: String, mimeType: String = "image/png") extends ToolContent:
    def toRaw: js.Dynamic = js.Dynamic.literal(
      `type` = "image",
      data = data,
      mimeType = mimeType
    )

  /** Audio content (base64 encoded) */
  final case class Audio(data: String, mimeType: String = "audio/wav") extends ToolContent:
    def toRaw: js.Dynamic = js.Dynamic.literal(
      `type` = "audio",
      data = data,
      mimeType = mimeType
    )

  /** Resource link (by URI) */
  final case class ResourceLink(
      uri: String,
      name: Option[String] = None,
      mimeType: Option[String] = None,
      description: Option[String] = None
  ) extends ToolContent:
    def toRaw: js.Dynamic =
      val obj = js.Dynamic.literal(
        `type` = "resource_link",
        name = name.getOrElse(uri),
        uri = uri
      )
      mimeType.foreach(m => obj.mimeType = m)
      description.foreach(d => obj.description = d)
      obj

  /** Embedded resource content (text/blob) */
  final case class EmbeddedResource(resource: ResourceContents) extends ToolContent:
    def toRaw: js.Dynamic =
      js.Dynamic.literal(`type` = "resource", resource = resource.toRaw)

  /** Backwards-compatible alias for resource links */
  @deprecated("Use ResourceLink or EmbeddedResource", "0.1.0")
  final case class Resource(
      uri: String,
      name: Option[String] = None,
      mimeType: Option[String] = None,
      description: Option[String] = None
  ) extends ToolContent:
    def toRaw: js.Dynamic =
      ResourceLink(uri, name, mimeType, description).toRaw

/** Embedded resource contents */
sealed trait ResourceContents:
  def toRaw: js.Dynamic

object ResourceContents:
  /** Text resource payload */
  final case class Text(
      uri: String,
      text: String,
      mimeType: Option[String] = None
  ) extends ResourceContents:
    def toRaw: js.Dynamic =
      val obj = js.Dynamic.literal(uri = uri, text = text)
      mimeType.foreach(m => obj.mimeType = m)
      obj

  /** Binary resource payload (base64-encoded) */
  final case class Blob(
      uri: String,
      blob: String,
      mimeType: Option[String] = None
  ) extends ResourceContents:
    def toRaw: js.Dynamic =
      val obj = js.Dynamic.literal(uri = uri, blob = blob)
      mimeType.foreach(m => obj.mimeType = m)
      obj

/** Result of tool execution - supports structured output and multimodal content */
sealed trait ToolResult:
  /** Convert to SDK's expected format */
  def toRaw: js.Object

object ToolResult:
  /** Single text success */
  final case class Success(content: String) extends ToolResult:
    def toRaw: js.Object = js.Dynamic.literal(
      content = js.Array(js.Dynamic.literal(`type` = "text", text = content))
    ).asInstanceOf[js.Object]

  /** Structured JSON success - type-safe serialization */
  final case class Structured[A](data: A)(using encoder: JsonEncoder[A]) extends ToolResult:
    def toRaw: js.Object = js.Dynamic.literal(
      content = js.Array(js.Dynamic.literal(`type` = "text", text = encoder.encodeJson(data, None).toString))
    ).asInstanceOf[js.Object]

  /** Multiple content blocks (multimodal) */
  final case class Multi(contents: List[ToolContent]) extends ToolResult:
    def toRaw: js.Object = js.Dynamic.literal(
      content = js.Array(contents.map(_.toRaw)*)
    ).asInstanceOf[js.Object]

  /** Error result with custom content blocks */
  final case class Failure(contents: List[ToolContent]) extends ToolResult:
    def toRaw: js.Object = js.Dynamic.literal(
      content = js.Array(contents.map(_.toRaw)*),
      isError = true
    ).asInstanceOf[js.Object]

  /** Error result */
  final case class Error(message: String) extends ToolResult:
    def toRaw: js.Object = js.Dynamic.literal(
      content = js.Array(js.Dynamic.literal(`type` = "text", text = message)),
      isError = true
    ).asInstanceOf[js.Object]

  // Convenience constructors
  def text(s: String): ToolResult = Success(s)
  def json[A: JsonEncoder](a: A): ToolResult = Structured(a)
  def image(base64: String, mime: String = "image/png"): ToolResult =
    Multi(List(ToolContent.Image(base64, mime)))
  def audio(base64: String, mime: String = "audio/wav"): ToolResult =
    Multi(List(ToolContent.Audio(base64, mime)))
  def resourceLink(
      uri: String,
      name: Option[String] = None,
      mimeType: Option[String] = None,
      description: Option[String] = None
  ): ToolResult =
    Multi(List(ToolContent.ResourceLink(uri, name, mimeType, description)))
  def resourceText(uri: String, text: String, mimeType: Option[String] = None): ToolResult =
    Multi(List(ToolContent.EmbeddedResource(ResourceContents.Text(uri, text, mimeType))))
  def resourceBlob(uri: String, blob: String, mimeType: Option[String] = None): ToolResult =
    Multi(List(ToolContent.EmbeddedResource(ResourceContents.Blob(uri, blob, mimeType))))
  def error(msg: String): ToolResult = Error(msg)
  def errorContents(contents: ToolContent*): ToolResult = Failure(contents.toList)

  // Multimodal builder for fluent construction
  def multi: MultiBuilder = MultiBuilder(Nil)

  final case class MultiBuilder(contents: List[ToolContent]):
    def text(s: String): MultiBuilder = copy(contents = contents :+ ToolContent.Text(s))
    def image(base64: String, mime: String = "image/png"): MultiBuilder =
      copy(contents = contents :+ ToolContent.Image(base64, mime))
    def audio(base64: String, mime: String = "audio/wav"): MultiBuilder =
      copy(contents = contents :+ ToolContent.Audio(base64, mime))
    def resource(uri: String, mime: Option[String] = None): MultiBuilder =
      copy(contents = contents :+ ToolContent.ResourceLink(uri, None, mime))
    def resourceLink(
        uri: String,
        name: Option[String] = None,
        mimeType: Option[String] = None,
        description: Option[String] = None
    ): MultiBuilder =
      copy(contents = contents :+ ToolContent.ResourceLink(uri, name, mimeType, description))
    def resourceText(uri: String, text: String, mimeType: Option[String] = None): MultiBuilder =
      copy(contents = contents :+ ToolContent.EmbeddedResource(ResourceContents.Text(uri, text, mimeType)))
    def resourceBlob(uri: String, blob: String, mimeType: Option[String] = None): MultiBuilder =
      copy(contents = contents :+ ToolContent.EmbeddedResource(ResourceContents.Blob(uri, blob, mimeType)))
    def build: ToolResult = Multi(contents)
    def buildError: ToolResult = Failure(contents)

/** JSON Schema representation for tool input validation.
  *
  * This is a simplified JSON Schema builder for defining tool input structures.
  */
sealed trait JsonSchema:
  /** Convert to raw JavaScript object */
  def toRaw: js.Object

object JsonSchema:

  /** String type */
  case object StringType extends JsonSchema:
    def toRaw: js.Object = js.Dynamic.literal(`type` = "string").asInstanceOf[js.Object]

  /** Integer type */
  case object IntType extends JsonSchema:
    def toRaw: js.Object = js.Dynamic.literal(`type` = "integer").asInstanceOf[js.Object]

  /** Number type */
  case object NumberType extends JsonSchema:
    def toRaw: js.Object = js.Dynamic.literal(`type` = "number").asInstanceOf[js.Object]

  /** Boolean type */
  case object BooleanType extends JsonSchema:
    def toRaw: js.Object = js.Dynamic.literal(`type` = "boolean").asInstanceOf[js.Object]

  /** Array type */
  final case class ArrayType(items: JsonSchema) extends JsonSchema:
    def toRaw: js.Object =
      js.Dynamic.literal(`type` = "array", items = items.toRaw).asInstanceOf[js.Object]

  /** Enum type (string literals) */
  final case class EnumType(values: List[String]) extends JsonSchema:
    def toRaw: js.Object =
      js.Dynamic.literal(`type` = "string", `enum` = values.toJSArray).asInstanceOf[js.Object]

  /** Object type */
  final case class ObjectType(
      properties: Map[String, JsonSchema],
      required: List[String] = Nil,
      additionalProperties: Boolean = false
  ) extends JsonSchema:
    def toRaw: js.Object =
      val props = js.Dictionary(properties.view.mapValues(_.toRaw).toSeq*)
      val obj = js.Dynamic.literal(
        `type` = "object",
        properties = props,
        additionalProperties = additionalProperties
      )
      if required.nonEmpty then obj.required = required.toJSArray
      obj.asInstanceOf[js.Object]

  /** Schema wrapper with human-readable description */
  final case class Described(schema: JsonSchema, description: String) extends JsonSchema:
    def toRaw: js.Object =
      val obj = schema.toRaw.asInstanceOf[js.Dynamic]
      obj.description = description
      obj.asInstanceOf[js.Object]

  // Builder DSL

  /** String schema */
  def string: JsonSchema = StringType

  /** Integer schema */
  def int: JsonSchema = IntType

  /** Number schema */
  def number: JsonSchema = NumberType

  /** Boolean schema */
  def boolean: JsonSchema = BooleanType

  /** Array schema */
  def array(items: JsonSchema): JsonSchema = ArrayType(items)

  /** Enum schema */
  def enumOf(values: String*): JsonSchema = EnumType(values.toList)

  /** Object schema builder */
  def obj(properties: (String, JsonSchema)*): ObjectBuilder =
    ObjectBuilder(properties.toMap)

  /** Builder for object schemas */
  final case class ObjectBuilder(
      properties: Map[String, JsonSchema],
      requiredFields: List[String] = Nil,
      allowAdditional: Boolean = false
  ):
    /** Mark fields as required */
    def required(fields: String*): ObjectBuilder =
      copy(requiredFields = fields.toList)

    /** Allow additional properties */
    def additionalProperties: ObjectBuilder =
      copy(allowAdditional = true)

    /** Build the final schema */
    def build: JsonSchema =
      ObjectType(properties, requiredFields, allowAdditional)

    /** Implicit conversion to JsonSchema */
    def toRaw: js.Object = build.toRaw

  // Allow ObjectBuilder to be used as JsonSchema
  given Conversion[ObjectBuilder, JsonSchema] = _.build

  extension (schema: JsonSchema)
    /** Attach a description to a schema */
    def describe(description: String): JsonSchema =
      Described(schema, description)

/** Tool builder DSL for fluent tool definition.
  *
  * Prefer ToolDef.fromInput with ToolInput.derive for case-class inputs. Use this
  * builder when you need a fully manual schema.
  *
  * Example usage:
  * {{{
  * val weatherTool = ToolBuilder[WeatherInput]("get_weather")
  *   .description("Get current weather for a location")
  *   .schema(JsonSchema.obj(
  *     "location" -> JsonSchema.string,
  *     "unit" -> JsonSchema.enum("celsius", "fahrenheit")
  *   ).required("location"))
  *   .handler { input =>
  *     fetchWeather(input.location, input.unit).map(ToolResult.Success(_))
  *   }
  * }}}
  */
object ToolBuilder:
  def apply[A: JsonDecoder](name: String): Step1[A] = Step1(name)

  final case class Step1[A: JsonDecoder](name: String):
    def description(desc: String): Step2[A] = Step2(name, desc)

  final case class Step2[A: JsonDecoder](name: String, desc: String):
    def schema(s: JsonSchema): Step3[A] = Step3(name, desc, s)

  final case class Step3[A: JsonDecoder](name: String, desc: String, schema: JsonSchema):
    def handler(f: A => Task[ToolResult]): ToolDef[A] =
      ToolDef(name, desc, schema, f)
