package com.tjclp.scalagent.schema

import munit.FunSuite
import zio.json.ast.Json
import zio.schema._

class SchemaToJsonSpec extends FunSuite:

  // Helper to extract JSON value by path
  def getField(json: Json, key: String): Option[Json] =
    json match
      case obj: Json.Obj => obj.get(key)
      case _             => None

  def getString(json: Json, key: String): Option[String] =
    getField(json, key).flatMap {
      case Json.Str(s) => Some(s)
      case _           => None
    }

  def getBool(json: Json, key: String): Option[Boolean] =
    getField(json, key).flatMap {
      case Json.Bool(b) => Some(b)
      case _            => None
    }

  def getArr(json: Json, key: String): Option[List[Json]] =
    getField(json, key).flatMap {
      case Json.Arr(arr) => Some(arr.toList)
      case _             => None
    }

  // ============================================
  // Primitive Types
  // ============================================

  test("String schema converts to JSON Schema string"):
    val schema = Schema[String]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("string"))

  test("Int schema converts to JSON Schema integer"):
    val schema = Schema[Int]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("integer"))

  test("Long schema converts to JSON Schema integer with int64 format"):
    val schema = Schema[Long]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("integer"))
    assertEquals(getString(json, "format"), Some("int64"))

  test("Double schema converts to JSON Schema number with double format"):
    val schema = Schema[Double]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("number"))
    assertEquals(getString(json, "format"), Some("double"))

  test("Float schema converts to JSON Schema number with float format"):
    val schema = Schema[Float]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("number"))
    assertEquals(getString(json, "format"), Some("float"))

  test("Boolean schema converts to JSON Schema boolean"):
    val schema = Schema[Boolean]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("boolean"))

  // ============================================
  // Date/Time Types
  // ============================================

  test("UUID schema converts to string with uuid format"):
    val schema = Schema[java.util.UUID]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("string"))
    assertEquals(getString(json, "format"), Some("uuid"))

  // ============================================
  // Case Classes (Records)
  // ============================================

  case class SimpleRecord(name: String, age: Int)
  object SimpleRecord:
    given Schema[SimpleRecord] = DeriveSchema.gen[SimpleRecord]

  test("Case class converts to object schema"):
    val schema = Schema[SimpleRecord]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("object"))

  test("Case class has properties object"):
    val schema = Schema[SimpleRecord]
    val json = SchemaToJson.convert(schema)
    val props = getField(json, "properties")
    assert(props.isDefined)
    props.foreach { p =>
      assert(getField(p, "name").isDefined)
      assert(getField(p, "age").isDefined)
    }

  test("Case class has required array with all fields"):
    val schema = Schema[SimpleRecord]
    val json = SchemaToJson.convert(schema)
    val required = getArr(json, "required")
    assert(required.isDefined)
    required.foreach { arr =>
      val names = arr.collect { case Json.Str(s) => s }
      assert(names.contains("name"))
      assert(names.contains("age"))
    }

  test("Case class has additionalProperties false"):
    val schema = Schema[SimpleRecord]
    val json = SchemaToJson.convert(schema)
    assertEquals(getBool(json, "additionalProperties"), Some(false))

  // ============================================
  // Optional Fields
  // ============================================

  case class WithOptional(required: String, optional: Option[Int])
  object WithOptional:
    given Schema[WithOptional] = DeriveSchema.gen[WithOptional]

  test("Optional fields are not in required array"):
    val schema = Schema[WithOptional]
    val json = SchemaToJson.convert(schema)
    val required = getArr(json, "required")
    assert(required.isDefined)
    required.foreach { arr =>
      val names = arr.collect { case Json.Str(s) => s }
      assert(names.contains("required"))
      assert(!names.contains("optional"))
    }

  test("Optional field schema is the inner type"):
    val schema = Schema[WithOptional]
    val json = SchemaToJson.convert(schema)
    val props = getField(json, "properties")
    props.foreach { p =>
      val optSchema = getField(p, "optional")
      optSchema.foreach { os =>
        // Should be integer, not null
        assertEquals(getString(os, "type"), Some("integer"))
      }
    }

  // ============================================
  // Collections
  // ============================================

  test("List converts to array schema"):
    val schema = Schema[List[String]]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("array"))

  test("List has items schema"):
    val schema = Schema[List[Int]]
    val json = SchemaToJson.convert(schema)
    val items = getField(json, "items")
    assert(items.isDefined)
    items.foreach { i =>
      assertEquals(getString(i, "type"), Some("integer"))
    }

  test("Set converts to array with uniqueItems"):
    val schema = Schema[Set[String]]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("array"))
    assertEquals(getBool(json, "uniqueItems"), Some(true))

  test("Map converts to object with additionalProperties"):
    val schema = Schema[Map[String, Int]]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("object"))
    val addProps = getField(json, "additionalProperties")
    assert(addProps.isDefined)
    addProps.foreach { ap =>
      assertEquals(getString(ap, "type"), Some("integer"))
    }

  // ============================================
  // Enums
  // ============================================

  enum Color:
    case Red, Green, Blue

  object Color:
    given Schema[Color] = DeriveSchema.gen[Color]

  test("Enum converts to string enum"):
    val schema = Schema[Color]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("string"))

  test("Enum has enum array with case names"):
    val schema = Schema[Color]
    val json = SchemaToJson.convert(schema)
    val enumVals = getArr(json, "enum")
    assert(enumVals.isDefined)
    enumVals.foreach { arr =>
      val names = arr.collect { case Json.Str(s) => s }
      assert(names.contains("Red"))
      assert(names.contains("Green"))
      assert(names.contains("Blue"))
    }

  // ============================================
  // Nested Structures
  // ============================================

  case class Address(street: String, city: String)
  case class Person(name: String, address: Address)

  object Address:
    given Schema[Address] = DeriveSchema.gen[Address]
  object Person:
    given Schema[Person] = DeriveSchema.gen[Person]

  test("Nested case class has nested object schema"):
    val schema = Schema[Person]
    val json = SchemaToJson.convert(schema)
    val props = getField(json, "properties")
    props.foreach { p =>
      val addressSchema = getField(p, "address")
      assert(addressSchema.isDefined)
      addressSchema.foreach { as =>
        assertEquals(getString(as, "type"), Some("object"))
        // Should have nested properties
        val addrProps = getField(as, "properties")
        assert(addrProps.isDefined)
      }
    }

  // ============================================
  // Tuples
  // ============================================

  test("Tuple2 converts to array with fixed items"):
    val schema = Schema[(String, Int)]
    val json = SchemaToJson.convert(schema)
    assertEquals(getString(json, "type"), Some("array"))
    val items = getField(json, "items")
    items.foreach {
      case Json.Arr(arr) =>
        assertEquals(arr.size, 2)
      case other => fail(s"Expected array, got $other")
    }

  // ============================================
  // Either
  // ============================================

  test("Either converts to oneOf"):
    val schema = Schema[Either[String, Int]]
    val json = SchemaToJson.convert(schema)
    val oneOf = getArr(json, "oneOf")
    assert(oneOf.isDefined)
    oneOf.foreach { arr =>
      assertEquals(arr.size, 2)
    }

  // ============================================
  // Complex Real-world Example
  // ============================================

  case class AnalysisResult(
      summary: String,
      issues: List[Issue],
      score: Int,
      metadata: Option[Map[String, String]]
  )

  case class Issue(
      severity: Severity,
      message: String,
      line: Option[Int]
  )

  enum Severity:
    case Low, Medium, High, Critical

  object Severity:
    given Schema[Severity] = DeriveSchema.gen[Severity]
  object Issue:
    given Schema[Issue] = DeriveSchema.gen[Issue]
  object AnalysisResult:
    given Schema[AnalysisResult] = DeriveSchema.gen[AnalysisResult]

  test("Complex nested structure converts correctly"):
    val schema = Schema[AnalysisResult]
    val json = SchemaToJson.convert(schema)

    // Top level is object
    assertEquals(getString(json, "type"), Some("object"))

    // Has required fields
    val required = getArr(json, "required")
    required.foreach { arr =>
      val names = arr.collect { case Json.Str(s) => s }
      assert(names.contains("summary"))
      assert(names.contains("issues"))
      assert(names.contains("score"))
      assert(!names.contains("metadata")) // Optional
    }

    // Issues is array
    val props = getField(json, "properties")
    props.foreach { p =>
      val issuesSchema = getField(p, "issues")
      issuesSchema.foreach { is =>
        assertEquals(getString(is, "type"), Some("array"))
      }
    }
