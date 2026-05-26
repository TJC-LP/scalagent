package com.tjclp.scalagent.json

import zio.json.{JsonDecoder, JsonEncoder}

/**
 * Shared JSON codecs for string-backed enums.
 *
 * This keeps enum codec declarations uniform across the codebase and avoids
 * repeating `JsonEncoder.string.contramap` / `JsonDecoder.string.map`.
 */
object StringEnumJsonCodec:
  inline def encoder[A](toRaw: A => String): JsonEncoder[A] =
    JsonEncoder.string.contramap(toRaw)

  inline def decoder[A](fromString: String => A): JsonDecoder[A] =
    JsonDecoder.string.map(fromString)

  inline def decoderOrFail[A](fromString: String => Either[String, A]): JsonDecoder[A] =
    JsonDecoder.string.mapOrFail(fromString)
