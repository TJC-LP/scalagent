package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*

/** Sandbox configuration for running agents in isolated environments.
  *
  * @param enabled
  *   Whether sandboxing is enabled
  * @param networkAccess
  *   Whether the sandbox allows network access
  * @param allowedHosts
  *   List of hosts allowed for network access (if networkAccess is true)
  * @param writableDirectories
  *   Directories that can be written to within the sandbox
  * @param readOnlyDirectories
  *   Directories that are read-only in the sandbox
  */
final case class SandboxSettings(
    enabled: Boolean = true,
    networkAccess: Option[Boolean] = None,
    allowedHosts: List[String] = Nil,
    writableDirectories: List[String] = Nil,
    readOnlyDirectories: List[String] = Nil
):
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object =
    val obj = js.Dynamic.literal(enabled = enabled)

    networkAccess.foreach(na => obj.networkAccess = na)

    if allowedHosts.nonEmpty then
      obj.allowedHosts = allowedHosts.toJSArray

    if writableDirectories.nonEmpty then
      obj.writableDirectories = writableDirectories.toJSArray

    if readOnlyDirectories.nonEmpty then
      obj.readOnlyDirectories = readOnlyDirectories.toJSArray

    obj.asInstanceOf[js.Object]

object SandboxSettings:
  given JsonDecoder[SandboxSettings] = DeriveJsonDecoder.gen[SandboxSettings]
  given JsonEncoder[SandboxSettings] = DeriveJsonEncoder.gen[SandboxSettings]

  /** Default sandbox settings (enabled with no network access) */
  val default: SandboxSettings = SandboxSettings()

  /** Sandbox settings with network access enabled */
  def withNetwork(allowedHosts: String*): SandboxSettings =
    SandboxSettings(
      enabled = true,
      networkAccess = Some(true),
      allowedHosts = allowedHosts.toList
    )

  /** Disabled sandbox (no isolation) */
  val disabled: SandboxSettings = SandboxSettings(enabled = false)
