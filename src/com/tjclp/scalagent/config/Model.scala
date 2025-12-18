package com.tjclp.scalagent.config

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
  // Claude 4.5 Family (Current Generation - Recommended)
  // ============================================================================

  /** Claude Sonnet 4.5 - Recommended for most tasks, excellent for coding */
  case Sonnet4_5 extends Model("claude-sonnet-4-5-20250929")

  /** Claude Haiku 4.5 - Fast and efficient for simple tasks */
  case Haiku4_5 extends Model("claude-haiku-4-5-20251001")

  /** Claude Opus 4.5 - Most capable model for complex reasoning */
  case Opus4_5 extends Model("claude-opus-4-5-20251101")

  // ============================================================================
  // Claude 4.x Family
  // ============================================================================

  /** Claude Opus 4.1 */
  case Opus4_1 extends Model("claude-opus-4-1-20250805")

  /** Claude Opus 4 */
  case Opus4 extends Model("claude-opus-4-20250514")

  /** Claude Sonnet 4 - Balanced performance */
  case Sonnet4 extends Model("claude-sonnet-4-20250514")

  // ============================================================================
  // Claude 3.7 Family
  // ============================================================================

  /** Claude 3.7 Sonnet */
  case Sonnet3_7 extends Model("claude-3-7-sonnet-20250219")

  // ============================================================================
  // Claude 3.5 Family
  // ============================================================================

  /** Claude 3.5 Haiku - Fast and efficient */
  case Haiku3_5 extends Model("claude-3-5-haiku-20241022")

  // ============================================================================
  // Claude 3 Family (Legacy)
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
  import zio.json.*

  // JSON codecs for serialization
  given JsonEncoder[Model] = JsonEncoder[String].contramap(_.id)
  given JsonDecoder[Model] = JsonDecoder[String].map(fromId)

  /** Parse a model ID string to the corresponding enum value.
    *
    * Known model IDs are mapped to their enum variants. Unknown IDs become Custom.
    */
  def fromId(id: String): Model = id match
    // Claude 4.5 Family
    case "claude-sonnet-4-5-20250929"  => Sonnet4_5
    case "claude-haiku-4-5-20251001"   => Haiku4_5
    case "claude-opus-4-5-20251101"    => Opus4_5
    // Claude 4.x Family
    case "claude-opus-4-1-20250805"    => Opus4_1
    case "claude-opus-4-20250514"      => Opus4
    case "claude-sonnet-4-20250514"    => Sonnet4
    // Claude 3.7 Family
    case "claude-3-7-sonnet-20250219"  => Sonnet3_7
    // Claude 3.5 Family
    case "claude-3-5-haiku-20241022"   => Haiku3_5
    // Claude 3 Family (Legacy)
    case "claude-3-opus-latest"        => Opus3
    case "claude-3-sonnet-20240229"    => Sonnet3
    case "claude-3-haiku-20240307"     => Haiku3
    case other                         => Custom(other)

  /** Default model for Claude Agent SDK - uses current recommended model */
  val default: Model = Sonnet4_5

  // Convenience aliases (point to current generation)
  val opus: Model = Opus4_5
  val sonnet: Model = Sonnet4_5
  val haiku: Model = Haiku4_5
