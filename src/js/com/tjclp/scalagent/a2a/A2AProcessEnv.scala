package com.tjclp.scalagent.a2a

import scala.scalajs.js

private[a2a] object A2AProcessEnv:
  def get(name: String): Option[String] =
    val process = js.Dynamic.global.selectDynamic("process")
    if js.isUndefined(process) || process == null then None
    else
      val env = process.selectDynamic("env")
      if js.isUndefined(env) || env == null then None
      else
        val value = env.selectDynamic(name)
        if js.isUndefined(value) || value == null then None
        else Some(value.asInstanceOf[String])

  def first(names: String*): Option[String] =
    names.iterator.flatMap(get).nextOption()
end A2AProcessEnv
