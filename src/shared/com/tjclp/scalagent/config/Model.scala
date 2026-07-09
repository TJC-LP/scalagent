package com.tjclp.scalagent.config

import com.tjclp.scalagent.json.StringEnumJsonCodec

/**
 * Type-safe Claude model identifiers.
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
  // Claude 5 Family (Latest Generation)
  // ============================================================================

  /** Claude Fable 5 - Most capable widely released model for demanding reasoning and long-horizon agentic work */
  case Fable5 extends Model("claude-fable-5")

  /** Claude Sonnet 5 - Best speed/intelligence balance; near-Opus quality on coding and agentic work */
  case Sonnet5 extends Model("claude-sonnet-5")

  // ============================================================================
  // Claude 4.8 Family
  // ============================================================================

  /** Claude Opus 4.8 - Most capable Opus-tier model for complex reasoning and agentic coding */
  case Opus4_8 extends Model("claude-opus-4-8")

  // ============================================================================
  // Claude 4.7 Family
  // ============================================================================

  /** Claude Opus 4.7 - High-capability model; supports adaptive thinking and effort controls */
  case Opus4_7 extends Model("claude-opus-4-7")

  // ============================================================================
  // Claude 4.6 Family
  // ============================================================================

  /** Claude Opus 4.6 - adaptive thinking */
  case Opus4_6 extends Model("claude-opus-4-6")

  /** Claude Sonnet 4.6 - Excellent balance of speed and capability */
  case Sonnet4_6 extends Model("claude-sonnet-4-6")

  // ============================================================================
  // Claude 4.5 Family
  // ============================================================================

  /** Claude Sonnet 4.5 - Excellent for coding */
  case Sonnet4_5 extends Model("claude-sonnet-4-5-20250929")

  /** Claude Haiku 4.5 - Fast and efficient for simple tasks */
  case Haiku4_5 extends Model("claude-haiku-4-5-20251001")

  /** Claude Opus 4.5 - Capable model for complex reasoning */
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
end Model

object Model:
  import zio.json.*

  // JSON codecs for serialization
  given JsonEncoder[Model] = StringEnumJsonCodec.encoder(_.id)
  given JsonDecoder[Model] = StringEnumJsonCodec.decoder(fromId)

  /**
   * Parse a model ID string to the corresponding enum value.
   *
   * Known model IDs are mapped to their enum variants. Unknown IDs become Custom.
   */
  def fromId(id: String): Model = id match
    // Claude 5 Family
    case "claude-fable-5"  => Fable5
    case "claude-sonnet-5" => Sonnet5
    // Claude 4.8 Family
    case "claude-opus-4-8" => Opus4_8
    // Claude 4.7 Family
    case "claude-opus-4-7" => Opus4_7
    // Claude 4.6 Family
    case "claude-opus-4-6"   => Opus4_6
    case "claude-sonnet-4-6" => Sonnet4_6
    // Claude 4.5 Family
    case "claude-sonnet-4-5-20250929" => Sonnet4_5
    case "claude-haiku-4-5-20251001"  => Haiku4_5
    case "claude-opus-4-5-20251101"   => Opus4_5
    // Claude 4.x Family
    case "claude-opus-4-1-20250805" => Opus4_1
    case "claude-opus-4-20250514"   => Opus4
    case "claude-sonnet-4-20250514" => Sonnet4
    // Claude 3.7 Family
    case "claude-3-7-sonnet-20250219" => Sonnet3_7
    // Claude 3.5 Family
    case "claude-3-5-haiku-20241022" => Haiku3_5
    // Claude 3 Family (Legacy)
    case "claude-3-opus-latest"     => Opus3
    case "claude-3-sonnet-20240229" => Sonnet3
    case "claude-3-haiku-20240307"  => Haiku3
    case other                      => Custom(other)

  /** Default model for Claude Agent SDK - balanced for general-purpose agent use */
  val default: Model = Sonnet5

  // Convenience aliases (point to current generation)
  val fable: Model  = Fable5
  val opus: Model   = Opus4_8
  val sonnet: Model = Sonnet5
  val haiku: Model  = Haiku4_5
end Model
