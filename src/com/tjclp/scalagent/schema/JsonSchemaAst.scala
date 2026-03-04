package com.tjclp.scalagent.schema

import zio.json.ast.Json

/** Shared JSON Schema AST constructors.
  *
  * Centralizes low-level schema object construction so macro-based and
  * zio-schema-based generation paths stay consistent.
  */
object JsonSchemaAst:
  val string: Json = Json.Obj("type" -> Json.Str("string"))
  val integer: Json = Json.Obj("type" -> Json.Str("integer"))
  val boolean: Json = Json.Obj("type" -> Json.Str("boolean"))
  val nullType: Json = Json.Obj("type" -> Json.Str("null"))
  val objectType: Json = Json.Obj("type" -> Json.Str("object"))

  def integerWithFormat(format: String): Json =
    Json.Obj(
      "type" -> Json.Str("integer"),
      "format" -> Json.Str(format)
    )

  def numberWithFormat(format: String): Json =
    Json.Obj(
      "type" -> Json.Str("number"),
      "format" -> Json.Str(format)
    )

  def number: Json =
    Json.Obj("type" -> Json.Str("number"))

  def withFormat(schemaType: String, format: String): Json =
    Json.Obj(
      "type" -> Json.Str(schemaType),
      "format" -> Json.Str(format)
    )

  def array(items: Json, uniqueItems: Boolean = false): Json =
    if uniqueItems then
      Json.Obj(
        "type" -> Json.Str("array"),
        "items" -> items,
        "uniqueItems" -> Json.Bool(true)
      )
    else
      Json.Obj(
        "type" -> Json.Str("array"),
        "items" -> items
      )

  def map(additionalProperties: Json): Json =
    Json.Obj(
      "type" -> Json.Str("object"),
      "additionalProperties" -> additionalProperties
    )

  def objectSchema(properties: List[(String, Json)], required: List[String]): Json =
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(properties*),
      "required" -> Json.Arr(required.map(Json.Str(_))*),
      "additionalProperties" -> Json.Bool(false)
    )

  def enumOf(values: List[String]): Json =
    Json.Obj(
      "type" -> Json.Str("string"),
      "enum" -> Json.Arr(values.map(Json.Str(_))*)
    )

  def tuple2(left: Json, right: Json): Json =
    Json.Obj(
      "type" -> Json.Str("array"),
      "items" -> Json.Arr(left, right),
      "minItems" -> Json.Num(2),
      "maxItems" -> Json.Num(2)
    )

  def oneOf(items: List[Json]): Json =
    Json.Obj("oneOf" -> Json.Arr(items*))

  def withDescription(schema: Json, description: String): Json =
    schema match
      case Json.Obj(fields) =>
        Json.Obj(fields.toList :+ ("description" -> Json.Str(description))*)
      case other => other
