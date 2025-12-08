package com.tjclp.claude.agent.tools

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio._
import zio.json._

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
final case class ToolDef[A: JsonDecoder](
    name: String,
    description: String,
    inputSchema: JsonSchema,
    handler: A => Task[ToolResult]
):

  /** Convert to raw JavaScript tool definition for SDK */
  def toRaw: js.Object =
    js.Dynamic
      .literal(
        name = name,
        description = description,
        inputSchema = inputSchema.toRaw
      )
      .asInstanceOf[js.Object]

/** Result of tool execution */
sealed trait ToolResult

object ToolResult:
  /** Successful tool execution */
  final case class Success(content: String) extends ToolResult

  /** Tool execution failed */
  final case class Error(message: String) extends ToolResult

  given JsonEncoder[ToolResult] = DeriveJsonEncoder.gen[ToolResult]
  given JsonDecoder[ToolResult] = DeriveJsonDecoder.gen[ToolResult]

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
