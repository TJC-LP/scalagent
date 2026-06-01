package com.tjclp.scalagent.a2a

import scala.collection.mutable
import scala.util.Try

import zio.*
import zio.stream.*

import com.tjclp.scalagent.config.*

/** A2A Server that exposes an agent via the native A2A v1 protocol. */
trait A2AServer:
  /** Start the server. */
  def start: Task[Unit]

  /** Stop the server. */
  def stop: Task[Unit]

  /** Get the agent card for this server. */
  def agentCard: AgentCard

  /** Get the server URL. */
  def url: String
end A2AServer

/** Minimal publisher exposed to server test overrides. */
trait A2AEventPublisher:
  def publish(event: A2AResponse.StreamEvent): UIO[Unit]
  def finish: UIO[Unit]
end A2AEventPublisher

/**
 * Durable per-task stream event store used to replay history after process restart.
 *
 * Implementations should treat persistence as best-effort — the server wraps every
 * call in a per-task delivery chain so failures don't fail live delivery, but
 * ordering is preserved by funneling appends for a single `(taskId, tenant)`
 * through one fiber. Terminal events wait for their bounded append barrier
 * before the in-process runtime entry can disappear.
 *
 * The `(taskId, tenant)` pair is the canonical storage key. When `tenant` is
 * `Some`, implementations should namespace per-tenant; when `None`, the
 * store-wide default tenant applies. Because [[A2ATaskStore]] does not require
 * globally-unique task ids across tenants, mixing tenants under the same id
 * without scoping will collide.
 */
trait A2AEventStore:
  def append(
    taskId: TaskId,
    tenant: Option[String],
    event: A2AResponse.StreamEvent,
  ): UIO[Unit]
  def load(
    taskId: TaskId,
    tenant: Option[String],
    limit: Int,
  ): UIO[List[A2AResponse.StreamEvent]]
end A2AEventStore

/**
 * Fallback stream source used when no in-process runtime bus exists.
 *
 * The server prepends a synthetic [[A2AResponse.StreamEvent.TaskSnapshot]] of
 * the current task before delegating to `replay`. Providers MUST omit any
 * `TaskSnapshot` events from their replay output to avoid client-visible
 * duplication; the server filters defensively but providers should not depend
 * on it.
 *
 * When both [[A2AEventStore]] and [[A2AReplayProvider]] are configured on the
 * server, **the provider takes precedence** — the store is consulted only when
 * no provider is set. Wire both during a migration if you want, but live replay
 * will read from the provider only.
 */
trait A2AReplayProvider:
  def replay(task: A2ATask, tenant: Option[String]): ZStream[Any, Throwable, A2AResponse.StreamEvent]
end A2AReplayProvider

/** Validation policy for server-side push notification callback URLs. */
trait PushNotificationUrlPolicy:
  def validate(url: String): Task[Unit]
end PushNotificationUrlPolicy

/** Authorization hook for authenticated extended Agent Card retrieval. */
trait A2AExtendedAgentCardAuth:
  def authorize(publicCard: AgentCard, authorization: Option[String]): Task[Unit]
end A2AExtendedAgentCardAuth

/** Authorization hook for public Agent Card discovery. Defaults to public discovery. */
trait A2AAgentCardAuth:
  def authorize(publicCard: AgentCard, authorization: Option[String]): Task[Unit]
end A2AAgentCardAuth

object A2AAgentCardAuth:
  val permitAll: A2AAgentCardAuth =
    (_: AgentCard, _: Option[String]) => ZIO.unit

  val requireAuthorizationHeader: A2AAgentCardAuth =
    (_: AgentCard, authorization: Option[String]) =>
      if authorization.exists(_.trim.nonEmpty) then ZIO.unit
      else
        ZIO.fail(
          A2AError.unauthenticated(
            "Agent Card discovery requires an Authorization header; configure a custom " +
              "A2AAgentCardAuth to validate deployed credentials"
          )
        )

  def fromFunction(f: (AgentCard, Option[String]) => Task[Unit]): A2AAgentCardAuth =
    (publicCard: AgentCard, authorization: Option[String]) => f(publicCard, authorization)
end A2AAgentCardAuth

object A2AExtendedAgentCardAuth:
  val permitAll: A2AExtendedAgentCardAuth =
    (_: AgentCard, _: Option[String]) => ZIO.unit

  val requireAuthorizationHeader: A2AExtendedAgentCardAuth =
    (_: AgentCard, authorization: Option[String]) =>
      if authorization.exists(_.trim.nonEmpty) then ZIO.unit
      else
        ZIO.fail(
          A2AError.unauthenticated(
            "GetExtendedAgentCard requires an Authorization header; configure a custom " +
              "A2AExtendedAgentCardAuth to validate deployed credentials"
          )
        )

  def fromFunction(f: (AgentCard, Option[String]) => Task[Unit]): A2AExtendedAgentCardAuth =
    (publicCard: AgentCard, authorization: Option[String]) => f(publicCard, authorization)
end A2AExtendedAgentCardAuth

/** Authorization hook for A2A protocol operations beyond public Agent Card discovery. */
trait A2ARequestAuth:
  def authorize(publicCard: AgentCard, context: ServerCallContext): Task[Unit]
end A2ARequestAuth

object A2ARequestAuth:
  val permitAll: A2ARequestAuth =
    (_: AgentCard, _: ServerCallContext) => ZIO.unit

  val requireAuthorizationWhenAdvertised: A2ARequestAuth =
    (publicCard: AgentCard, context: ServerCallContext) =>
      // Require auth only when a requirement actually names a scheme. An
      // empty-object requirement (`[{}]`, OpenAPI "auth optional") and an absent
      // securityRequirements list both mean "no auth gate".
      if !publicCard.securityRequirements.exists(_.schemes.nonEmpty) || context.authorization.exists(_.trim.nonEmpty)
      then ZIO.unit
      else
        ZIO.fail(
          A2AError.unauthenticated(
            "A2A request requires an Authorization header because the Agent Card declares security requirements; " +
              "configure a custom A2ARequestAuth to validate deployed credentials"
          )
        )

  def fromFunction(f: (AgentCard, ServerCallContext) => Task[Unit]): A2ARequestAuth =
    (publicCard: AgentCard, context: ServerCallContext) => f(publicCard, context)

  /**
   * Validate a `Bearer <token>` Authorization header. `validateToken` returns
   * `true` for an accepted token. Use [[A2AAuth.constantTimeEquals]] inside the
   * validator when comparing against a known secret to avoid leaking token
   * length/prefix via timing. A missing/non-Bearer/empty header is rejected.
   */
  def requireBearer(validateToken: String => Task[Boolean]): A2ARequestAuth =
    (_: AgentCard, context: ServerCallContext) =>
      val token = context.authorization
        .map(_.trim)
        // Locale-independent scheme match (`toLowerCase` mangles 'I' under a
        // Turkish default locale).
        .collect { case header if header.regionMatches(true, 0, "Bearer ", 0, 7) => header.drop(7).trim }
        .filter(_.nonEmpty)
      token match
        case None        => ZIO.fail(A2AError.unauthenticated("A2A request requires a non-empty Bearer token"))
        case Some(value) =>
          validateToken(value).flatMap {
            case true  => ZIO.unit
            case false => ZIO.fail(A2AError.unauthenticated("A2A request Bearer token was rejected"))
          }
end A2ARequestAuth

/** Small auth helpers shared by custom [[A2ARequestAuth]]/[[A2AExtendedAgentCardAuth]] hooks. */
object A2AAuth:
  /**
   * Length-tolerant constant-time string comparison for secret/token checks —
   * avoids the timing leak of `==` (which short-circuits on the first differing
   * char). Compares all positions regardless of where they diverge. Char-based
   * so it cross-builds (JVM + Scala.js) without a charset dependency.
   */
  def constantTimeEquals(a: String, b: String): Boolean =
    var result = a.length ^ b.length
    val n      = math.max(a.length, b.length)
    var i      = 0
    while i < n do
      val ca = if i < a.length then a.charAt(i).toInt else 0
      val cb = if i < b.length then b.charAt(i).toInt else 0
      result |= (ca ^ cb)
      i += 1
    result == 0
end A2AAuth

object PushNotificationUrlPolicy:
  val allowAll: PushNotificationUrlPolicy =
    (_: String) => ZIO.unit

  /**
   * Reject URLs that don't look external (loopback, link-local, RFC-1918, etc.).
   * Uses `java.net.URI` for parsing — cross-built (Scala.js polyfills java.net).
   */
  val externalOnly: PushNotificationUrlPolicy =
    (url: String) =>
      ZIO
        .attempt {
          val parsed   = java.net.URI(url)
          val protocol = Option(parsed.getScheme).map(_.toLowerCase).getOrElse("")
          val hostname = hostName(parsed)
            .map(_.stripPrefix("[").stripSuffix("]").toLowerCase)
            .getOrElse("")
          if protocol != "http" && protocol != "https" then Left("Push notification URL must use http or https")
          else if isBlockedHost(hostname) then Left(s"Push notification URL host is not allowed: $hostname")
          else Right(())
        }
        .catchAll(_ => ZIO.succeed(Left("Push notification URL is invalid")))
        .flatMap {
          case Right(()) => ZIO.unit
          case Left(msg) => ZIO.fail(A2AError.invalidParams(msg))
        }

  private def hostName(parsed: java.net.URI): Option[String] =
    Option(parsed.getHost).orElse(
      Option(parsed.getRawAuthority).flatMap { authority =>
        val withoutUserInfo = authority.drop(authority.lastIndexOf('@') + 1)
        if withoutUserInfo.startsWith("[") then
          val end = withoutUserInfo.indexOf(']')
          Option.when(end >= 0)(withoutUserInfo.substring(1, end))
        else Some(withoutUserInfo.takeWhile(_ != ':')).filter(_.nonEmpty)
      }
    )

  private def isBlockedHost(hostname: String): Boolean =
    hostname.isEmpty ||
      hostname == "localhost" ||
      hostname.endsWith(".localhost") ||
      isBlockedIpv4(hostname) ||
      isBlockedIpv6(hostname)

  private def isBlockedIpv4(hostname: String): Boolean =
    parseIpv4(hostname).exists { nums =>
      val a :: b :: _ = nums: @unchecked
      a == 0 ||
      a == 10 ||
      a == 127 ||
      (a == 100 && b >= 64 && b <= 127) ||
      (a == 169 && b == 254) ||
      (a == 172 && b >= 16 && b <= 31) ||
      (a == 192 && b == 168) ||
      (a == 198 && (b == 18 || b == 19)) ||
      a >= 224
    }

  private def parseIpv4(hostname: String): Option[List[Int]] =
    val parts = hostname.split("\\.", -1).toList
    if parts.isEmpty || parts.length > 4 || parts.exists(_.isEmpty) then None
    else
      val nums = parts.map(parseIpv4Number)
      if nums.exists(_.isEmpty) then None
      else
        val values = nums.flatten
        parts.length match
          case 1 =>
            values match
              case List(value) if value >= 0 && value <= BigInt("ffffffff", 16) =>
                Some(
                  List(
                    ((value >> 24) & 0xff).toInt,
                    ((value >> 16) & 0xff).toInt,
                    ((value >> 8) & 0xff).toInt,
                    (value & 0xff).toInt,
                  )
                )
              case _ => None
          case 2 =>
            values match
              case List(a, b) if a <= 255 && b <= BigInt("ffffff", 16) =>
                Some(List(a.toInt, ((b >> 16) & 0xff).toInt, ((b >> 8) & 0xff).toInt, (b & 0xff).toInt))
              case _ => None
          case 3 =>
            values match
              case List(a, b, c) if a <= 255 && b <= 255 && c <= BigInt("ffff", 16) =>
                Some(List(a.toInt, b.toInt, ((c >> 8) & 0xff).toInt, (c & 0xff).toInt))
              case _ => None
          case 4 =>
            values match
              case List(a, b, c, d) if values.forall(value => value >= 0 && value <= 255) =>
                Some(List(a.toInt, b.toInt, c.toInt, d.toInt))
              case _ => None
          case _ =>
            None
        end match
      end if
    end if
  end parseIpv4

  private def parseIpv4Number(part: String): Option[BigInt] =
    val lower  = part.toLowerCase
    val parsed =
      if lower
          .startsWith("0x") && lower.length > 2 && lower.drop(2).forall(ch => ch.isDigit || (ch >= 'a' && ch <= 'f'))
      then Some(BigInt(lower.drop(2), 16))
      else if lower.length > 1 && lower.startsWith("0") && lower.forall(ch => ch >= '0' && ch <= '7') then
        Some(BigInt(lower, 8))
      else if lower.forall(_.isDigit) then Some(BigInt(lower, 10))
      else None
    parsed.filter(_ >= 0)

  private def isBlockedIpv6(hostname: String): Boolean =
    val host = hostname.toLowerCase
    host.contains(":") && {
      host == "::" ||
      host == "::1" ||
      host == "0:0:0:0:0:0:0:0" ||
      host == "0:0:0:0:0:0:0:1" ||
      host.startsWith("fe80:") ||
      host.startsWith("fc") ||
      host.startsWith("fd") ||
      host.startsWith("ff") ||
      (host.startsWith("::ffff:") && isBlockedIpv4(host.stripPrefix("::ffff:")))
    }
end PushNotificationUrlPolicy

// `A2AServer.Config` lives in src/js/.../A2AServer.scala (JS) and
// src/jvm/.../A2AServerLive.scala (JVM). They are platform-specific
// because the JS Config references `AgentOptions` (Claude Agent SDK
// adapter, JS-only). The shared traits above don't need a Config —
// they're just interface boundaries; the per-platform impl owns its
// own configuration shape.

/**
 * Pluggable A2A task store.
 *
 * The default `A2ATaskStore.inMemory` keeps tasks in a process-local map,
 * which is fine for in-process tests but loses everything when the host
 * scales to zero (e.g. Modal `@web_server` containers idle out, restart
 * with empty state, and `tasks/get` for a previously-accepted id returns
 * "task not found"). Production hosts should plug a durable backend
 * (Modal Dict, Redis, etc.) via [[A2AServer.Config.taskStore]] so the
 * task lifecycle survives container restarts and follow-up A2A messages
 * can find their context's prior tasks.
 *
 * Eviction is the implementation's call: the protocol does not GC tasks
 * implicitly. Callers decide when (and whether) to drop entries via
 * [[delete]].
 *
 * Durable implementations that map task/config IDs into external storage
 * keys must validate or escape those IDs before using them in
 * backend-specific paths, SQL, keys, or document identifiers.
 */
trait A2ATaskStore:
  def save(task: A2ATask, tenant: Option[String]): UIO[Unit]
  def load(taskId: TaskId, tenant: Option[String]): UIO[Option[A2ATask]]
  def list(params: A2ARequest.TasksList, tenant: Option[String]): Task[A2AResponse.ListTasksResult]
  def delete(taskId: TaskId, tenant: Option[String]): UIO[Unit]
end A2ATaskStore

object A2ATaskStore:
  private enum PageToken derives CanEqual:
    case Offset(value: Int)
    case Cursor(timestamp: String, taskId: String)

  /** Default in-process store; non-persistent. */
  def inMemory: A2ATaskStore = new InMemoryTaskStoreImpl

  /**
   * Apply the standard A2A `tasks/list` filters, ordering, cursor pagination,
   * history projection, and artifact inclusion rules to an already-scoped task
   * collection.
   *
   * Store implementations should pre-filter by their storage namespace/tenant,
   * then delegate here so durable stores don't drift from the in-memory default.
   */
  def listTasks(
    tasks: Iterable[A2ATask],
    params: A2ARequest.TasksList,
  ): Task[A2AResponse.ListTasksResult] =
    for statusTimestampAfter <- validateListParams(params)
    yield
      val pageSize = params.pageSize.getOrElse(50)
      val filtered = tasks.toList
        .filter(task => params.contextId.forall(_ == task.contextId))
        .filter(task => params.status.forall(_ == task.status.state))
        .filter(task =>
          statusTimestampAfter.forall(after => task.status.timestamp.flatMap(parseTimestamp).exists(!_.isBefore(after)))
        )
        .sortBy(taskSortKey)
        .reverse
      val pageSource = decodePageToken(params.pageToken.getOrElse("")).getOrElse(PageToken.Offset(0)) match
        case PageToken.Offset(offset) =>
          filtered.drop(offset)
        case PageToken.Cursor(timestamp, taskId) =>
          filtered.filter(task =>
            summon[Ordering[(Long, Int, String)]].lt(taskSortKey(task), cursorSortKey(timestamp, taskId))
          )
      val pageWithLookahead = pageSource.take(pageSize + 1)
      val page              = pageWithLookahead.take(pageSize)
      val next              = page.lastOption
        .filter(_ => pageWithLookahead.length > pageSize)
        .map(task => encodePageToken(taskCursor(task)))
      val includeArtifacts = params.includeArtifacts.getOrElse(false)
      A2AResponse.ListTasksResult(
        tasks = page.map { task =>
          val withHistory = applyHistoryLength(task, params.historyLength)
          if includeArtifacts then withHistory else withHistory.copy(artifacts = Nil)
        },
        nextPageToken = next,
        pageSize = pageSize,
        totalSize = filtered.length,
        includeArtifacts = includeArtifacts,
      )

  /**
   * Truncate `task.history` to the requested length (matches the A2A
   * `historyLength` semantic). Public so durable [[A2ATaskStore]] impls
   * can stay byte-identical with the in-memory default's projection.
   */
  def applyHistoryLength(task: A2ATask, historyLength: Option[Int]): A2ATask =
    historyLength match
      case Some(length) if length <= 0 => task.copy(history = Nil)
      case Some(length)                => task.copy(history = task.history.takeRight(length))
      case None                        => task

  private def validateListParams(params: A2ARequest.TasksList): Task[Option[java.time.Instant]] =
    params.pageSize match
      case Some(size) if size < 1 || size > 100 =>
        ZIO.fail(A2AError.invalidParams(s"pageSize must be between 1 and 100 inclusive, got $size"))
      case _ =>
        params.historyLength match
          case Some(length) if length < 0 =>
            ZIO.fail(A2AError.invalidParams(s"historyLength must be non-negative integer, got $length"))
          case _ =>
            params.pageToken match
              case Some(token) if token.nonEmpty && token.toIntOption.isEmpty =>
                decodePageToken(token) match
                  case Some(_) => validateStatusTimestampAfter(params.statusTimestampAfter)
                  case None    => ZIO.fail(A2AError.invalidParams("Invalid pageToken"))
              case Some(token) if token.toIntOption.exists(_ < 0) =>
                ZIO.fail(A2AError.invalidParams("Invalid pageToken"))
              case _ =>
                validateStatusTimestampAfter(params.statusTimestampAfter)

  private def validateStatusTimestampAfter(value: Option[String]): Task[Option[java.time.Instant]] =
    value match
      case Some(raw) =>
        ZIO
          .fromOption(parseTimestamp(raw))
          .map(Some(_))
          .orElseFail(
            A2AError.invalidParams(s"statusTimestampAfter must be an ISO 8601 UTC timestamp ending in Z, got $raw")
          )
      case None =>
        ZIO.succeed(None)

  private def parseTimestamp(value: String): Option[java.time.Instant] =
    if value.endsWith("Z") then Try(java.time.Instant.parse(value)).toOption
    else None

  private def timestampSortKey(value: String): (Long, Int) =
    parseTimestamp(value).map(instant => (instant.getEpochSecond, instant.getNano)).getOrElse((Long.MinValue, 0))

  private def taskSortKey(task: A2ATask): (Long, Int, String) =
    val (epochSecond, nano) = task.status.timestamp.map(timestampSortKey).getOrElse((Long.MinValue, 0))
    (epochSecond, nano, task.id.value)

  private def cursorSortKey(timestamp: String, taskId: String): (Long, Int, String) =
    val (epochSecond, nano) = timestampSortKey(timestamp)
    (epochSecond, nano, taskId)

  private def taskCursor(task: A2ATask): PageToken.Cursor =
    val timestamp = task.status.timestamp
      .flatMap(parseTimestamp)
      .map(_.toString)
      .getOrElse(task.status.timestamp.getOrElse(""))
    PageToken.Cursor(timestamp, task.id.value)

  private def encodePageToken(cursor: PageToken.Cursor): String =
    s"v1:${hexEncode(cursor.timestamp)}:${hexEncode(cursor.taskId)}"

  private def decodePageToken(raw: String): Option[PageToken] =
    if raw.isEmpty then Some(PageToken.Offset(0))
    else raw.toIntOption.filter(_ >= 0).map(PageToken.Offset.apply).orElse(decodeCursor(raw))

  private def decodeCursor(raw: String): Option[PageToken.Cursor] =
    if !raw.startsWith("v1:") then None
    else
      raw.drop("v1:".length).split(":", -1).toList match
        case timestampHex :: taskIdHex :: Nil =>
          for
            timestamp <- hexDecode(timestampHex).filter(value => value.isEmpty || parseTimestamp(value).isDefined)
            taskId    <- hexDecode(taskIdHex).filter(_.nonEmpty)
          yield PageToken.Cursor(timestamp, taskId)
        case _ =>
          None

  private def hexEncode(value: String): String =
    value.flatMap { char =>
      val hex = char.toInt.toHexString
      "0" * (4 - hex.length) + hex
    }

  private def hexDecode(value: String): Option[String] =
    if value.length % 4 != 0 then None
    else Try(value.grouped(4).map(Integer.parseInt(_, 16).toChar).mkString).toOption
end A2ATaskStore

/** Default in-process task store. Pure Scala, cross-built. */
private[a2a] final class InMemoryTaskStoreImpl extends A2ATaskStore:
  private val tasks = mutable.Map.empty[(String, String), A2ATask]
  private val lock  = new AnyRef

  private def key(id: TaskId, tenant: Option[String]): (String, String) =
    (tenant.getOrElse(""), id.value)

  def save(task: A2ATask, tenant: Option[String]): UIO[Unit] =
    ZIO.succeed(lock.synchronized { tasks.update(key(task.id, tenant), task); () })

  def load(taskId: TaskId, tenant: Option[String]): UIO[Option[A2ATask]] =
    ZIO.succeed(lock.synchronized(tasks.get(key(taskId, tenant))))

  def delete(taskId: TaskId, tenant: Option[String]): UIO[Unit] =
    ZIO.succeed(lock.synchronized { tasks.remove(key(taskId, tenant)); () })

  def list(params: A2ARequest.TasksList, tenant: Option[String]): Task[A2AResponse.ListTasksResult] =
    val all = lock.synchronized {
      tasks.collect {
        case ((t, _), task) if t == tenant.getOrElse("") => task
      }.toList
    }
    A2ATaskStore.listTasks(all, params)
end InMemoryTaskStoreImpl

/**
 * Internal server call context — tenant/version/extensions threaded through
 * the request handler. Cross-built; concrete impls in JS/JVM read it.
 */
private[a2a] final case class ServerCallContext(
  tenant: Option[String] = None,
  requestedVersion: Option[String] = None,
  requestedExtensions: List[String] = Nil,
  authorization: Option[String] = None)

private[a2a] object A2AServerAgentCard:
  def apply(
    name: String,
    description: String,
    baseUrl: String,
    capabilities: AgentCapabilities,
    skills: List[AgentSkill],
    tenant: Option[String],
  ): AgentCard =
    AgentCard(
      name = name,
      description = description,
      supportedInterfaces = supportedInterfaces(baseUrl, tenant),
      capabilities = capabilities,
      skills = AgentCard.requiredSkills(name, description, skills),
    )

  def supportedInterfaces(baseUrl: String, tenant: Option[String]): List[AgentInterface] =
    List(
      AgentInterface.jsonRpc(baseUrl, tenant),
      AgentInterface.rest(baseUrl, tenant),
    )
end A2AServerAgentCard

private[a2a] object A2AServiceParameters:
  def validate(
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    context: ServerCallContext,
    binding: A2ATransport,
  ): Either[A2AError, Unit] =
    validateVersionAndTenant(agentCard, context, binding).flatMap(_ =>
      validateRequiredExtensions(capabilities, context)
    )

  def validateVersion(
    agentCard: AgentCard,
    context: ServerCallContext,
    binding: A2ATransport,
  ): Either[A2AError, Unit] =
    val requested = requestedVersion(context)
    if matchingInterfaces(agentCard, context, binding).nonEmpty then Right(())
    else Left(A2AError.versionNotSupported(requested))

  def validateVersionAndTenant(
    agentCard: AgentCard,
    context: ServerCallContext,
    binding: A2ATransport,
  ): Either[A2AError, Unit] =
    val requested = requestedVersion(context)
    val matching  = matchingInterfaces(agentCard, context, binding)
    if matching.isEmpty then Left(A2AError.versionNotSupported(requested))
    else validateInterfaceTenant(matching, context)

  def validateRequiredExtensions(
    capabilities: AgentCapabilities,
    context: ServerCallContext,
  ): Either[A2AError, Unit] =
    val requested = context.requestedExtensions.toSet
    capabilities.extensions.find(extension => extension.required && !requested.contains(extension.uri)) match
      case Some(extension) => Left(A2AError.extensionSupportRequired(extension.uri))
      case None            => Right(())

  def activatedExtensions(capabilities: AgentCapabilities, context: ServerCallContext): List[String] =
    val supported = capabilities.extensions.map(_.uri).toSet
    context.requestedExtensions.filter(supported.contains).distinct

  private def requestedVersion(context: ServerCallContext): String =
    // NB: an explicitly-blank version (`A2A-Version:` / `?A2A-Version=`) is
    // intentionally treated as legacy "0.3" (fail-loud on a malformed version
    // param), distinct from omitting it entirely (→ current version). This
    // asymmetry is deliberate and covered by A2AServerLiveSpec ("treats empty
    // A2A-Version parameter as protocol 0.3").
    context.requestedVersion match
      case Some(value) if value.trim.isEmpty => "0.3"
      case Some(value)                       => value.trim
      case None                              => A2AProtocol.Version

  private def matchingInterfaces(
    agentCard: AgentCard,
    context: ServerCallContext,
    binding: A2ATransport,
  ): List[AgentInterface] =
    val version = A2AProtocol.negotiationVersion(requestedVersion(context))
    agentCard.supportedInterfaces.filter(iface =>
      iface.protocolBinding == binding && A2AProtocol.negotiationVersion(iface.protocolVersion) == version
    )

  private def validateInterfaceTenant(
    interfaces: List[AgentInterface],
    context: ServerCallContext,
  ): Either[A2AError, Unit] =
    val tenants = interfaces.flatMap(_.tenant.map(_.trim).filter(_.nonEmpty)).distinct
    if tenants.size < interfaces.size then Right(())
    else
      context.tenant.map(_.trim).filter(_.nonEmpty) match
        case None => Left(A2AError.invalidParams("tenant is required for selected AgentInterface"))
        case Some(tenant) if tenants.contains(tenant) => Right(())
        case Some(_) => Left(A2AError.invalidParams("tenant must match selected AgentInterface tenant"))
end A2AServiceParameters

private[a2a] type TaskRuntimeKey = (String, String)

private[a2a] def taskRuntimeKey(taskId: TaskId, context: ServerCallContext): TaskRuntimeKey =
  (context.tenant.getOrElse(""), taskId.value)
