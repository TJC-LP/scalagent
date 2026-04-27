package com.tjclp.scalagent.tools

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*

/**
 * Minimal Zod facade for SDK tool schema creation.
 *
 * The Claude Agent SDK expects Zod schemas for tool input validation.
 * This facade provides the minimal interface needed to create Zod schemas
 * from our JsonSchema definitions.
 */
@js.native
@JSImport("zod", "z")
object Zod extends js.Object:
  def string(): ZodType                                = js.native
  def number(): ZodType                                = js.native
  def boolean(): ZodType                               = js.native
  def `enum`(values: js.Array[String]): ZodType        = js.native
  def array(schema: ZodType): ZodType                  = js.native
  def `object`(shape: js.Dictionary[ZodType]): ZodType = js.native
  def union(types: js.Array[ZodType]): ZodType         = js.native

@js.native
trait ZodType extends js.Object:
  def optional(): ZodType                    = js.native
  def describe(description: String): ZodType = js.native

/** Helper to convert JsonSchema to Zod schema */
object ZodConverter:

  /** Convert a JsonSchema to a Zod raw shape (dictionary of Zod types) */
  def toZodRawShape(schema: JsonSchema): js.Dictionary[ZodType] =
    schema match
      case JsonSchema.ObjectType(properties, required, _) =>
        val dict = js.Dictionary[ZodType]()
        properties.foreach {
          case (name, propSchema) =>
            val zodType = toZodType(propSchema)
            // Make optional if not in required list
            val finalType = if required.contains(name) then zodType else zodType.optional()
            dict(name) = finalType
        }
        dict
      case _ =>
        // For non-object schemas, return empty shape
        js.Dictionary[ZodType]()

  /** Convert an ObjectBuilder to a Zod raw shape */
  def toZodRawShape(builder: JsonSchema.ObjectBuilder): js.Dictionary[ZodType] =
    toZodRawShape(builder.build)

  /** Convert a JsonSchema to a ZodType */
  def toZodType(schema: JsonSchema): ZodType =
    schema match
      case JsonSchema.Described(inner, desc) =>
        toZodType(inner).describe(desc)
      case JsonSchema.StringType         => Zod.string()
      case JsonSchema.IntType            => Zod.number()
      case JsonSchema.NumberType         => Zod.number()
      case JsonSchema.BooleanType        => Zod.boolean()
      case JsonSchema.EnumType(values)   => Zod.`enum`(values.toJSArray)
      case JsonSchema.ArrayType(items)   => Zod.array(toZodType(items))
      case obj: JsonSchema.ObjectType    => Zod.`object`(toZodRawShape(obj))
      case JsonSchema.AnyOfType(schemas) => Zod.union(schemas.map(toZodType).toJSArray)
end ZodConverter
