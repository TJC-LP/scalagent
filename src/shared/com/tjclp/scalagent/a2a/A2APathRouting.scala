package com.tjclp.scalagent.a2a

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

  final class Query(valueOf: String => Option[String]):
    def string(name: String, aliases: String*): Option[String] =
      (name +: aliases).iterator.flatMap(valueOf).nextOption()

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

  def query(valueOf: String => Option[String]): Query =
    new Query(valueOf)

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
    val sources = List(pathTenant, queryTenant, requestTenant.filter(_.nonEmpty)).flatten.distinct
    sources match
      case Nil          => Right(None)
      case value :: Nil => Right(Some(value))
      case _            => Left(A2AError.invalidParams("Conflicting tenant values in REST request"))

  def queryTenant(query: Query): Option[String] =
    query.string("tenant").filter(_.nonEmpty)

  def requestedVersion(query: Query): Option[String] =
    query.string(A2AHeader.Version).orElse(query.string("a2aVersion"))

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
    if raw.isEmpty then Left(A2AError.invalidParams("Missing task ID"))
    else Right(TaskId(raw))

  def pushConfigId(raw: String): Either[A2AError, String] =
    if raw.isEmpty then Left(A2AError.invalidParams("Missing push notification config ID"))
    else Right(raw)
end A2APathRouting
