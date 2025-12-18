package com.tjclp.scalagent.schema

import zio.json.ast.Json
import zio.schema._

/** Converts zio-schema Schema[A] to JSON Schema.
  *
  * Generates standard JSON Schema format compatible with the Claude SDK structured outputs.
  *
  * Example:
  * {{{
  * case class Result(summary: String, score: Int)
  * object Result:
  *   given Schema[Result] = DeriveSchema.gen[Result]
  *
  * val jsonSchema: Json = SchemaToJson.convert(Schema[Result])
  * // {"type": "object", "properties": {...}, "required": [...]}
  * }}}
  */
object SchemaToJson:
  /** Convert a zio-schema Schema to JSON Schema */
  def convert[A](schema: Schema[A]): Json =
    schemaToJson(schema)

  private def schemaToJson(schema: Schema[?]): Json =
    schema match
      case Schema.Primitive(standardType, _) =>
        primitiveToJson(standardType)

      case record: Schema.Record[?] =>
        recordToJson(record)

      case Schema.Optional(inner, _) =>
        // Optional fields handled at record level for required array
        schemaToJson(inner)

      case seq: Schema.Sequence[?, ?, ?] =>
        Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> schemaToJson(seq.elementSchema)
        )

      case enumSchema: Schema.Enum[?] =>
        enumSchemaToJson(enumSchema)

      case Schema.Lazy(schema0) =>
        schemaToJson(schema0())

      case transform: Schema.Transform[?, ?, ?] =>
        schemaToJson(transform.schema)

      case Schema.Fail(_, _) =>
        Json.Obj("type" -> Json.Str("null"))

      case map: Schema.Map[?, ?] =>
        Json.Obj(
          "type" -> Json.Str("object"),
          "additionalProperties" -> schemaToJson(map.valueSchema)
        )

      case set: Schema.Set[?] =>
        Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> schemaToJson(set.elementSchema),
          "uniqueItems" -> Json.Bool(true)
        )

      case either: Schema.Either[?, ?] =>
        // Either as oneOf
        Json.Obj(
          "oneOf" -> Json.Arr(
            schemaToJson(either.left),
            schemaToJson(either.right)
          )
        )

      case tuple: Schema.Tuple2[?, ?] =>
        Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> Json.Arr(
            schemaToJson(tuple.left),
            schemaToJson(tuple.right)
          ),
          "minItems" -> Json.Num(2),
          "maxItems" -> Json.Num(2)
        )

      case _ =>
        Json.Obj("type" -> Json.Str("object"))

  private def primitiveToJson(standardType: StandardType[?]): Json =
    val (typeName, format) = standardType match
      case StandardType.StringType         => ("string", None)
      case StandardType.IntType            => ("integer", None)
      case StandardType.LongType           => ("integer", Some("int64"))
      case StandardType.FloatType          => ("number", Some("float"))
      case StandardType.DoubleType         => ("number", Some("double"))
      case StandardType.BoolType           => ("boolean", None)
      case StandardType.ShortType          => ("integer", Some("int32"))
      case StandardType.ByteType           => ("integer", Some("int32"))
      case StandardType.CharType           => ("string", None)
      case StandardType.BigDecimalType     => ("number", None)
      case StandardType.BigIntegerType     => ("integer", None)
      case StandardType.UUIDType           => ("string", Some("uuid"))
      case StandardType.LocalDateType      => ("string", Some("date"))
      case StandardType.LocalTimeType      => ("string", Some("time"))
      case StandardType.LocalDateTimeType  => ("string", Some("date-time"))
      case StandardType.OffsetTimeType     => ("string", Some("time"))
      case StandardType.OffsetDateTimeType => ("string", Some("date-time"))
      case StandardType.ZonedDateTimeType  => ("string", Some("date-time"))
      case StandardType.InstantType        => ("string", Some("date-time"))
      case StandardType.DurationType       => ("string", Some("duration"))
      case StandardType.PeriodType         => ("string", None)
      case StandardType.YearType           => ("integer", None)
      case StandardType.YearMonthType      => ("string", None)
      case StandardType.MonthType          => ("string", None)
      case StandardType.MonthDayType       => ("string", None)
      case StandardType.DayOfWeekType      => ("string", None)
      case StandardType.ZoneIdType         => ("string", None)
      case StandardType.ZoneOffsetType     => ("string", None)
      case StandardType.UnitType           => ("null", None)
      case StandardType.BinaryType         => ("string", Some("byte"))
      case StandardType.CurrencyType       => ("string", None)

    format match
      case Some(fmt) =>
        Json.Obj("type" -> Json.Str(typeName), "format" -> Json.Str(fmt))
      case None =>
        Json.Obj("type" -> Json.Str(typeName))

  private def recordToJson(record: Schema.Record[?]): Json =
    val properties = record.fields.map { field =>
      val fieldSchema = field.schema match
        case Schema.Optional(inner, _) => schemaToJson(inner)
        case other                     => schemaToJson(other)
      field.name -> fieldSchema
    }

    val required = record.fields
      .filterNot(f => isOptional(f.schema))
      .map(_.name)

    val propsObj = Json.Obj(properties.map((k, v) => (k, v)): _*)
    val requiredArr = Json.Arr(required.map(Json.Str(_)): _*)

    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> propsObj,
      "required" -> requiredArr,
      "additionalProperties" -> Json.Bool(false)
    )

  private def isOptional(schema: Schema[?]): Boolean =
    schema match
      case Schema.Optional(_, _) => true
      case Schema.Lazy(s)        => isOptional(s())
      case _                     => false

  private def enumSchemaToJson(enumSchema: Schema.Enum[?]): Json =
    // For simple string enums
    val cases = enumSchema.cases.map(_.id)
    Json.Obj(
      "type" -> Json.Str("string"),
      "enum" -> Json.Arr(cases.map(Json.Str(_)): _*)
    )
