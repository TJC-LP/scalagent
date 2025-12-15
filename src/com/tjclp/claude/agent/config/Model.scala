package com.tjclp.claude.agent.config

/** Type-safe Claude model identifiers.
  *
  * Instead of error-prone string literals, use this enum for compile-time safety:
  *
  * Example:
  * {{{
  * // Before (error-prone):
  * options.withModel("claude-sonnet-4-20250514")
  *
  * // After (type-safe):
  * options.withModel(Model.Sonnet4)
  * }}}
  */
enum Model(val id: String):

  // ============================================================================
  // Claude 4 Family
  // ============================================================================

  /** Claude Opus 4.5 - Most capable model */
  case Opus4_5 extends Model("claude-opus-4-5-20251101")

  /** Claude Sonnet 4 - Balanced performance */
  case Sonnet4 extends Model("claude-sonnet-4-20250514")

  // ============================================================================
  // Claude 3.5 Family
  // ============================================================================

  /** Claude 3.5 Sonnet (latest) */
  case Sonnet3_5 extends Model("claude-3-5-sonnet-latest")

  /** Claude 3.5 Haiku (latest) - Fast and efficient */
  case Haiku3_5 extends Model("claude-3-5-haiku-latest")

  // ============================================================================
  // Claude 3 Family
  // ============================================================================

  /** Claude 3 Opus - Previous generation flagship */
  case Opus3 extends Model("claude-3-opus-latest")

  /** Claude 3 Sonnet - Previous generation balanced */
  case Sonnet3 extends Model("claude-3-sonnet-20240229")

  /** Claude 3 Haiku - Previous generation fast */
  case Haiku3 extends Model("claude-3-haiku-20240307")

  // ============================================================================
  // Custom Models
  // ============================================================================

  /** Custom model ID for new or preview models */
  case Custom(override val id: String) extends Model(id)

object Model:
  import zio.json._

  // JSON codecs for serialization
  given JsonEncoder[Model] = JsonEncoder[String].contramap(_.id)
  given JsonDecoder[Model] = JsonDecoder[String].map(fromId)

  /** Parse a model ID string to the corresponding enum value.
    *
    * Known model IDs are mapped to their enum variants. Unknown IDs become Custom.
    */
  def fromId(id: String): Model = id match
    case "claude-opus-4-5-20251101"    => Opus4_5
    case "claude-sonnet-4-20250514"    => Sonnet4
    case "claude-3-5-sonnet-latest"    => Sonnet3_5
    case "claude-3-5-haiku-latest"     => Haiku3_5
    case "claude-3-opus-latest"        => Opus3
    case "claude-3-sonnet-20240229"    => Sonnet3
    case "claude-3-haiku-20240307"     => Haiku3
    case other                         => Custom(other)

  /** Default model for Claude Agent SDK */
  val default: Model = Sonnet4

  // Convenience aliases
  val opus: Model = Opus4_5
  val sonnet: Model = Sonnet4
  val haiku: Model = Haiku3_5
