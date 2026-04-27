package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*

/**
 * Sandbox configuration for command execution isolation.
 *
 * When enabled, commands are executed in a sandboxed environment that restricts
 * filesystem and network access. This provides an additional security layer.
 *
 * Note: Filesystem and network restrictions are configured via permission rules,
 * not via these sandbox settings. These settings control sandbox behavior
 * (enabled, auto-allow, etc.), while actual access restrictions come from
 * your permission configuration.
 *
 * @param enabled
 *   Whether sandboxing is enabled
 * @param autoAllowBashIfSandboxed
 *   Auto-allow Bash commands when sandbox is active
 * @param allowUnsandboxedCommands
 *   Allow commands to run without sandboxing
 * @param network
 *   Network configuration for the sandbox
 * @param ignoreViolations
 *   Map of violation types to patterns to ignore
 * @param enableWeakerNestedSandbox
 *   Enable weaker nested sandbox for compatibility
 * @param excludedCommands
 *   Commands that are excluded from sandboxing
 * @param ripgrep
 *   Custom ripgrep configuration for sandbox
 * @param filesystem
 *   Filesystem configuration for sandbox (allowWrite, denyWrite, denyRead)
 * @param enableWeakerNetworkIsolation
 *   macOS only: Allow access to com.apple.trustd.agent in the sandbox.
 *   Needed for Go-based CLI tools (gh, gcloud, terraform, etc.) to verify TLS
 *   certificates when using httpProxyPort with a MITM proxy and custom CA.
 *   Reduces security. Default: false
 */
final case class SandboxSettings(
  enabled: Boolean = true,
  autoAllowBashIfSandboxed: Boolean = false,
  allowUnsandboxedCommands: Boolean = false,
  network: Option[SandboxNetworkConfig] = None,
  ignoreViolations: Map[String, List[String]] = Map.empty,
  enableWeakerNestedSandbox: Boolean = false,
  enableWeakerNetworkIsolation: Boolean = false,
  excludedCommands: List[String] = Nil,
  ripgrep: Option[RipgrepConfig] = None,
  filesystem: Option[SandboxFilesystemConfig] = None,
  failIfUnavailable: Boolean = false):
  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object =
    val obj = js.Dynamic.literal(enabled = enabled)

    if autoAllowBashIfSandboxed then obj.autoAllowBashIfSandboxed = true
    if allowUnsandboxedCommands then obj.allowUnsandboxedCommands = true

    network.foreach(n => obj.network = n.toRaw)

    if ignoreViolations.nonEmpty then
      obj.ignoreViolations = js.Dictionary(
        ignoreViolations.view.mapValues(_.toJSArray).toSeq*
      )

    if enableWeakerNestedSandbox then obj.enableWeakerNestedSandbox = true
    if enableWeakerNetworkIsolation then obj.enableWeakerNetworkIsolation = true

    if excludedCommands.nonEmpty then obj.excludedCommands = excludedCommands.toJSArray

    ripgrep.foreach(rg => obj.ripgrep = rg.toRaw)
    filesystem.foreach(fs => obj.filesystem = fs.toRaw)

    if failIfUnavailable then obj.failIfUnavailable = true

    obj.asInstanceOf[js.Object]
  end toRaw
end SandboxSettings

object SandboxSettings:
  given JsonDecoder[SandboxSettings] = DeriveJsonDecoder.gen[SandboxSettings]
  given JsonEncoder[SandboxSettings] = DeriveJsonEncoder.gen[SandboxSettings]

  /** Default sandbox settings (enabled) */
  val default: SandboxSettings = SandboxSettings()

  /** Sandbox with auto-allow Bash when sandboxed */
  def withAutoAllowBash: SandboxSettings =
    SandboxSettings(autoAllowBashIfSandboxed = true)

  /** Disabled sandbox (no isolation) */
  val disabled: SandboxSettings = SandboxSettings(enabled = false)

  extension (ss: SandboxSettings)
    def withNetwork(config: SandboxNetworkConfig): SandboxSettings =
      ss.copy(network = Some(config))

    def withAutoAllowBashIfSandboxed: SandboxSettings =
      ss.copy(autoAllowBashIfSandboxed = true)

    def withAllowUnsandboxedCommands: SandboxSettings =
      ss.copy(allowUnsandboxedCommands = true)

    def withWeakerNestedSandbox: SandboxSettings =
      ss.copy(enableWeakerNestedSandbox = true)

    /**
     * macOS only: Enable weaker network isolation to allow trustd access.
     * Required for Go-based CLI tools with MITM proxy and custom CA.
     */
    def withWeakerNetworkIsolation: SandboxSettings =
      ss.copy(enableWeakerNetworkIsolation = true)

    def withExcludedCommands(commands: String*): SandboxSettings =
      ss.copy(excludedCommands = commands.toList)

    def withRipgrep(config: RipgrepConfig): SandboxSettings =
      ss.copy(ripgrep = Some(config))

    def withIgnoreViolations(violations: Map[String, List[String]]): SandboxSettings =
      ss.copy(ignoreViolations = violations)

    def withFilesystem(config: SandboxFilesystemConfig): SandboxSettings =
      ss.copy(filesystem = Some(config))

    /** Fail if sandbox is unavailable on this platform. */
    def withFailIfUnavailable: SandboxSettings =
      ss.copy(failIfUnavailable = true)
  end extension
end SandboxSettings

/**
 * Network configuration for sandbox.
 *
 * @param allowedDomains
 *   Domains allowed for network access
 * @param allowManagedDomainsOnly
 *   Only allow managed domains
 * @param allowUnixSockets
 *   Specific Unix socket paths to allow
 * @param allowAllUnixSockets
 *   Allow all Unix sockets
 * @param allowLocalBinding
 *   Allow binding to local ports
 * @param httpProxyPort
 *   HTTP proxy port for sandbox
 * @param socksProxyPort
 *   SOCKS proxy port for sandbox
 */
final case class SandboxNetworkConfig(
  allowedDomains: List[String] = Nil,
  allowManagedDomainsOnly: Boolean = false,
  allowUnixSockets: List[String] = Nil,
  allowAllUnixSockets: Boolean = false,
  allowLocalBinding: Boolean = false,
  httpProxyPort: Option[Int] = None,
  socksProxyPort: Option[Int] = None):
  def toRaw: js.Object =
    val obj = js.Dynamic.literal()

    if allowedDomains.nonEmpty then obj.allowedDomains = allowedDomains.toJSArray

    if allowManagedDomainsOnly then obj.allowManagedDomainsOnly = true

    if allowUnixSockets.nonEmpty then obj.allowUnixSockets = allowUnixSockets.toJSArray

    if allowAllUnixSockets then obj.allowAllUnixSockets = true
    if allowLocalBinding then obj.allowLocalBinding = true

    httpProxyPort.foreach(p => obj.httpProxyPort = p)
    socksProxyPort.foreach(p => obj.socksProxyPort = p)

    obj.asInstanceOf[js.Object]
end SandboxNetworkConfig

object SandboxNetworkConfig:
  given JsonDecoder[SandboxNetworkConfig] = DeriveJsonDecoder.gen[SandboxNetworkConfig]
  given JsonEncoder[SandboxNetworkConfig] = DeriveJsonEncoder.gen[SandboxNetworkConfig]

  /** Network config with allowed domains */
  def withDomains(domains: String*): SandboxNetworkConfig =
    SandboxNetworkConfig(allowedDomains = domains.toList)

  /** Network config with local binding enabled */
  def withLocalBinding: SandboxNetworkConfig =
    SandboxNetworkConfig(allowLocalBinding = true)

  /** Network config with Docker socket access */
  def withDockerSocket: SandboxNetworkConfig =
    SandboxNetworkConfig(allowUnixSockets = List("/var/run/docker.sock"))

/**
 * Filesystem configuration for sandbox.
 *
 * @param allowWrite
 *   Paths/patterns to allow write access
 * @param denyWrite
 *   Paths/patterns to deny write access
 * @param denyRead
 *   Paths/patterns to deny read access
 * @param allowRead
 *   Paths to re-allow reading within denyRead regions (takes precedence over denyRead)
 * @param allowManagedReadPathsOnly
 *   When true, only allowRead paths from policySettings are used
 */
final case class SandboxFilesystemConfig(
  allowWrite: List[String] = Nil,
  denyWrite: List[String] = Nil,
  denyRead: List[String] = Nil,
  allowRead: List[String] = Nil,
  allowManagedReadPathsOnly: Boolean = false):
  def toRaw: js.Object =
    val obj = js.Dynamic.literal()
    if allowWrite.nonEmpty then obj.allowWrite = allowWrite.toJSArray
    if denyWrite.nonEmpty then obj.denyWrite = denyWrite.toJSArray
    if denyRead.nonEmpty then obj.denyRead = denyRead.toJSArray
    if allowRead.nonEmpty then obj.allowRead = allowRead.toJSArray
    if allowManagedReadPathsOnly then obj.allowManagedReadPathsOnly = true
    obj.asInstanceOf[js.Object]

object SandboxFilesystemConfig:
  given JsonDecoder[SandboxFilesystemConfig] = DeriveJsonDecoder.gen[SandboxFilesystemConfig]
  given JsonEncoder[SandboxFilesystemConfig] = DeriveJsonEncoder.gen[SandboxFilesystemConfig]

/**
 * Ripgrep configuration for sandbox.
 *
 * @param command
 *   Path to ripgrep command
 * @param args
 *   Additional arguments for ripgrep
 */
final case class RipgrepConfig(
  command: String,
  args: List[String] = Nil):
  def toRaw: js.Object =
    val obj = js.Dynamic.literal(command = command)
    if args.nonEmpty then obj.args = args.toJSArray
    obj.asInstanceOf[js.Object]

object RipgrepConfig:
  given JsonDecoder[RipgrepConfig] = DeriveJsonDecoder.gen[RipgrepConfig]
  given JsonEncoder[RipgrepConfig] = DeriveJsonEncoder.gen[RipgrepConfig]

  /** Create ripgrep config with custom command path */
  def apply(command: String): RipgrepConfig = new RipgrepConfig(command)

  /** Create ripgrep config with command and args */
  def withArgs(command: String, args: String*): RipgrepConfig =
    RipgrepConfig(command, args.toList)
