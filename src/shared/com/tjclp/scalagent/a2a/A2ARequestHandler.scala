package com.tjclp.scalagent.a2a

import scala.util.Try

import zio.*
import zio.json.ast.Json
import zio.stream.*

private[a2a] object A2ARequestHandler:
  final case class Config(
    capabilities: AgentCapabilities,
    eventStore: Option[A2AEventStore],
    replayProvider: Option[A2AReplayProvider],
    eventReplayLimit: Int,
    eventStoreAppendTimeout: Duration,
    eventStoreLoadTimeout: Duration,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy,
    agentCardAuth: A2AAgentCardAuth,
    extendedAgentCardAuth: A2AExtendedAgentCardAuth,
    requestAuth: A2ARequestAuth,
    messageResponseSelector: Option[MessageResponseSelector])

  final case class PreparedRun(
    message: A2AMessage,
    task: A2ATask,
    bus: A2AEventBus)

  type ExecuteRun              = (PreparedRun, A2AEventPublisher) => Task[Unit]
  type MessageResponseOverride = A2ARequest.MessageSend => Task[A2AMessage]
  type MessageResponseSelector = A2ARequest.MessageSend => Task[Option[A2AMessage]]
end A2ARequestHandler

private[a2a] final class A2ARequestHandler(
  config: A2ARequestHandler.Config,
  runtime: Runtime[Any],
  taskStore: A2ATaskStore,
  pushStore: A2APushNotificationStore,
  runtimeRegistry: A2ARuntimeRegistry,
  pushSender: A2APushNotificationSender,
  agentCardProvider: () => AgentCard,
  extendedAgentCardProvider: () => Option[AgentCard],
  executeRun: A2ARequestHandler.ExecuteRun):

  import A2ARequestHandler.PreparedRun

  private enum PushConfigPageToken derives CanEqual:
    case Offset(value: Int)

  private val eventPersister: Option[EventStorePersister] =
    config.eventStore.map(EventStorePersister(_, config.eventStoreAppendTimeout))

  def agentCard: AgentCard = agentCardProvider()

  /**
   * The public Agent Card may be cached by shared/proxy caches only when its
   * discovery is unauthenticated. With any non-`permitAll` card auth (or a
   * tenant-scoped provider) the response is per-caller and must not be shared.
   */
  def agentCardPubliclyCacheable: Boolean = config.agentCardAuth eq A2AAgentCardAuth.permitAll

  def getAgentCard(context: ServerCallContext): Task[AgentCard] =
    val card = agentCard
    config.agentCardAuth.authorize(card, context.authorization).as(card)

  def sendMessage(
    params: A2ARequest.MessageSend,
    context: ServerCallContext,
  ): Task[A2AResponse.SendMessageResult] =
    authorizeRequest(context) *> {
      messageResponse(params, context).flatMap {
        case Some(messageResult) =>
          ZIO.succeed(messageResult)
        case None =>
          for
            prepared <- prepare(params, context)
            result   <-
              val historyLength = params.configuration.flatMap(_.historyLength)
              val project       = (task: A2ATask) => A2ATaskStore.applyHistoryLength(task, historyLength)
              val run           =
                for
                  _ <- saveInlinePushConfig(params.configuration, prepared.task.id, context)
                  stream = prepared.bus.stream
                  _      <- startExecution(prepared, context)
                  result <-
                    if params.configuration.exists(_.returnImmediately) then
                      ZIO.succeed(A2AResponse.SendMessageResult.TaskResult(project(prepared.task)))
                    else
                      waitForFinal(prepared.task.id, stream, context)
                        .map(task => A2AResponse.SendMessageResult.TaskResult(project(task)))
                yield result
              run.onError(_ => cleanupPrepared(prepared, context))
          yield result
      }
    }

  def sendMessageStream(
    params: A2ARequest.MessageSend,
    context: ServerCallContext,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    authorizeRequest(context) *> requireStreaming *> {
      messageResponse(params, context).flatMap {
        case Some(A2AResponse.SendMessageResult.MessageResult(message)) =>
          ZIO.succeed(ZStream.succeed(A2AResponse.StreamEvent.Message(message)))
        case Some(A2AResponse.SendMessageResult.TaskResult(task)) =>
          ZIO.succeed(ZStream.succeed(A2AResponse.StreamEvent.TaskSnapshot(task)))
        case None =>
          for
            prepared <- prepare(params, context)
            stream   <-
              val run =
                for
                  _ <- saveInlinePushConfig(params.configuration, prepared.task.id, context)
                  stream = prepared.bus.stream
                  _ <- startExecution(prepared, context)
                yield stream
              run.onError(_ => cleanupPrepared(prepared, context))
          yield stream
      }
    }

  def getTask(params: A2ARequest.TasksGet, context: ServerCallContext): Task[A2ATask] =
    authorizeRequest(context) *> validateHistoryLength(params.historyLength) *>
      taskStore.load(params.id, context.tenant).flatMap {
        case Some(task) =>
          reconcileOrphaned(task, context).map(A2ATaskStore.applyHistoryLength(_, params.historyLength))
        case None => ZIO.fail(A2AError.taskNotFound(params.id))
      }

  /**
   * Durable-completion safety net. A task in an active state with no active
   * runtime bus is orphaned: its forked execution ended without writing a
   * stream-ending status — the server was restarted/recycled, or the run died —
   * so it would otherwise report `working` forever. Transition it to terminal
   * `failed` and persist, so a `tasks/get` poll self-heals. An active bus means
   * the run is legitimately in flight (or just reserved in `prepare`), so leave
   * it alone.
   *
   * Scope: correct for a single web-tier replica (the registry reflects every
   * active run on this process). A multi-replica deployment would need a shared
   * runtime registry before relying on this, or a run on replica B looks
   * orphaned to replica A. A live run that hangs WITHOUT ending is not covered
   * here (its bus is still active) — bound that with `Config.taskTimeout`.
   */
  private def reconcileOrphaned(task: A2ATask, context: ServerCallContext): Task[A2ATask] =
    if task.isStreamEnding then ZIO.succeed(task)
    else
      runtimeRegistry.bus(taskRuntimeKey(task.id, context)).flatMap {
        case Some(_) => ZIO.succeed(task)
        case None    =>
          // We loaded `task` (non-terminal) BEFORE checking the bus. A run that
          // finishes in that window persists its terminal status and THEN removes
          // its bus (terminal-persist happens-before bus-removal in
          // ResultManager.publish), so "no bus" can mean "orphaned" OR "just
          // completed". Re-check the bus once more (catches a retry run that
          // registered a fresh bus), then transition via the store's
          // compare-and-set so a concurrent terminal write is honored, not
          // clobbered, and a deleted task isn't resurrected.
          runtimeRegistry.bus(taskRuntimeKey(task.id, context)).flatMap {
            case Some(_) => ZIO.succeed(task)
            case None    =>
              ZIO.logWarning(s"Reconciling orphaned non-terminal task ${task.id.value} -> failed") *>
                taskStore
                  .transformIfNotTerminal(task.id, context.tenant) { fresh =>
                    val message = A2AMessage
                      .agentText(
                        "Task interrupted: no active run (the server restarted or the run ended without " +
                          "completing). Resend the message to retry.",
                        Some(fresh.contextId),
                      )
                      .copy(taskId = Some(fresh.id))
                    fresh.copy(status = TaskStatus.failed(message))
                  }
                  .flatMap {
                    case Some(result) => ZIO.succeed(result)
                    case None         => ZIO.fail(A2AError.taskNotFound(task.id))
                  }
          }
      }

  def listTasks(params: A2ARequest.TasksList, context: ServerCallContext): Task[A2AResponse.ListTasksResult] =
    authorizeRequest(context) *> taskStore.list(params, context.tenant)

  def cancelTask(params: A2ARequest.TasksCancel, context: ServerCallContext): Task[A2ATask] =
    authorizeRequest(context) *> taskStore.load(params.id, context.tenant).flatMap {
      case Some(task) if task.status.state == TaskState.Canceled =>
        ZIO.succeed(task)
      case Some(task) if task.isTerminal =>
        ZIO.fail(A2AError.taskNotCancelable(params.id))
      case Some(task) =>
        val canceled = task.copy(status = TaskStatus.canceled)
        val event    = A2AResponse.StreamEvent.TaskStatusUpdate(
          params.id,
          task.contextId,
          canceled.status,
          `final` = true,
        )
        val key = taskRuntimeKey(params.id, context)
        for
          runtimeEntry <- runtimeRegistry.markCanceled(key)
          _            <- runtimeEntry.flatMap(_._2) match
            case Some(fiber) => fiber.interrupt.unit
            case None        => ZIO.unit
          _            <- taskStore.save(canceled, context.tenant)
          awaitPersist <- eventPersister.fold[UIO[UIO[Unit]]](ZIO.succeed(ZIO.unit))(_.enqueue(event, context.tenant))
          _            <- runtimeEntry.map(_._1) match
            case Some(bus) => bus.publish(event) *> bus.finish
            case None      => ZIO.unit
          _ <- pushSender.send(event, context)
          _ <- awaitPersist
          _ <- runtimeRegistry.remove(key)
        yield canceled
      case None =>
        ZIO.fail(A2AError.taskNotFound(params.id))
    }

  def resubscribe(params: A2ARequest.TasksResubscribe, context: ServerCallContext)
    : Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    authorizeRequest(context) *> requireStreaming *> taskStore.load(params.id, context.tenant).flatMap {
      case Some(task) if task.isTerminal =>
        ZIO.fail(A2AError.unsupportedOperation(s"Task ${params.id.value} is terminal and cannot be subscribed"))
      case Some(task) =>
        runtimeRegistry.bus(taskRuntimeKey(params.id, context)).flatMap {
          case Some(bus) =>
            ZIO.succeed(ZStream.succeed(A2AResponse.StreamEvent.TaskSnapshot(task)) ++ bus.stream.filter(notSnapshot))
          case None => durableReplay(task, context)
        }
      case None =>
        ZIO.fail(A2AError.taskNotFound(params.id))
    }

  private val notSnapshot: A2AResponse.StreamEvent => Boolean =
    event => !event.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot]

  private def durableReplay(task: A2ATask, context: ServerCallContext)
    : Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    val snapshot = ZStream.succeed(A2AResponse.StreamEvent.TaskSnapshot(task))
    config.replayProvider match
      case Some(provider) =>
        ZIO.succeed(snapshot ++ provider.replay(task, context.tenant).filter(notSnapshot))
      case None =>
        config.eventStore match
          case Some(store) =>
            store
              .load(task.id, context.tenant, config.eventReplayLimit)
              .timeout(config.eventStoreLoadTimeout)
              .flatMap {
                case Some(events) =>
                  val replay = events.filter(notSnapshot)
                  if task.isStreamEnding || replay.exists(_.isFinal) then
                    ZIO.succeed(snapshot ++ ZStream.fromIterable(replay))
                  else inactiveNonTerminalReplayFailure(task, "the durable event store has no terminal event")
                case None =>
                  if task.isStreamEnding then ZIO.succeed(snapshot)
                  else
                    inactiveNonTerminalReplayFailure(
                      task,
                      s"event store load timed out after ${config.eventStoreLoadTimeout}",
                    )
              }
          case None =>
            if task.isStreamEnding then ZIO.succeed(snapshot)
            else inactiveNonTerminalReplayFailure(task, "no event store / replay provider is configured")
    end match
  end durableReplay

  private def inactiveNonTerminalReplayFailure(
    task: A2ATask,
    reason: String,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    ZIO.fail(
      A2AError.unsupportedOperation(
        s"Task ${task.id.value} has no active runtime bus and cannot be replayed to a terminal event ($reason). Poll tasks/get for status."
      )
    )

  def createPushConfig(configParam: TaskPushNotificationConfig, context: ServerCallContext)
    : Task[TaskPushNotificationConfig] =
    authorizeRequest(context) *> requirePush *> {
      val taskId = configParam.taskId.getOrElse(TaskId(""))
      if taskId.isEmpty then ZIO.fail(A2AError.invalidParams("taskId is required"))
      else
        ensureTask(taskId, context) *> config.pushNotificationUrlPolicy
          .validate(configParam.url) *> pushStore.save(taskId, context.tenant, configParam)
    }

  def getPushConfig(params: A2ARequest.PushNotificationConfigGet, context: ServerCallContext)
    : Task[TaskPushNotificationConfig] =
    authorizeRequest(context) *> requirePush *> ensureTask(params.taskId, context) *>
      pushStore.load(params.taskId, context.tenant).flatMap { configs =>
        configs.find(_.id.contains(params.id)) match
          case Some(config) => ZIO.succeed(config)
          case None         => ZIO.fail(A2AError.pushNotificationConfigNotFound(params.id))
      }

  def listPushConfigs(params: A2ARequest.PushNotificationConfigList, context: ServerCallContext)
    : Task[A2AResponse.PushNotificationConfigListResult] =
    authorizeRequest(context) *> requirePush *> ensureTask(params.taskId, context) *> validatePushConfigListParams(
      params
    ) *>
      pushStore
        .load(params.taskId, context.tenant)
        .map(paginatePushConfigs(_, params))

  def deletePushConfig(params: A2ARequest.PushNotificationConfigDelete, context: ServerCallContext): Task[Unit] =
    authorizeRequest(context) *> requirePush *> ensureTask(params.taskId, context) *>
      pushStore.delete(params.taskId, context.tenant, params.id)

  def getExtendedAgentCard(context: ServerCallContext): Task[AgentCard] =
    authorizeRequest(context) *> {
      if !agentCard.capabilities.extendedAgentCard then
        ZIO.fail(A2AError.unsupportedOperation(A2AMethod.GetAuthenticatedExtendedCard))
      else
        ZIO
          .fromOption(extendedAgentCardProvider())
          .orElseFail(A2AError.authenticatedExtendedCardNotConfigured)
          .flatMap(card => config.extendedAgentCardAuth.authorize(agentCard, context.authorization).as(card))
    }

  /**
   * Protocol-operation auth gate. Each handler calls it, AND transports may
   * call it up front in their dispatch layer as a single first line of defense
   * (so a handler that ever forgets is still covered). Idempotent.
   */
  def authorizeRequest(context: ServerCallContext): Task[Unit] =
    config.requestAuth.authorize(agentCard, context)

  private def messageResponse(
    params: A2ARequest.MessageSend,
    context: ServerCallContext,
  ): Task[Option[A2AResponse.SendMessageResult]] =
    config.messageResponseSelector match
      case Some(responder) if canReturnMessageResponse(params) =>
        for
          _        <- validateHistoryLength(params.configuration.flatMap(_.historyLength))
          _        <- validateInboundMessage(params.message)
          _        <- validateContextReference(params.message, None, context)
          response <- responder(params)
          result   <- response match
            case Some(message) =>
              val normalized = normalizeMessageResponse(message, params.message.contextId)
              validateOutboundMessage(normalized).as(Some(A2AResponse.SendMessageResult.MessageResult(normalized)))
            case None =>
              ZIO.none
        yield result
      case _ =>
        ZIO.none

  private def canReturnMessageResponse(params: A2ARequest.MessageSend): Boolean =
    params.message.taskId.isEmpty &&
      !params.configuration.exists(_.returnImmediately) &&
      params.configuration.flatMap(_.taskPushNotificationConfig).isEmpty

  private def normalizeMessageResponse(message: A2AMessage, fallbackContextId: Option[ContextId]): A2AMessage =
    message.copy(
      contextId = message.contextId.orElse(fallbackContextId),
      taskId = None,
    )

  private def prepare(params: A2ARequest.MessageSend, context: ServerCallContext): Task[PreparedRun] =
    val incoming = params.message
    for
      _        <- validateHistoryLength(params.configuration.flatMap(_.historyLength))
      _        <- validateInlinePushConfig(params.configuration)
      _        <- validateInboundMessage(incoming)
      existing <- incoming.taskId match
        case Some(id) =>
          taskStore.load(id, context.tenant).flatMap {
            case Some(task) => ZIO.succeed(Some(task))
            case None       => ZIO.fail(A2AError.taskNotFound(id))
          }
        case None => ZIO.none
      taskId = incoming.taskId.getOrElse(TaskId.generate)
      key    = taskRuntimeKey(taskId, context)
      _ <- existing match
        case Some(task) if task.isTerminal =>
          ZIO.fail(A2AError.unsupportedOperation(s"Task ${task.id.value} is terminal and cannot be modified"))
        case Some(task) if incoming.contextId.exists(_ != task.contextId) =>
          ZIO.fail(A2AError.invalidParams("contextId does not match task contextId"))
        case _ =>
          ZIO.unit
      _        <- validateContextReference(incoming, existing, context)
      maybeBus <- runtimeRegistry.reserve(key, config.eventReplayLimit)
      bus      <- maybeBus match
        case Some(bus) => ZIO.succeed(bus)
        case None      => ZIO.fail(A2AError.unsupportedOperation(s"Task ${taskId.value} already has an active run"))
      contextId = incoming.contextId.orElse(existing.map(_.contextId)).getOrElse(ContextId.generate)
      message   = incoming.copy(taskId = Some(taskId), contextId = Some(contextId))
      task      = existing
        .map(task => task.copy(status = TaskStatus.working(), history = task.history :+ message))
        .getOrElse(A2ATask(id = taskId, contextId = contextId, status = TaskStatus.working(), history = List(message)))
      _ <- taskStore.save(task, context.tenant)
    yield PreparedRun(message, task, bus)
    end for
  end prepare

  private def startExecution(prepared: PreparedRun, context: ServerCallContext): UIO[Unit] =
    val manager =
      ResultManager(taskStore, eventPersister, pushSender, prepared.bus, runtimeRegistry, context, prepared.message)
    val key = taskRuntimeKey(prepared.task.id, context)
    val run =
      manager.publish(A2AResponse.StreamEvent.TaskSnapshot(prepared.task)) *>
        executeRun(prepared, manager)
          .catchAll { error =>
            val detail       = Option(error.getMessage).getOrElse(error.getClass.getName)
            val errorMessage = A2AMessage
              .agentText(s"Error: $detail", Some(prepared.task.contextId))
              .copy(taskId = Some(prepared.task.id))
            manager.publish(
              A2AResponse.StreamEvent.TaskStatusUpdate(
                prepared.task.id,
                prepared.task.contextId,
                TaskStatus.failed(errorMessage),
                `final` = true,
              )
            )
          }
          .ensuring(
            runtimeRegistry.isCanceled(key).flatMap { canceled =>
              if canceled then ZIO.unit else manager.finish
            } *> runtimeRegistry.remove(key)
          )
    ZIO
      .succeed {
        Unsafe.unsafe { implicit unsafe => runtime.unsafe.fork(run) }
      }
      .flatMap(fiber => runtimeRegistry.attachFiber(key, fiber))
  end startExecution

  private def waitForFinal(
    taskId: TaskId,
    stream: ZStream[Any, Throwable, A2AResponse.StreamEvent],
    context: ServerCallContext,
  ): Task[A2ATask] =
    stream
      .filter(_.isFinal)
      .runHead
      .flatMap {
        case Some(_) => taskStore.load(taskId, context.tenant).someOrFail(A2AError.taskNotFound(taskId))
        case None    => ZIO.fail(A2AError.internalError(s"Terminal event never received for task ${taskId.value}"))
      }

  private def cleanupPrepared(prepared: PreparedRun, context: ServerCallContext): UIO[Unit] =
    runtimeRegistry.remove(taskRuntimeKey(prepared.task.id, context)) *> prepared.bus.finish

  private def validateInboundMessage(message: A2AMessage): Task[Unit] =
    if message.role != A2ARole.User then
      ZIO.fail(A2AError.invalidParams("message.role must be ROLE_USER for SendMessage"))
    else if message.parts.isEmpty then ZIO.fail(A2AError.invalidParams("message.parts must contain at least one part"))
    else ZIO.foreachDiscard(message.parts)(validateInboundPartMediaTypes)

  private def validateContextReference(
    message: A2AMessage,
    existingTask: Option[A2ATask],
    context: ServerCallContext,
  ): Task[Unit] =
    if existingTask.nonEmpty then ZIO.unit
    else
      message.contextId match
        case Some(contextId) => ensureKnownContextId(contextId, context)
        case None            => ZIO.unit

  private def ensureKnownContextId(contextId: ContextId, context: ServerCallContext): Task[Unit] =
    taskStore
      .list(A2ARequest.TasksList(contextId = Some(contextId), pageSize = Some(1)), context.tenant)
      .flatMap { result =>
        if result.tasks.nonEmpty then ZIO.unit
        else
          ZIO.fail(
            A2AError.invalidParams(
              s"contextId is not known: ${contextId.value}; omit contextId to start a new context"
            )
          )
      }

  private def validateInboundPartMediaTypes(part: Part): Task[Unit] =
    ZIO.foreachDiscard(explicitMediaTypes(part)) { mediaType =>
      if supportedInputMediaType(mediaType) then ZIO.unit
      else ZIO.fail(A2AError.contentTypeNotSupported(mediaType))
    }

  private def explicitMediaTypes(part: Part): List[String] =
    val values = part match
      case Part.Text(_, metadata, _, mediaType) =>
        mediaType.toList ++ metadataContentTypes(metadata)
      case Part.File(file, metadata) =>
        val declared = fileMediaType(file).toList ++ metadataContentTypes(metadata)
        // A file with no declared media type is still validated by inferring the
        // type from its filename extension (so e.g. `report.exe` is checked
        // against the agent's input modes). A truly-unguessable extension yields
        // no constraint — allow it rather than fail closed, which would reject
        // spec-legitimate clients that omit `mediaType` (it is optional).
        if declared.nonEmpty then declared
        else fileContentName(file).flatMap(A2AArtifactMimes.guess).toList
      case Part.Data(_, metadata, _, mediaType) =>
        mediaType.toList ++ metadataContentTypes(metadata)
    values.map(_.trim).filter(_.nonEmpty).distinct

  private def fileContentName(file: FileContent): Option[String] =
    file match
      case FileContent.Bytes(_, name, _) => name
      case FileContent.Uri(_, name, _)   => name

  private def metadataContentTypes(metadata: Option[Json]): List[String] =
    metadata.toList.flatMap(_.asObject.toList).flatMap { fields =>
      A2AJson
        .caseInsensitiveLookup(name => fields.toMap.get(name).flatMap(_.asString), "contentType", "content_type")
        .toList
    }

  private def fileMediaType(file: FileContent): Option[String] =
    file match
      case FileContent.Bytes(_, _, mimeType) => mimeType
      case FileContent.Uri(_, _, mimeType)   => mimeType

  private def supportedInputMediaType(mediaType: String): Boolean =
    val requested = normalizeMediaType(mediaType)
    supportedInputMediaTypes.exists(supported => mediaTypeMatches(requested, supported))

  // Skill input modes are UNIONED with (not intersected against) the card-level
  // defaultInputModes: a skill can only WIDEN the accepted surface, never narrow
  // it below the card default. An inbound part is accepted if its media type
  // matches any default OR any skill's declared input mode.
  private def supportedInputMediaTypes: List[String] =
    (agentCard.defaultInputModes ++ agentCard.skills.flatMap(_.inputModes))
      .map(normalizeMediaType)
      .filter(_.nonEmpty)
      .distinct

  private def normalizeMediaType(mediaType: String): String =
    A2AHttpBinding.mediaType(mediaType)

  private def mediaTypeMatches(requested: String, supported: String): Boolean =
    supported == requested ||
      supported == "*/*" ||
      (supported.endsWith("/*") && requested.startsWith(supported.stripSuffix("*")))

  private def validateOutboundMessage(message: A2AMessage): Task[Unit] =
    if message.role != A2ARole.Agent then
      ZIO.fail(A2AError.invalidAgentResponse("message.role must be ROLE_AGENT for SendMessageResponse.message"))
    else if message.parts.isEmpty then
      ZIO.fail(A2AError.invalidAgentResponse("message.parts must contain at least one part"))
    else ZIO.unit

  private def validatePushConfigListParams(params: A2ARequest.PushNotificationConfigList): Task[Unit] =
    params.pageSize match
      case Some(size) if size < 1 || size > 100 =>
        ZIO.fail(A2AError.invalidParams(s"pageSize must be between 1 and 100 inclusive, got $size"))
      case _ =>
        params.pageToken match
          case Some(token) if decodePushConfigPageToken(token).isEmpty =>
            ZIO.fail(A2AError.invalidParams("Invalid pageToken"))
          case _ =>
            ZIO.unit

  private def paginatePushConfigs(
    configs: List[TaskPushNotificationConfig],
    params: A2ARequest.PushNotificationConfigList,
  ): A2AResponse.PushNotificationConfigListResult =
    val pageSize = params.pageSize.getOrElse(50)
    val offset   = decodePushConfigPageToken(params.pageToken.getOrElse(""))
      .getOrElse(PushConfigPageToken.Offset(0)) match
      case PushConfigPageToken.Offset(value) => value
    val page = configs.drop(offset).take(pageSize)
    val next =
      if configs.length > offset + pageSize then Some(encodePushConfigPageToken(offset + pageSize))
      else None
    A2AResponse.PushNotificationConfigListResult(page, next)

  private def encodePushConfigPageToken(offset: Int): String =
    s"v1:${offset.toHexString}"

  private def decodePushConfigPageToken(raw: String): Option[PushConfigPageToken] =
    if raw.isEmpty then Some(PushConfigPageToken.Offset(0))
    else raw.toIntOption.filter(_ >= 0).map(PushConfigPageToken.Offset.apply).orElse(decodePushConfigCursor(raw))

  private def decodePushConfigCursor(raw: String): Option[PushConfigPageToken] =
    if !raw.startsWith("v1:") then None
    else
      Try(Integer.parseInt(raw.drop("v1:".length), 16)).toOption
        .filter(_ >= 0)
        .map(PushConfigPageToken.Offset.apply)

  private def saveInlinePushConfig(
    messageConfig: Option[MessageSendConfiguration],
    taskId: TaskId,
    context: ServerCallContext,
  ): Task[Unit] =
    messageConfig.flatMap(_.taskPushNotificationConfig) match
      case Some(pushConfig) =>
        validateInlinePushConfig(messageConfig) *> pushStore.save(taskId, context.tenant, pushConfig).unit
      case None =>
        ZIO.unit

  private def validateInlinePushConfig(messageConfig: Option[MessageSendConfiguration]): Task[Unit] =
    messageConfig.flatMap(_.taskPushNotificationConfig) match
      case Some(pushConfig) if agentCard.capabilities.pushNotifications =>
        pushConfig.taskId match
          case Some(taskId) if taskId.nonEmpty =>
            ZIO.fail(A2AError.invalidParams("taskPushNotificationConfig.taskId must be empty for SendMessage"))
          case _ =>
            config.pushNotificationUrlPolicy.validate(pushConfig.url)
      case Some(_) =>
        ZIO.fail(A2AError.pushNotificationNotSupported)
      case None =>
        ZIO.unit

  private def requirePush: Task[Unit] =
    ZIO.fail(A2AError.pushNotificationNotSupported).unless(agentCard.capabilities.pushNotifications).unit

  private def requireStreaming: Task[Unit] =
    ZIO.fail(A2AError.unsupportedOperation("Streaming not supported")).unless(agentCard.capabilities.streaming).unit

  private def validateHistoryLength(historyLength: Option[Int]): Task[Unit] =
    historyLength match
      case Some(length) if length < 0 =>
        ZIO.fail(A2AError.invalidParams(s"historyLength must be non-negative integer, got $length"))
      case _ =>
        ZIO.unit

  private def ensureTask(taskId: TaskId, context: ServerCallContext): Task[Unit] =
    taskStore.load(taskId, context.tenant).flatMap {
      case Some(_) => ZIO.unit
      case None    => ZIO.fail(A2AError.taskNotFound(taskId))
    }
end A2ARequestHandler
