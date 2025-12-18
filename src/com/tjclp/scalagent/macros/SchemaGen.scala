package com.tjclp.scalagent.macros

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio.schema.Schema
import zio.schema.StandardType

// Alias to avoid conflict with zio.schema.Schema.Map
import scala.collection.immutable.{Map => ScalaMap}

/** Generate JSON Schema from zio-schema Schema[A].
  *
  * This converts ZIO schema definitions to the JSON Schema format expected by the Claude SDK.
  */
object SchemaGen:

  /** Generate JSON Schema object from a zio-schema Schema.
    *
    * @param schema
    *   The zio-schema Schema to convert
    * @param descriptions
    *   Optional parameter descriptions from @Param annotations
    * @return
    *   JavaScript object representing JSON Schema
    */
  def toJsonSchema[A](schema: Schema[A], descriptions: ScalaMap[String, String] = ScalaMap.empty): js.Object =
    schemaToJs(schema, descriptions)

  /** Generate a JSON Schema for function parameters.
    *
    * Creates an object schema where each field corresponds to a function parameter.
    *
    * @param params
    *   List of (name, schema, isRequired) tuples
    * @param descriptions
    *   Parameter descriptions from @Param annotations
    * @return
    *   JavaScript object representing JSON Schema
    */
  def paramsToJsonSchema(
      params: List[(String, Schema[?], Boolean)],
      descriptions: ScalaMap[String, String] = ScalaMap.empty
  ): js.Object =
    val properties = js.Dictionary[js.Any]()
    val required = scala.collection.mutable.ListBuffer[String]()

    params.foreach { case (name, schema, isRequired) =>
      val fieldSchema = schemaToJs(schema, ScalaMap.empty)
      // Add description if available
      descriptions.get(name).foreach { desc =>
        fieldSchema.asInstanceOf[js.Dynamic].description = desc
      }
      properties(name) = fieldSchema
      if isRequired then required += name
    }

    val obj = js.Dynamic.literal(
      `type` = "object",
      properties = properties
    )
    if required.nonEmpty then obj.required = required.toJSArray
    obj.additionalProperties = false
    obj.asInstanceOf[js.Object]

  private def schemaToJs[A](schema: Schema[A], descriptions: ScalaMap[String, String]): js.Object =
    schema match
      // Primitives
      case Schema.Primitive(standardType, _) =>
        primitiveToJs(standardType)

      // Optional types
      case Schema.Optional(innerSchema, _) =>
        // For optional, we return the inner schema (JSON Schema handles optionality via required)
        schemaToJs(innerSchema, descriptions)

      // Sequences
      case Schema.Sequence(elementSchema, _, _, _, _) =>
        js.Dynamic
          .literal(
            `type` = "array",
            items = schemaToJs(elementSchema, ScalaMap.empty)
          )
          .asInstanceOf[js.Object]

      // Case classes (records)
      case record: Schema.Record[A] =>
        recordToJs(record, descriptions)

      // Enums
      case e: Schema.Enum[A] =>
        enumToJs(e)

      // Maps
      case map: Schema.Map[?, ?] =>
        js.Dynamic
          .literal(
            `type` = "object",
            additionalProperties = schemaToJs(map.valueSchema.asInstanceOf[Schema[Any]], ScalaMap.empty)
          )
          .asInstanceOf[js.Object]

      // Lazy (for recursive schemas)
      case Schema.Lazy(schema0) =>
        schemaToJs(schema0(), descriptions)

      // Transform (e.g., newtypes)
      case Schema.Transform(codec, _, _, _, _) =>
        schemaToJs(codec, descriptions)

      // Fallback
      case _ =>
        js.Dynamic.literal(`type` = "object").asInstanceOf[js.Object]

  private def primitiveToJs(standardType: StandardType[?]): js.Object =
    standardType match
      case StandardType.StringType =>
        js.Dynamic.literal(`type` = "string").asInstanceOf[js.Object]
      case StandardType.IntType =>
        js.Dynamic.literal(`type` = "integer").asInstanceOf[js.Object]
      case StandardType.LongType =>
        js.Dynamic.literal(`type` = "integer").asInstanceOf[js.Object]
      case StandardType.FloatType =>
        js.Dynamic.literal(`type` = "number").asInstanceOf[js.Object]
      case StandardType.DoubleType =>
        js.Dynamic.literal(`type` = "number").asInstanceOf[js.Object]
      case StandardType.BoolType =>
        js.Dynamic.literal(`type` = "boolean").asInstanceOf[js.Object]
      case StandardType.BigDecimalType =>
        js.Dynamic.literal(`type` = "number").asInstanceOf[js.Object]
      case StandardType.BigIntegerType =>
        js.Dynamic.literal(`type` = "integer").asInstanceOf[js.Object]
      case StandardType.UUIDType =>
        js.Dynamic.literal(`type` = "string", format = "uuid").asInstanceOf[js.Object]
      case StandardType.LocalDateType =>
        js.Dynamic.literal(`type` = "string", format = "date").asInstanceOf[js.Object]
      case StandardType.LocalDateTimeType =>
        js.Dynamic.literal(`type` = "string", format = "date-time").asInstanceOf[js.Object]
      case StandardType.InstantType =>
        js.Dynamic.literal(`type` = "string", format = "date-time").asInstanceOf[js.Object]
      case _ =>
        js.Dynamic.literal(`type` = "string").asInstanceOf[js.Object]

  private def recordToJs[A](record: Schema.Record[A], descriptions: ScalaMap[String, String]): js.Object =
    val properties = js.Dictionary[js.Any]()
    val required = scala.collection.mutable.ListBuffer[String]()

    record.fields.foreach { field =>
      val fieldSchema = schemaToJs(field.schema, ScalaMap.empty)
      // Add description if available
      descriptions.get(field.name.toString).foreach { desc =>
        fieldSchema.asInstanceOf[js.Dynamic].description = desc
      }
      properties(field.name.toString) = fieldSchema

      // Check if field is required (not optional)
      field.schema match
        case _: Schema.Optional[?] => () // Optional, not required
        case _                     => required += field.name.toString
    }

    val obj = js.Dynamic.literal(
      `type` = "object",
      properties = properties
    )
    if required.nonEmpty then obj.required = required.toJSArray
    obj.additionalProperties = false
    obj.asInstanceOf[js.Object]

  private def enumToJs[A](e: Schema.Enum[A]): js.Object =
    // For simple string enums, generate enum constraint
    val cases = e.cases.map(_.id)
    js.Dynamic
      .literal(
        `type` = "string",
        `enum` = cases.toJSArray
      )
      .asInstanceOf[js.Object]
