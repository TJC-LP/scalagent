package com.tjclp.scalagent.config

import scala.scalajs.js
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
      case Enabled(b)  => Obj("type" -> Str("enabled"), "budgetTokens" -> b.fold[zio.json.ast.Json](Null)(n => Num(n)))
      case Disabled    => Obj("type" -> Str("disabled"))
  }
  given JsonDecoder[ThinkingConfig] = DeriveJsonDecoder.gen[ThinkingConfig]

  /** Adaptive thinking (recommended for Opus 4.6+) */
  val adaptive: ThinkingConfig = Adaptive

  /** Enabled with a specific budget */
  def enabled(budgetTokens: Int): ThinkingConfig = Enabled(Some(budgetTokens))

  /** Enabled with default budget */
  val enabled: ThinkingConfig = Enabled(None)

  /** Disabled */
  val disabled: ThinkingConfig = Disabled
