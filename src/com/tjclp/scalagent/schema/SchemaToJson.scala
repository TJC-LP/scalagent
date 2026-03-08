package com.tjclp.scalagent.schema

import zio.json.ast.Json
import zio.schema.*

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
        JsonSchemaAst.array(schemaToJson(seq.elementSchema))

      case enumSchema: Schema.Enum[?] =>
        enumSchemaToJson(enumSchema)

      case Schema.Lazy(schema0) =>
        schemaToJson(schema0())

      case transform: Schema.Transform[?, ?, ?] =>
        schemaToJson(transform.schema)

      case Schema.Fail(_, _) =>
        JsonSchemaAst.nullType

      case map: Schema.Map[?, ?] =>
        JsonSchemaAst.map(schemaToJson(map.valueSchema))

      case set: Schema.Set[?] =>
        JsonSchemaAst.array(
          items = schemaToJson(set.elementSchema),
          uniqueItems = true
        )

      case either: Schema.Either[?, ?] =>
        // Either as oneOf
        JsonSchemaAst.oneOf(
          List(
            schemaToJson(either.left),
            schemaToJson(either.right)
          )
        )

      case tuple: Schema.Tuple2[?, ?] =>
        JsonSchemaAst.tuple2(
          left = schemaToJson(tuple.left),
          right = schemaToJson(tuple.right)
        )

      case _ =>
        JsonSchemaAst.objectType

  private def primitiveToJson(standardType: StandardType[?]): Json =
    standardType match
      case StandardType.StringType         => JsonSchemaAst.string
      case StandardType.IntType            => JsonSchemaAst.integer
      case StandardType.LongType           => JsonSchemaAst.integerWithFormat("int64")
      case StandardType.FloatType          => JsonSchemaAst.number
      case StandardType.DoubleType         => JsonSchemaAst.number
      case StandardType.BoolType           => JsonSchemaAst.boolean
      case StandardType.ShortType          => JsonSchemaAst.integerWithFormat("int32")
      case StandardType.ByteType           => JsonSchemaAst.integerWithFormat("int32")
      case StandardType.CharType           => JsonSchemaAst.string
      case StandardType.BigDecimalType     => JsonSchemaAst.number
      case StandardType.BigIntegerType     => JsonSchemaAst.integer
      case StandardType.UUIDType           => JsonSchemaAst.withFormat("string", "uuid")
      case StandardType.LocalDateType      => JsonSchemaAst.withFormat("string", "date")
      case StandardType.LocalTimeType      => JsonSchemaAst.withFormat("string", "time")
      case StandardType.LocalDateTimeType  => JsonSchemaAst.withFormat("string", "date-time")
      case StandardType.OffsetTimeType     => JsonSchemaAst.withFormat("string", "time")
      case StandardType.OffsetDateTimeType => JsonSchemaAst.withFormat("string", "date-time")
      case StandardType.ZonedDateTimeType  => JsonSchemaAst.withFormat("string", "date-time")
      case StandardType.InstantType        => JsonSchemaAst.withFormat("string", "date-time")
      case StandardType.DurationType       => JsonSchemaAst.withFormat("string", "duration")
      case StandardType.PeriodType         => JsonSchemaAst.string
      case StandardType.YearType           => JsonSchemaAst.integer
      case StandardType.YearMonthType      => JsonSchemaAst.string
      case StandardType.MonthType          => JsonSchemaAst.string
      case StandardType.MonthDayType       => JsonSchemaAst.string
      case StandardType.DayOfWeekType      => JsonSchemaAst.string
      case StandardType.ZoneIdType         => JsonSchemaAst.string
      case StandardType.ZoneOffsetType     => JsonSchemaAst.string
      case StandardType.UnitType           => JsonSchemaAst.nullType
      case StandardType.BinaryType         => JsonSchemaAst.withFormat("string", "byte")
      case StandardType.CurrencyType       => JsonSchemaAst.string

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

    JsonSchemaAst.objectSchema(
      properties = properties.toList,
      required = required.toList
    )

  private def isOptional(schema: Schema[?]): Boolean =
    schema match
      case Schema.Optional(_, _) => true
      case Schema.Lazy(s)        => isOptional(s())
      case _                     => false

  private def enumSchemaToJson(enumSchema: Schema.Enum[?]): Json =
    // For simple string enums
    val cases = enumSchema.cases.map(_.id)
    JsonSchemaAst.enumOf(cases.toList)
