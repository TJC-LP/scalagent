package com.tjclp.scalagent.json

import zio.json.{JsonDecoder, JsonEncoder}

/**
 * Shared JSON codecs for opaque string wrappers.
 *
 * This avoids repetitive `asInstanceOf` casts when defining codecs for opaque
 * types backed by `String`.
 */
object OpaqueStringJsonCodec:
  inline def encoder[A](unwrap: A => String): JsonEncoder[A] =
    JsonEncoder.string.contramap(unwrap)

  inline def decoder[A](wrap: String => A): JsonDecoder[A] =
    JsonDecoder.string.map(wrap)

  inline def decoderOrFail[A](wrap: String => Either[String, A]): JsonDecoder[A] =
    JsonDecoder.string.mapOrFail(wrap)
