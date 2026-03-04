package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.util.Try
import zio.json.*

/** Controls Claude's thinking/reasoning behavior.
  *
  * When set, takes precedence over the deprecated maxThinkingTokens.
  */
enum ThinkingConfig:
  /** Claude decides when and how much to think (Opus 4.6+). */
  case Adaptive

  /** Fixed thinking token budget (older models). */
  case Enabled(budgetTokens: Option[Int] = None)

  /** No extended thinking. */
  case Disabled

  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Any = this match
    case Adaptive =>
      js.Dynamic.literal(`type` = "adaptive").asInstanceOf[js.Any]
    case Enabled(budget) =>
      val obj = js.Dynamic.literal(`type` = "enabled")
      budget.foreach(b => obj.budgetTokens = b)
      obj.asInstanceOf[js.Any]
    case Disabled =>
      js.Dynamic.literal(`type` = "disabled").asInstanceOf[js.Any]

object ThinkingConfig:
  given JsonEncoder[ThinkingConfig] = JsonEncoder[zio.json.ast.Json].contramap { tc =>
    import zio.json.ast.Json.*
    tc match
      case Adaptive    => Obj("type" -> Str("adaptive"))
      case Enabled(Some(budget)) => Obj("type" -> Str("enabled"), "budgetTokens" -> Num(budget))
      case Enabled(None) => Obj("type" -> Str("enabled"))
      case Disabled    => Obj("type" -> Str("disabled"))
  }
  given JsonDecoder[ThinkingConfig] = JsonDecoder[zio.json.ast.Json].mapOrFail {
    case zio.json.ast.Json.Obj(fields) =>
      val map = fields.toMap
      map.get("type") match
        case Some(zio.json.ast.Json.Str("adaptive")) =>
          Right(Adaptive)
        case Some(zio.json.ast.Json.Str("enabled")) =>
          decodeBudgetTokens(map.get("budgetTokens")).map(Enabled.apply)
        case Some(zio.json.ast.Json.Str("disabled")) =>
          Right(Disabled)
        case Some(zio.json.ast.Json.Str(other)) =>
          Left(s"Unknown thinking type: $other")
        case _ =>
          Left("ThinkingConfig must contain a string 'type' field")
    case _ =>
      Left("ThinkingConfig must be a JSON object")
  }

  private def decodeBudgetTokens(raw: Option[zio.json.ast.Json]): Either[String, Option[Int]] =
    raw match
      case None | Some(zio.json.ast.Json.Null) =>
        Right(None)
      case Some(zio.json.ast.Json.Num(n)) =>
        Try(n.intValueExact()).toEither.left.map(_ => s"budgetTokens must be an integer, got: $n").map(Some(_))
      case Some(other) =>
        Left(s"budgetTokens must be a number or null, got: $other")

  /** Adaptive thinking (recommended for Opus 4.6+) */
  val adaptive: ThinkingConfig = Adaptive

  /** Enabled with a specific budget */
  def enabled(budgetTokens: Int): ThinkingConfig = Enabled(Some(budgetTokens))

  /** Enabled with no fixed budget (model decides) */
  val enabledDefault: ThinkingConfig = Enabled(None)

  /** Disabled */
  val disabled: ThinkingConfig = Disabled
