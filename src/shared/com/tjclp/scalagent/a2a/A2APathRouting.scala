package com.tjclp.scalagent.a2a

import scala.collection.mutable

import zio.json.*
import zio.json.ast.Json

private[a2a] object A2APathRouting:
  private val knownPrefixes = Set("message:send", "message:stream", "tasks", "extendedAgentCard")

  enum RestRoute:
    case MessageSend
    case MessageStream
    case TasksList
    case TaskCancel(rawTaskAction: String)
    case TaskSubscribe(rawTaskAction: String)
    case TaskGet(rawTaskId: String)
    case PushConfigCreate(rawTaskId: String)
    case PushConfigList(rawTaskId: String)
    case PushConfigGet(rawTaskId: String, rawConfigId: String)
    case PushConfigDelete(rawTaskId: String, rawConfigId: String)
    case ExtendedAgentCard
    case MissingTaskId

  final case class RoutedRest(pathTenant: Option[String], route: Option[RestRoute])

  final class Query(valueOf: String => Option[String], entries: Iterable[(String, String)]):
    def string(name: String, aliases: String*): Option[String] =
      A2AJson
        .caseInsensitiveEntryLookup(entries, name, aliases*)
        .orElse(A2AJson.caseInsensitiveLookup(valueOf, name, aliases*))

    def int(name: String, aliases: String*): Either[A2AError, Option[Int]] =
      string(name, aliases*) match
        case Some(value) =>
          value.toIntOption match
            case Some(parsed) => Right(Some(parsed))
            case None         => Left(A2AError.invalidParams(s"$name must be a valid integer"))
        case None =>
          Right(None)

    def bool(name: String, aliases: String*): Either[A2AError, Option[Boolean]] =
      string(name, aliases*) match
        case Some(value) =>
          value.toBooleanOption match
            case Some(parsed) => Right(Some(parsed))
            case None         => Left(A2AError.invalidParams(s"$name must be a valid boolean"))
        case None =>
          Right(None)

    def taskStatus(name: String, aliases: String*): Either[A2AError, Option[TaskState]] =
      string(name, aliases*) match
        case Some(value) =>
          Json
            .Str(value)
            .as[TaskState]
            .left
            .map(error => A2AError.invalidParams(s"Invalid $name: $error"))
            .flatMap(TaskState.requireSpecified(_, name).left.map(A2AError.invalidParams))
            .map(Some(_))
        case None =>
          Right(None)
  end Query

  def query(valueOf: String => Option[String], entries: Iterable[(String, String)] = Iterable.empty): Query =
    new Query(valueOf, entries)

  def query(entries: Iterable[(String, String)]): Query =
    new Query(name => entries.iterator.collectFirst { case (key, value) if key == name => value }, entries)

  def route(method: String, pathname: String): RoutedRest =
    val (pathTenant, path) = splitTenant(pathname)
    val segments           = rawSegments(path)
    val restRoute          =
      (method, segments) match
        case ("POST", List("message:send")) =>
          Some(RestRoute.MessageSend)
        case ("POST", List("message:stream")) =>
          Some(RestRoute.MessageStream)
        case ("GET", List("tasks")) =>
          Some(RestRoute.TasksList)
        case ("POST", List("tasks", rawTaskAction)) if rawTaskAction.endsWith(":cancel") =>
          Some(RestRoute.TaskCancel(rawTaskAction))
        case (verb, List("tasks", rawTaskAction))
            if (verb == "GET" || verb == "POST") && rawTaskAction.endsWith(":subscribe") =>
          Some(RestRoute.TaskSubscribe(rawTaskAction))
        case (_, List("tasks", rawTaskAction))
            if rawTaskAction.endsWith(":cancel") || rawTaskAction.endsWith(":subscribe") =>
          None
        case ("GET", List("tasks", rawTaskId)) =>
          Some(RestRoute.TaskGet(rawTaskId))
        case ("POST", List("tasks", rawTaskId, "pushNotificationConfigs")) =>
          Some(RestRoute.PushConfigCreate(rawTaskId))
        case ("GET", List("tasks", rawTaskId, "pushNotificationConfigs")) =>
          Some(RestRoute.PushConfigList(rawTaskId))
        case ("GET", List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
          Some(RestRoute.PushConfigGet(rawTaskId, rawConfigId))
        case ("DELETE", List("tasks", rawTaskId, "pushNotificationConfigs", rawConfigId)) =>
          Some(RestRoute.PushConfigDelete(rawTaskId, rawConfigId))
        case ("GET", List("extendedAgentCard")) =>
          Some(RestRoute.ExtendedAgentCard)
        case (_, "tasks" :: "" :: _) =>
          Some(RestRoute.MissingTaskId)
        case _ =>
          None
    RoutedRest(pathTenant, restRoute)
  end route

  def splitTenant(pathname: String): (Option[String], String) =
    val stripped = pathname.stripPrefix("/")
    val segments = if stripped.isEmpty then Nil else stripped.split("/", -1).toList
    segments match
      case first :: rest if first.nonEmpty && !knownPrefixes.contains(first) =>
        Some(first) -> ("/" + rest.mkString("/"))
      case _ =>
        None -> pathname

  def rawSegments(value: String): List[String] =
    val stripped = value.stripPrefix("/")
    if stripped.isEmpty then Nil else stripped.split("/", -1).toList

  def resolveTenant(
    pathTenant: Option[String],
    queryTenant: Option[String],
    requestTenant: Option[String] = None,
  ): Either[A2AError, Option[String]] =
    decodeOptionalPathParameter(pathTenant, "tenant").flatMap { decodedPathTenant =>
      // Explicit type annotation works around a Scala 3.8.3 Scala.js pickler
      // crash ("error when pickling type List[String]^{}") triggered by the
      // inferred type of this fluent chain when compiled fresh.
      val sources: List[String] =
        List(decodedPathTenant, queryTenant, requestTenant.filter(_.nonEmpty)).flatten.distinct
      sources match
        case Nil          => Right(None)
        case value :: Nil => Right(Some(value))
        case _            => Left(A2AError.invalidParams("Conflicting tenant values in REST request"))
    }

  def queryTenant(query: Query): Option[String] =
    query.string("tenant").filter(_.nonEmpty)

  def requestedVersion(query: Query): Option[String] =
    query.string(A2AHeader.Version).orElse(query.string("a2aVersion"))

  def requestedExtensions(query: Query): List[String] =
    query
      .string(A2AHeader.StandardExtensions, A2AHeader.Extensions, "a2aExtensions")
      .toList
      .flatMap(A2AHttpBinding.parseExtensionsHeader)

  def tasksList(query: Query, tenant: Option[String]): Either[A2AError, A2ARequest.TasksList] =
    for
      status           <- query.taskStatus("status")
      pageSize         <- query.int("pageSize", "page_size")
      historyLength    <- query.int("historyLength", "history_length")
      includeArtifacts <- query.bool("includeArtifacts", "include_artifacts")
    yield A2ARequest.TasksList(
      contextId = query.string("contextId", "context_id").filter(_.nonEmpty).map(ContextId(_)),
      status = status,
      pageSize = pageSize,
      pageToken = query.string("pageToken", "page_token"),
      historyLength = historyLength,
      statusTimestampAfter = query.string("statusTimestampAfter", "status_timestamp_after"),
      includeArtifacts = includeArtifacts,
      tenant = tenant,
    )

  def tasksGet(
    rawTaskId: String,
    query: Query,
    tenant: Option[String],
  ): Either[A2AError, A2ARequest.TasksGet] =
    for
      id            <- taskId(rawTaskId)
      historyLength <- query.int("historyLength", "history_length")
    yield A2ARequest.TasksGet(id, historyLength, tenant)

  def tasksCancel(
    rawTaskAction: String,
    body: Option[A2ARequest.TasksCancelRestBody],
    tenant: Option[String],
  ): Either[A2AError, A2ARequest.TasksCancel] =
    for
      id <- taskId(rawTaskAction.stripSuffix(":cancel"))
      _  <- body.flatMap(_.id).filter(_ != id) match
        case Some(_) => Left(A2AError.invalidParams("CancelTaskRequest.id does not match path id"))
        case None    => Right(())
    yield A2ARequest.TasksCancel(
      id,
      metadata = body.flatMap(_.metadata),
      tenant = tenant,
    )

  def tasksResubscribe(rawTaskAction: String, tenant: Option[String]): Either[A2AError, A2ARequest.TasksResubscribe] =
    taskId(rawTaskAction.stripSuffix(":subscribe")).map(A2ARequest.TasksResubscribe(_, tenant))

  def pushConfigCreate(
    rawTaskId: String,
    config: TaskPushNotificationConfig,
    tenant: Option[String],
  ): Either[A2AError, TaskPushNotificationConfig] =
    for
      id <- taskId(rawTaskId)
      _  <- config.taskId.filter(_ != id) match
        case Some(_) => Left(A2AError.invalidParams("TaskPushNotificationConfig.taskId does not match path taskId"))
        case None    => Right(())
    yield config.copy(taskId = Some(id), tenant = tenant)

  def pushConfigList(
    rawTaskId: String,
    query: Query,
    tenant: Option[String],
  ): Either[A2AError, A2ARequest.PushNotificationConfigList] =
    for
      id       <- taskId(rawTaskId)
      pageSize <- query.int("pageSize", "page_size")
    yield A2ARequest.PushNotificationConfigList(
      id,
      pageSize,
      query.string("pageToken", "page_token"),
      tenant,
    )

  def pushConfigGet(
    rawTaskId: String,
    rawConfigId: String,
    tenant: Option[String],
  ): Either[A2AError, A2ARequest.PushNotificationConfigGet] =
    for
      id       <- taskId(rawTaskId)
      configId <- pushConfigId(rawConfigId)
    yield A2ARequest.PushNotificationConfigGet(id, configId, tenant)

  def pushConfigDelete(
    rawTaskId: String,
    rawConfigId: String,
    tenant: Option[String],
  ): Either[A2AError, A2ARequest.PushNotificationConfigDelete] =
    for
      id       <- taskId(rawTaskId)
      configId <- pushConfigId(rawConfigId)
    yield A2ARequest.PushNotificationConfigDelete(id, configId, tenant)

  def taskId(raw: String): Either[A2AError, TaskId] =
    decodePathParameter(raw, "task ID").flatMap { decoded =>
      if decoded.isEmpty then Left(A2AError.invalidParams("Missing task ID"))
      else Right(TaskId(decoded))
    }

  def pushConfigId(raw: String): Either[A2AError, String] =
    decodePathParameter(raw, "push notification config ID").flatMap { decoded =>
      if decoded.isEmpty then Left(A2AError.invalidParams("Missing push notification config ID"))
      else Right(decoded)
    }

  private def decodeOptionalPathParameter(raw: Option[String], label: String): Either[A2AError, Option[String]] =
    raw.filter(_.nonEmpty) match
      case Some(value) => decodePathParameter(value, label).map(decoded => Some(decoded).filter(_.nonEmpty))
      case None        => Right(None)

  private def decodePathParameter(raw: String, label: String): Either[A2AError, String] =
    if !raw.contains("%") then Right(raw)
    else
      val out   = new StringBuilder(raw.length)
      val bytes = mutable.ArrayBuffer.empty[Byte]
      var i     = 0
      var error = Option.empty[A2AError]

      def flushBytes(): Unit =
        if bytes.nonEmpty then
          strictUtf8Decode(bytes.toArray, label) match
            case Right(decoded) => out.append(decoded)
            case Left(err)      => error = Some(err)
          bytes.clear()

      def invalidPercentEncoding: A2AError =
        A2AError.invalidParams(s"Invalid percent-encoding in $label")

      while i < raw.length && error.isEmpty do
        raw.charAt(i) match
          case '%' =>
            if i + 2 >= raw.length then error = Some(invalidPercentEncoding)
            else
              (hexValue(raw.charAt(i + 1)), hexValue(raw.charAt(i + 2))) match
                case (Some(hi), Some(lo)) =>
                  bytes += (((hi << 4) | lo) & 0xff).toByte
                  i += 3
                case _ =>
                  error = Some(invalidPercentEncoding)
          case ch =>
            flushBytes()
            if error.isEmpty then out.append(ch)
            i += 1

      error match
        case Some(value) => Left(value)
        case None        =>
          flushBytes()
          error match
            case Some(value) => Left(value)
            case None        => Right(out.result())

  private def strictUtf8Decode(bytes: Array[Byte], label: String): Either[A2AError, String] =
    val out = new StringBuilder(bytes.length)
    var i   = 0

    def byteAt(index: Int): Int =
      bytes(index) & 0xff

    def invalid: Either[A2AError, String] =
      Left(A2AError.invalidParams(s"Invalid UTF-8 percent-encoding in $label"))

    def continuation(index: Int): Option[Int] =
      if index < bytes.length then
        val value = byteAt(index)
        Option.when((value & 0xc0) == 0x80)(value)
      else None

    def appendCodePoint(codePoint: Int): Unit =
      if codePoint <= 0xffff then out.append(codePoint.toChar)
      else
        val shifted = codePoint - 0x10000
        out.append(((shifted >> 10) + 0xd800).toChar)
        out.append(((shifted & 0x3ff) + 0xdc00).toChar)

    while i < bytes.length do
      val b0 = byteAt(i)
      if b0 <= 0x7f then
        out.append(b0.toChar)
        i += 1
      else if b0 >= 0xc2 && b0 <= 0xdf then
        continuation(i + 1) match
          case Some(b1) =>
            appendCodePoint(((b0 & 0x1f) << 6) | (b1 & 0x3f))
            i += 2
          case None => return invalid
      else if b0 >= 0xe0 && b0 <= 0xef then
        (continuation(i + 1), continuation(i + 2)) match
          case (Some(b1), Some(b2))
              if (b0 != 0xe0 || b1 >= 0xa0) &&
                (b0 != 0xed || b1 <= 0x9f) =>
            appendCodePoint(((b0 & 0x0f) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f))
            i += 3
          case _ => return invalid
      else if b0 >= 0xf0 && b0 <= 0xf4 then
        (continuation(i + 1), continuation(i + 2), continuation(i + 3)) match
          case (Some(b1), Some(b2), Some(b3))
              if (b0 != 0xf0 || b1 >= 0x90) &&
                (b0 != 0xf4 || b1 <= 0x8f) =>
            appendCodePoint(
              ((b0 & 0x07) << 18) |
                ((b1 & 0x3f) << 12) |
                ((b2 & 0x3f) << 6) |
                (b3 & 0x3f)
            )
            i += 4
          case _ => return invalid
      else return invalid
      end if
    end while

    Right(out.result())
  end strictUtf8Decode

  private def hexValue(ch: Char): Option[Int] =
    ch match
      case value if value >= '0' && value <= '9' => Some(value - '0')
      case value if value >= 'a' && value <= 'f' => Some(value - 'a' + 10)
      case value if value >= 'A' && value <= 'F' => Some(value - 'A' + 10)
      case _                                     => None
end A2APathRouting
