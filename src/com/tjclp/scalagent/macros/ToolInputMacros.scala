package com.tjclp.scalagent.macros

import scala.quoted.*
import com.tjclp.scalagent.tools.{JsonSchema, ToolInput}

/** Macros for deriving ToolInput instances from case classes. */
object ToolInputMacros:

  /** Derive a ToolInput instance for a case class. */
  inline def derive[A]: ToolInput[A] =
    ${ deriveImpl[A] }

  private def deriveImpl[A: Type](using Quotes): Expr[ToolInput[A]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[A]
    val sym = tpe.typeSymbol

    if !sym.flags.is(Flags.Case) then
      report.errorAndAbort(s"ToolInput.derive requires a case class, but ${sym.fullName} is not a case class")

    val fields = sym.caseFields

    // (name, innerType, isOptional, description)
    val fieldInfos = fields.map { field =>
      val fieldName = field.name
      val fieldType = tpe.memberType(field)

      val (isOptional, innerType) = fieldType match
        case AppliedType(tycon, List(inner)) if tycon.typeSymbol.fullName == "scala.Option" =>
          (true, inner)
        case other =>
          (false, other)

      val descriptionOpt = field.annotations.collectFirst {
        case ann if ann.tpe.typeSymbol.fullName == "com.tjclp.scalagent.macros.description" =>
          ann match
            case Apply(_, List(Literal(StringConstant(text)))) => text
            case _ =>
              report.warning(s"Could not extract description text for field $fieldName")
              ""
      }

      (fieldName, innerType, isOptional, descriptionOpt)
    }

    val schemaExpr = generateSchemaExpr(fieldInfos)

    '{
      new ToolInput[A]:
        val jsonSchema: JsonSchema = $schemaExpr
    }

  private def generateSchemaExpr(using Quotes)(
      fieldInfos: List[(String, quotes.reflect.TypeRepr, Boolean, Option[String])]
  ): Expr[JsonSchema] =
    import quotes.reflect.*

    val propertiesExprs: List[Expr[(String, JsonSchema)]] = fieldInfos.map { case (name, tpe, _, descOpt) =>
      val nameExpr = Expr(name)
      val baseSchemaExpr = typeToSchemaExpr(tpe)
      val schemaExpr = descOpt match
        case Some(desc) =>
          val descExpr = Expr(desc)
          '{ JsonSchema.describe($baseSchemaExpr, $descExpr) }
        case None =>
          baseSchemaExpr
      '{ ($nameExpr, $schemaExpr) }
    }

    val requiredExprs: List[Expr[String]] = fieldInfos.collect {
      case (name, _, false, _) => Expr(name)
    }

    val propertiesListExpr = Expr.ofList(propertiesExprs)
    val requiredListExpr = Expr.ofList(requiredExprs)

    '{
      val props = $propertiesListExpr
      val builder = JsonSchema.obj(props*)
      val withRequired = builder.required($requiredListExpr*)
      withRequired.build
    }

  private def typeToSchemaExpr(using Quotes)(
      tpe: quotes.reflect.TypeRepr
  ): Expr[JsonSchema] =
    import quotes.reflect.*

    tpe.dealias match
      case t if t =:= TypeRepr.of[String] =>
        '{ JsonSchema.string }

      case t if t =:= TypeRepr.of[Int] || t =:= TypeRepr.of[Long] =>
        '{ JsonSchema.int }

      case t if t =:= TypeRepr.of[Double] || t =:= TypeRepr.of[Float] =>
        '{ JsonSchema.number }

      case t if t =:= TypeRepr.of[Boolean] =>
        '{ JsonSchema.boolean }

      case AppliedType(tycon, List(inner)) if tycon.typeSymbol.fullName == "scala.Option" =>
        typeToSchemaExpr(inner)

      case AppliedType(tycon, List(elemType))
          if tycon.typeSymbol.fullName == "scala.collection.immutable.List" ||
            tycon.typeSymbol.fullName == "scala.collection.immutable.Vector" ||
            tycon.typeSymbol.fullName.contains("Seq") ||
            tycon.typeSymbol.fullName == "scala.collection.immutable.Set" =>
        val elemSchemaExpr = typeToSchemaExpr(elemType)
        '{ JsonSchema.array($elemSchemaExpr) }

      case AppliedType(tycon, List(_, _)) if tycon.typeSymbol.fullName.contains("Map") =>
        '{ JsonSchema.obj().additionalProperties.build }

      case t if t.typeSymbol.flags.is(Flags.Enum) =>
        val enumCases = t.typeSymbol.children.map(_.name)
        val casesExpr = Expr(enumCases)
        '{ JsonSchema.enumOf($casesExpr*) }

      case t if t.typeSymbol.flags.is(Flags.Case) =>
        val nestedFields = t.typeSymbol.caseFields.map { field =>
          val fieldName = field.name
          val fieldType = t.memberType(field)

          val (isOptional, innerType) = fieldType match
            case AppliedType(tycon, List(inner)) if tycon.typeSymbol.fullName == "scala.Option" =>
              (true, inner)
            case other =>
              (false, other)

          val descOpt = field.annotations.collectFirst {
            case ann if ann.tpe.typeSymbol.fullName == "com.tjclp.scalagent.macros.description" =>
              ann match
                case Apply(_, List(Literal(StringConstant(text)))) => text
                case _                                            => ""
          }

          (fieldName, innerType, isOptional, descOpt)
        }
        generateSchemaExpr(nestedFields.toList)

      case other =>
        report.warning(s"Unknown type ${other.show}, using object schema")
        '{ JsonSchema.obj().build }
