package com.tjclp.scalagent.a2a

import scala.collection.mutable

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
  /** Default in-process store; non-persistent. */
  def inMemory: A2ATaskStore = new InMemoryTaskStoreImpl

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
    validateListParams(params) *> ZIO.succeed {
      val pageSize = params.pageSize.getOrElse(50)
      val all      = lock.synchronized {
        tasks.collect {
          case ((t, _), task) if t == tenant.getOrElse("") => task
        }.toList
      }
      val filtered = all
        .filter(task => params.contextId.forall(_ == task.contextId))
        .filter(task => params.status.forall(_ == task.status.state))
        .filter(task => params.statusTimestampAfter.forall { after => task.status.timestamp.exists(_ >= after) })
        .sortBy(task => (task.status.timestamp.getOrElse(""), task.id.value))
        .reverse
      val offset = params.pageToken.flatMap(_.toIntOption).getOrElse(0)
      val page   = filtered.slice(offset, offset + pageSize)
      val next   = Option.when(offset + pageSize < filtered.length)((offset + pageSize).toString)
      A2AResponse.ListTasksResult(
        tasks = page.map { task =>
          val withHistory = A2ATaskStore.applyHistoryLength(task, params.historyLength)
          if params.includeArtifacts.getOrElse(false) then withHistory else withHistory.copy(artifacts = Nil)
        },
        nextPageToken = next,
        pageSize = pageSize,
        totalSize = filtered.length,
      )
    }

  private def validateListParams(params: A2ARequest.TasksList): Task[Unit] =
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
                ZIO.fail(A2AError.invalidParams("Invalid pageToken"))
              case _ =>
                ZIO.unit
end InMemoryTaskStoreImpl

/**
 * Internal server call context — tenant/version/extensions threaded through
 * the request handler. Cross-built; concrete impls in JS/JVM read it.
 */
private[a2a] final case class ServerCallContext(
  tenant: Option[String] = None,
  requestedVersion: Option[String] = None,
  requestedExtensions: List[String] = Nil)

private[a2a] type TaskRuntimeKey = (String, String)

private[a2a] def taskRuntimeKey(taskId: TaskId, context: ServerCallContext): TaskRuntimeKey =
  (context.tenant.getOrElse(""), taskId.value)
