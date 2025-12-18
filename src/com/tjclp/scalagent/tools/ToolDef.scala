package com.tjclp.scalagent.tools

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.json.*

/** Tool definition for custom tools.
  *
  * This is a skeleton for future type-safe tool definitions. The SDK uses Zod schemas for tool input validation, which
  * doesn't translate well to ScalablyTyped. This DSL provides a Scala-native alternative.
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

      // Handle errors gracefully
      val safeEffect = effect.catchAll { err =>
        val errMsg = Option(err.getMessage).getOrElse(err.getClass.getName)
        val fullError = s"Tool execution failed: $errMsg"
        ZIO.succeed(
          resultToJs(ToolResult.Error(fullError))
        )
      }

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(safeEffect).toJSPromise
      }
    }

  /** Convert a ToolResult to the SDK's expected format */
  private def resultToJs(result: ToolResult): js.Object =
    result.toRaw

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

  /** Resource reference */
  final case class Resource(uri: String, mimeType: Option[String] = None) extends ToolContent:
    def toRaw: js.Dynamic =
      val obj = js.Dynamic.literal(`type` = "resource", uri = uri)
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
  def error(msg: String): ToolResult = Error(msg)

  // Multimodal builder for fluent construction
  def multi: MultiBuilder = MultiBuilder(Nil)

  final case class MultiBuilder(contents: List[ToolContent]):
    def text(s: String): MultiBuilder = copy(contents = contents :+ ToolContent.Text(s))
    def image(base64: String, mime: String = "image/png"): MultiBuilder =
      copy(contents = contents :+ ToolContent.Image(base64, mime))
    def resource(uri: String, mime: Option[String] = None): MultiBuilder =
      copy(contents = contents :+ ToolContent.Resource(uri, mime))
    def build: ToolResult = Multi(contents)

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

/** Tool builder DSL for fluent tool definition.
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
