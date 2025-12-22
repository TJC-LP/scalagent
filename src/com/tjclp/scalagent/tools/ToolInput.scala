package com.tjclp.scalagent.tools

import com.tjclp.scalagent.macros.ToolInputMacros

/** Type class describing tool input schemas.
  *
  * Provides a JSON Schema for validating tool inputs. Use ToolInput.derive for
  * case classes to keep schemas and inputs in sync.
  */
trait ToolInput[A]:
  def jsonSchema: JsonSchema

object ToolInput:

  /** Derive ToolInput automatically from a case class. */
  inline def derive[A]: ToolInput[A] =
    ToolInputMacros.derive[A]

  /** Enable `derives ToolInput` syntax. */
  inline def derived[A]: ToolInput[A] =
    derive[A]

  /** Create ToolInput from an explicit JsonSchema. */
  def fromJsonSchema[A](schema: JsonSchema): ToolInput[A] =
    new ToolInput[A]:
      val jsonSchema: JsonSchema = schema

  /** Backwards-friendly alias for fromJsonSchema. */
  def fromSchema[A](schema: JsonSchema): ToolInput[A] =
    fromJsonSchema(schema)

  /** Summon a ToolInput instance. */
  def apply[A](using ti: ToolInput[A]): ToolInput[A] = ti
