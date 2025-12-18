package com.tjclp.scalagent.macros

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*

/** Typeclass for converting JavaScript values to Scala types.
  *
  * Used by macro-generated handlers to convert incoming tool arguments from JS objects to typed Scala values.
  */
trait ParamConverter[A]:
  def convert(value: js.Any): Either[String, A]

object ParamConverter:
  def apply[A](using pc: ParamConverter[A]): ParamConverter[A] = pc

  // Primitives

  given ParamConverter[String] with
    def convert(value: js.Any): Either[String, String] =
      if value == null || js.isUndefined(value) then Left("Expected string, got null/undefined")
      else Right(value.asInstanceOf[String])

  given ParamConverter[Int] with
    def convert(value: js.Any): Either[String, Int] =
      if value == null || js.isUndefined(value) then Left("Expected int, got null/undefined")
      else Right(value.asInstanceOf[Double].toInt)

  given ParamConverter[Long] with
    def convert(value: js.Any): Either[String, Long] =
      if value == null || js.isUndefined(value) then Left("Expected long, got null/undefined")
      else Right(value.asInstanceOf[Double].toLong)

  given ParamConverter[Double] with
    def convert(value: js.Any): Either[String, Double] =
      if value == null || js.isUndefined(value) then Left("Expected double, got null/undefined")
      else Right(value.asInstanceOf[Double])

  given ParamConverter[Float] with
    def convert(value: js.Any): Either[String, Float] =
      if value == null || js.isUndefined(value) then Left("Expected float, got null/undefined")
      else Right(value.asInstanceOf[Double].toFloat)

  given ParamConverter[Boolean] with
    def convert(value: js.Any): Either[String, Boolean] =
      if value == null || js.isUndefined(value) then Left("Expected boolean, got null/undefined")
      else Right(value.asInstanceOf[Boolean])

  // Option - handles null/undefined as None
  given [A](using inner: ParamConverter[A]): ParamConverter[Option[A]] with
    def convert(value: js.Any): Either[String, Option[A]] =
      if value == null || js.isUndefined(value) then Right(None)
      else inner.convert(value).map(Some(_))

  // List from JS Array
  given [A](using inner: ParamConverter[A]): ParamConverter[List[A]] with
    def convert(value: js.Any): Either[String, List[A]] =
      if value == null || js.isUndefined(value) then Left("Expected array, got null/undefined")
      else
        val arr = value.asInstanceOf[js.Array[js.Any]]
        arr.toList.zipWithIndex.foldLeft[Either[String, List[A]]](Right(Nil)) {
          case (Right(acc), (elem, idx)) =>
            inner.convert(elem) match
              case Right(v)  => Right(acc :+ v)
              case Left(err) => Left(s"Error at index $idx: $err")
          case (left, _) => left
        }

  // Vector from JS Array
  given [A](using inner: ParamConverter[A]): ParamConverter[Vector[A]] with
    def convert(value: js.Any): Either[String, Vector[A]] =
      summon[ParamConverter[List[A]]].convert(value).map(_.toVector)

  // Map from JS object
  given [V](using inner: ParamConverter[V]): ParamConverter[Map[String, V]] with
    def convert(value: js.Any): Either[String, Map[String, V]] =
      if value == null || js.isUndefined(value) then Left("Expected object, got null/undefined")
      else
        val dict = value.asInstanceOf[js.Dictionary[js.Any]]
        dict.toMap.foldLeft[Either[String, Map[String, V]]](Right(Map.empty)) {
          case (Right(acc), (k, v)) =>
            inner.convert(v) match
              case Right(converted) => Right(acc + (k -> converted))
              case Left(err)        => Left(s"Error at key '$k': $err")
          case (left, _) => left
        }

  // Fallback for case classes via zio-json
  // This requires the type to have a JsonDecoder instance
  given jsonDecoder[A](using decoder: JsonDecoder[A]): ParamConverter[A] with
    def convert(value: js.Any): Either[String, A] =
      if value == null || js.isUndefined(value) then Left("Expected object, got null/undefined")
      else
        val jsonStr = js.JSON.stringify(value)
        jsonStr.fromJson[A].left.map(err => s"JSON decode error: $err")

  /** Helper to convert a JS object's properties to typed values */
  def convertParams(
      args: js.Any,
      converters: List[(String, ParamConverter[?])]
  ): Either[String, List[Any]] =
    val obj = args.asInstanceOf[js.Dynamic]
    converters.foldLeft[Either[String, List[Any]]](Right(Nil)) {
      case (Right(acc), (name, converter)) =>
        val value = obj.selectDynamic(name).asInstanceOf[js.Any]
        converter.convert(value) match
          case Right(v)  => Right(acc :+ v)
          case Left(err) => Left(s"Parameter '$name': $err")
      case (left, _) => left
    }
