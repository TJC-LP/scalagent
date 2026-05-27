package com.tjclp.scalagent.a2a

import scala.scalajs.js

private[a2a] object A2APlatform:
  def randomUUID(): String =
    val crypto = js.Dynamic.global.selectDynamic("crypto")
    if js.typeOf(crypto) != "undefined" then
      val randomUUID = crypto.selectDynamic("randomUUID")
      if js.typeOf(randomUUID) == "function" then crypto.applyDynamic("randomUUID")().asInstanceOf[String]
      else fallbackRandomUUID()
    else fallbackRandomUUID()

  private def fallbackRandomUUID(): String =
    "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".map {
      case 'x' => hexDigit(16)
      case 'y' => (8 + js.Math.floor(js.Math.random() * 4).toInt).toHexString.head
      case c   => c
    }.mkString

  private def hexDigit(limit: Int): Char =
    js.Math.floor(js.Math.random() * limit).toInt.toHexString.head
end A2APlatform
