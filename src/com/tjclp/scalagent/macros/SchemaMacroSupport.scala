package com.tjclp.scalagent.macros

import scala.quoted.Quotes

/** Shared helpers for schema derivation macros.
  *
  * Centralizes common reflection logic used by ToolInput and StructuredOutput
  * derivation to keep both macro implementations aligned.
  */
object SchemaMacroSupport:
  private val OptionTypeFullName = "scala.Option"
  private val ListTypeFullName = "scala.collection.immutable.List"
  private val VectorTypeFullName = "scala.collection.immutable.Vector"
  private val SetTypeFullName = "scala.collection.immutable.Set"
  private val DescriptionAnnotationFullName = "com.tjclp.scalagent.macros.description"

  def splitOptional(using q: Quotes)(
      tpe: q.reflect.TypeRepr
  ): (Boolean, q.reflect.TypeRepr) =
    import q.reflect.*
    tpe match
      case AppliedType(tycon, List(inner)) if tycon.typeSymbol.fullName == OptionTypeFullName =>
        (true, inner)
      case other =>
        (false, other)

  def caseClassFieldInfos(using q: Quotes)(
      ownerType: q.reflect.TypeRepr
  ): List[(String, q.reflect.TypeRepr, Boolean, Option[String])] =
    import q.reflect.*

    ownerType.typeSymbol.caseFields.map { field =>
      val fieldName = field.name
      val fieldType = ownerType.memberType(field)
      val (isOptional, innerType) = splitOptional(fieldType)
      val descriptionOpt = extractDescription(fieldName, field.annotations)
      (fieldName, innerType, isOptional, descriptionOpt)
    }

  def isListLike(using q: Quotes)(tycon: q.reflect.TypeRepr): Boolean =
    val fullName = tycon.typeSymbol.fullName
    fullName == ListTypeFullName ||
    fullName == VectorTypeFullName ||
    fullName.contains("Seq") ||
    fullName == SetTypeFullName

  def isSetLike(using q: Quotes)(tycon: q.reflect.TypeRepr): Boolean =
    tycon.typeSymbol.fullName == SetTypeFullName

  def isMapLike(using q: Quotes)(tycon: q.reflect.TypeRepr): Boolean =
    tycon.typeSymbol.fullName.contains("Map")

  def enumCaseNames(using q: Quotes)(enumType: q.reflect.TypeRepr): List[String] =
    enumType.typeSymbol.children.map(_.name)

  private def extractDescription(using q: Quotes)(
      fieldName: String,
      annotations: List[q.reflect.Term]
  ): Option[String] =
    import q.reflect.*
    annotations.collectFirst {
      case ann if ann.tpe.typeSymbol.fullName == DescriptionAnnotationFullName =>
        ann match
          case Apply(_, List(Literal(StringConstant(text)))) => text
          case _ =>
            report.warning(s"Could not extract description text for field $fieldName")
            ""
    }
