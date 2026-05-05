package com.tjclp.scalagent.a2a

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import scala.scalajs.js.annotation.*
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.TimeoutException
import zio.*
import zio.stream.*
import zio.json.*
import zio.json.ast.Json
import com.tjclp.scalagent.{ClaudeAgent, CollectionPolicy, QueryCollector, QueryResult}
import com.tjclp.scalagent.config.*

/** A2A Server that exposes a Claude agent via the native A2A v1 protocol. */
trait A2AServer:
  /** Start the server. */
  def start: Task[Unit]

  /** Stop the server. */
  def stop: Task[Unit]

  /** Get the agent card for this server. */
  def agentCard: AgentCard

  /** Get the server URL. */
  def url: String

/** Minimal publisher exposed to server test overrides. */
trait A2AEventPublisher:
  def publish(event: A2AResponse.StreamEvent): UIO[Unit]
  def finish: UIO[Unit]

/**
 * Per-task A2A session logger. Writes JSONL to a configurable directory.
 *
 * This supplements Claude's native session transcripts with A2A-level events:
 * task IDs, prompts, completion status, and timing.
 */
object SessionLogger:
  @js.native
  @JSImport("node:fs",JSImport.Namespace)
  private object Fs extends js.Object:
    def appendFileSync(path: String, data: String): Unit   = js.native
    def mkdirSync(path: String, options: js.Dynamic): Unit = js.native
    def existsSync(path: String): Boolean                  = js.native

  private var logDir: Option[String] = None
  private var dirEnsured             = false

  /** Configure the log directory. Call before using logEvent. */
  def configure(dir: Option[String]): Unit =
    logDir = dir
    dirEnsured = false

  private def ensureDir(): Boolean =
    logDir match
      case None      => false
      case Some(dir) =>
        if !dirEnsured then
          try
            if !Fs.existsSync(dir) then Fs.mkdirSync(dir, js.Dynamic.literal(recursive = true))
            dirEnsured = true
          catch case _: Throwable => ()
        dirEnsured

  /** Log an A2A event. */
  def logEvent(
    taskId: String,
    event: String,
    data: String,
  ): Unit =
    if !ensureDir() then return
    val dir     = logDir.get
    val ts      = new js.Date().toISOString().asInstanceOf[String]
    val escaped = data
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      .replaceAll("[\\x00-\\x1f]", "")
    val line = s"""{"ts":"$ts","taskId":"$taskId","event":"$event","data":"$escaped"}"""
    try Fs.appendFileSync(s"$dir/$taskId.jsonl", line + "\n")
    catch case _: Throwable => ()
end SessionLogger

object A2AServer:

  /** Server configuration. */
  final case class Config(
    name: String,
    description: String,
    host: String = "localhost",
    port: Int = 3000,
    agentOptions: AgentOptions = AgentOptions.default,
    executionMode: ExecutionMode = ExecutionMode.Default,
    taskTimeout: Option[Duration] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default,
    skills: List[AgentSkill] = Nil,
    sessionLogDir: Option[String] = None,
    invocationPreparer: Option[(A2AMessage, TaskId) => Task[InvocationContext]] = None,
    executionOverride: Option[(A2AMessage, TaskId, ContextId, A2AEventPublisher) => Task[Unit]] = None,
    pushNotificationStore: Option[A2APushNotificationStore] = None,
    taskStore: Option[A2ATaskStore] = None):
    def url: String = s"http://$host:$port"

    def toAgentCard: AgentCard =
      AgentCard(
        name = name,
        description = description,
        supportedInterfaces = List(
          AgentInterface.jsonRpc(url),
          AgentInterface.rest(url),
        ),
        capabilities = capabilities,
        skills = skills,
      )
  end Config

  val workspaceStaging: (A2AMessage, TaskId) => Task[InvocationContext] =
    (message, taskId) => ZIO.attempt(WorkspaceStaging.stageFromMessage(message, taskId).toInvocationContext)

  /** Create and start an A2A server. */
  def create(config: Config): ZIO[Scope, Throwable, A2AServer] =
    for
      runtime <- ZIO.runtime[Any]
      server  <- ZIO.acquireRelease(start(config, runtime))(_.stop.ignore)
    yield server

  /** Start server without scope management. */
  def start(config: Config, runtime: Runtime[Any]): Task[A2AServer] =
    ZIO.attempt(A2AServerLive(config, runtime)).tap(_.start)

  /** Create a server layer. */
  def live(config: Config): ZLayer[Scope, Throwable, A2AServer] =
    ZLayer.fromZIO(create(config))
end A2AServer

private final case class ServerCallContext(
  tenant: Option[String] = None,
  requestedVersion: Option[String] = None,
  requestedExtensions: List[String] = Nil)

private type TaskRuntimeKey = (String, String)

private def taskRuntimeKey(taskId: TaskId, context: ServerCallContext): TaskRuntimeKey =
  (context.tenant.getOrElse(""), taskId.value)

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
 */
trait A2ATaskStore:
  def save(task: A2ATask, tenant: Option[String]): UIO[Unit]
  def load(taskId: TaskId, tenant: Option[String]): UIO[Option[A2ATask]]
  def list(params: A2ARequest.TasksList, tenant: Option[String]): UIO[A2AResponse.ListTasksResult]
  def delete(taskId: TaskId, tenant: Option[String]): UIO[Unit]
end A2ATaskStore

object A2ATaskStore:
  /** Default in-process store; non-persistent. */
  def inMemory: A2ATaskStore = new InMemoryTaskStoreImpl

  private[a2a] def applyHistoryLength(task: A2ATask, historyLength: Option[Int]): A2ATask =
    historyLength match
      case Some(length) if length <= 0 => task.copy(history = Nil)
      case Some(length)               => task.copy(history = task.history.takeRight(length))
      case None                       => task
end A2ATaskStore

private final class InMemoryTaskStoreImpl extends A2ATaskStore:
  private val tasks = mutable.Map.empty[(String, String), A2ATask]

  private def key(id: TaskId, tenant: Option[String]): (String, String) =
    (tenant.getOrElse(""), id.value)

  def save(task: A2ATask, tenant: Option[String]): UIO[Unit] =
    ZIO.succeed(tasks.update(key(task.id, tenant), task))

  def load(taskId: TaskId, tenant: Option[String]): UIO[Option[A2ATask]] =
    ZIO.succeed(tasks.get(key(taskId, tenant)))

  def delete(taskId: TaskId, tenant: Option[String]): UIO[Unit] =
    ZIO.succeed { tasks.remove(key(taskId, tenant)); () }

  def list(params: A2ARequest.TasksList, tenant: Option[String]): UIO[A2AResponse.ListTasksResult] =
    ZIO.succeed {
      val pageSize = params.pageSize.getOrElse(50).max(1).min(100)
      val all = tasks.collect {
        case ((t, _), task) if t == tenant.getOrElse("") => task
      }.toList
      val filtered = all
        .filter(task => params.contextId.forall(_ == task.contextId))
        .filter(task => params.status.forall(_ == task.status.state))
        .filter(task =>
          params.statusTimestampAfter.forall { after =>
            task.status.timestamp.exists(_ >= after)
          }
        )
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
end InMemoryTaskStoreImpl

private final class A2AEventBus:
  private val subscribers = mutable.Set.empty[Queue[Take[Throwable, A2AResponse.StreamEvent]]]
  private var history     = Vector.empty[A2AResponse.StreamEvent]
  private var closed      = false

  def publish(event: A2AResponse.StreamEvent): UIO[Unit] =
    if closed then ZIO.unit
    else
      history = history :+ event
      ZIO.foreachDiscard(subscribers.toList)(_.offer(Take.single(event))).unit

  def finish: UIO[Unit] =
    if closed then ZIO.unit
    else
      closed = true
      ZIO.foreachDiscard(subscribers.toList)(_.offer(Take.end)).unit

  def stream: ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrapScoped {
      for
        queue <- Queue.unbounded[Take[Throwable, A2AResponse.StreamEvent]]
        replay <- ZIO.acquireRelease {
          ZIO.succeed {
            val replay = history
            if !closed then subscribers += queue
            replay
          }
        }(_ => ZIO.succeed(subscribers -= queue).unit)
      yield
        val live =
          if closed then ZStream.empty
          else ZStream.fromQueue(queue).flattenTake
        ZStream.fromIterable(replay) ++ live
    }
end A2AEventBus

private final class PushNotificationSender(store: A2APushNotificationStore):
  private val deliveryChains = mutable.Map.empty[(String, String), Promise[Nothing, Unit]]

  def send(event: A2AResponse.StreamEvent, context: ServerCallContext): UIO[Unit] =
    val key = (context.tenant.getOrElse(""), event.taskId.value)
    (for
      previous <- ZIO.succeed(deliveryChains.get(key))
      current  <- Promise.make[Nothing, Unit]
      _        <- ZIO.succeed(deliveryChains.update(key, current))
      _ <-
        (previous.fold(ZIO.unit)(_.await) *> sendNow(event, context))
          .catchAll(error => ZIO.logWarning(s"Failed to send A2A push notification: ${error.getMessage}"))
          .ensuring(
            current.succeed(()).unit *>
              ZIO.succeed {
                if deliveryChains.get(key).contains(current) then deliveryChains.remove(key)
              }.unit
          )
          .forkDaemon
    yield ()).unit

  private def sendNow(event: A2AResponse.StreamEvent, context: ServerCallContext): Task[Unit] =
    store
      .load(event.taskId, context.tenant)
      .flatMap(configs => ZIO.foreachDiscard(configs)(sendOne(event, _)))

  private def sendOne(event: A2AResponse.StreamEvent, config: TaskPushNotificationConfig): Task[Unit] =
    ZIO.fromPromiseJS {
      val headers = js.Dynamic.literal(`Content-Type` = A2AContentType.A2AJson)
      config.authentication match
        case Some(auth) if auth.scheme.nonEmpty && auth.credentials.nonEmpty =>
          headers.updateDynamic("Authorization")(s"${auth.scheme} ${auth.credentials}")
        case _ =>
          config.token.foreach(token => headers.updateDynamic("X-A2A-Notification-Token")(token))
      val init = js.Dynamic.literal(
        method = "POST",
        headers = headers,
        body = event.toJson,
      )
      js.Dynamic.global.fetch(config.url, init).asInstanceOf[js.Promise[js.Dynamic]]
    }.flatMap { response =>
      val ok = response.selectDynamic("ok").asInstanceOf[Boolean]
      if ok then ZIO.unit
      else
        val status = response.selectDynamic("status").asInstanceOf[Int]
        ZIO.fail(new RuntimeException(s"Push callback ${config.url} returned HTTP $status"))
    }
end PushNotificationSender

private final class ResultManager(
  taskStore: A2ATaskStore,
  pushSender: PushNotificationSender,
  bus: A2AEventBus,
  context: ServerCallContext,
  userMessage: A2AMessage)
    extends A2AEventPublisher:

  override def publish(event: A2AResponse.StreamEvent): UIO[Unit] =
    applyEvent(event) *> bus.publish(event) *> pushSender.send(event, context)

  override def finish: UIO[Unit] =
    bus.finish

  private def applyEvent(event: A2AResponse.StreamEvent): UIO[Unit] =
    event match
      case A2AResponse.StreamEvent.TaskSnapshot(task) =>
        taskStore.save(ensureHistory(task), context.tenant)
      case A2AResponse.StreamEvent.TaskStatusUpdate(taskId, _, status, _, _) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) =>
            val history = status.message match
              case Some(message) if !task.history.exists(_.messageId == message.messageId) => task.history :+ message
              case _                                                                        => task.history
            taskStore.save(task.copy(status = status, history = history), context.tenant)
          case None =>
            ZIO.unit
        }
      case A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, _, artifact, append, _, _) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) =>
            val existingIndex = task.artifacts.indexWhere(_.artifactId == artifact.artifactId)
            val artifacts =
              if existingIndex < 0 then task.artifacts :+ artifact
              else if append then
                task.artifacts.updated(
                  existingIndex,
                  task.artifacts(existingIndex).copy(parts = task.artifacts(existingIndex).parts ++ artifact.parts),
                )
              else task.artifacts.updated(existingIndex, artifact)
            taskStore.save(task.copy(artifacts = artifacts), context.tenant)
          case None =>
            ZIO.unit
        }
      case A2AResponse.StreamEvent.TaskMessage(taskId, _, message) =>
        taskStore.load(taskId, context.tenant).flatMap {
          case Some(task) if !task.history.exists(_.messageId == message.messageId) =>
            taskStore.save(task.copy(history = task.history :+ message), context.tenant)
          case _ =>
            ZIO.unit
        }

  private def ensureHistory(task: A2ATask): A2ATask =
    if task.history.exists(_.messageId == userMessage.messageId) then task
    else task.copy(history = userMessage :: task.history)
end ResultManager

private final class A2ARequestHandler(
  config: A2AServer.Config,
  runtime: Runtime[Any],
  taskStore: A2ATaskStore,
  pushStore: A2APushNotificationStore,
  buses: mutable.Map[TaskRuntimeKey, A2AEventBus],
  activeRuns: mutable.Map[TaskRuntimeKey, Fiber.Runtime[Throwable, Unit]]):

  private val pushSender = PushNotificationSender(pushStore)

  def agentCard: AgentCard = config.toAgentCard

  def sendMessage(
    params: A2ARequest.MessageSend,
    context: ServerCallContext,
  ): Task[A2AResponse.SendMessageResult] =
    for
      prepared <- prepare(params, context)
      _        <- saveInlinePushConfig(params.configuration, prepared.task.id, context)
      stream    = prepared.bus.stream
      _        <- startExecution(prepared, context)
      result   <-
        if params.configuration.exists(_.returnImmediately) then ZIO.succeed(A2AResponse.SendMessageResult.TaskResult(prepared.task))
        else waitForFinal(prepared.task.id, stream, context).map(A2AResponse.SendMessageResult.TaskResult(_))
    yield result

  def sendMessageStream(
    params: A2ARequest.MessageSend,
    context: ServerCallContext,
  ): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    requireStreaming *> {
      for
        prepared <- prepare(params, context)
        _        <- saveInlinePushConfig(params.configuration, prepared.task.id, context)
        stream    = prepared.bus.stream
        _        <- startExecution(prepared, context)
      yield stream
    }

  def getTask(params: A2ARequest.TasksGet, context: ServerCallContext): Task[A2ATask] =
    taskStore.load(params.id, context.tenant).flatMap {
      case Some(task) => ZIO.succeed(applyHistoryLength(task, params.historyLength))
      case None       => ZIO.fail(A2AError.taskNotFound(params.id))
    }

  def listTasks(params: A2ARequest.TasksList, context: ServerCallContext): Task[A2AResponse.ListTasksResult] =
    taskStore.list(params, context.tenant)

  def cancelTask(params: A2ARequest.TasksCancel, context: ServerCallContext): Task[A2ATask] =
    taskStore.load(params.id, context.tenant).flatMap {
      case Some(task) if task.isTerminal =>
        ZIO.fail(A2AError.taskNotCancelable(params.id))
      case Some(task) =>
        val canceled = task.copy(status = TaskStatus.canceled)
        val event = A2AResponse.StreamEvent.TaskStatusUpdate(
          params.id,
          task.contextId,
          canceled.status,
          `final` = true,
        )
        val key = taskRuntimeKey(params.id, context)
        val interruptActiveRun = ZIO.succeed {
          activeRuns.remove(key).foreach { fiber =>
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.fork(fiber.interrupt.unit)
            }
          }
        }
        val closeBus =
          buses.get(key) match
            case Some(bus) => bus.publish(event) *> bus.finish
            case None      => ZIO.unit
        interruptActiveRun *> taskStore.save(canceled, context.tenant) *> closeBus *> pushSender.send(event, context).as(canceled)
      case None =>
        ZIO.fail(A2AError.taskNotFound(params.id))
    }

  def resubscribe(params: A2ARequest.TasksResubscribe, context: ServerCallContext): Task[ZStream[Any, Throwable, A2AResponse.StreamEvent]] =
    requireStreaming *> taskStore.load(params.id, context.tenant).flatMap {
      case Some(task) if task.isTerminal =>
        ZIO.fail(A2AError.unsupportedOperation(s"Task ${params.id.value} is terminal"))
      case Some(task) =>
        buses.get(taskRuntimeKey(params.id, context)) match
          case Some(bus) => ZIO.succeed(ZStream.succeed(A2AResponse.StreamEvent.TaskSnapshot(task)) ++ bus.stream)
          case None      => ZIO.fail(A2AError.unsupportedOperation(s"No active stream for task ${params.id.value}"))
      case None =>
        ZIO.fail(A2AError.taskNotFound(params.id))
    }

  def createPushConfig(configParam: TaskPushNotificationConfig, context: ServerCallContext): Task[TaskPushNotificationConfig] =
    requirePush *> {
      val taskId = configParam.taskId.getOrElse(TaskId(""))
      if taskId.isEmpty then ZIO.fail(A2AError.invalidParams("taskId is required"))
      else ensureTask(taskId, context) *> pushStore.save(taskId, context.tenant, configParam)
    }

  def getPushConfig(params: A2ARequest.PushNotificationConfigGet, context: ServerCallContext): Task[TaskPushNotificationConfig] =
    requirePush *> ensureTask(params.taskId, context) *>
      pushStore.load(params.taskId, context.tenant).flatMap { configs =>
        configs.find(_.id.contains(params.id)) match
          case Some(config) => ZIO.succeed(config)
          case None         => ZIO.fail(A2AError.invalidParams(s"Push notification config not found: ${params.id}"))
      }

  def listPushConfigs(params: A2ARequest.PushNotificationConfigList, context: ServerCallContext): Task[A2AResponse.PushNotificationConfigListResult] =
    requirePush *> ensureTask(params.taskId, context) *>
      pushStore.load(params.taskId, context.tenant).map(configs => A2AResponse.PushNotificationConfigListResult(configs))

  def deletePushConfig(params: A2ARequest.PushNotificationConfigDelete, context: ServerCallContext): Task[Unit] =
    requirePush *> ensureTask(params.taskId, context) *> pushStore.delete(params.taskId, context.tenant, params.id)

  def getExtendedAgentCard(context: ServerCallContext): Task[AgentCard] =
    if config.capabilities.extendedAgentCard then ZIO.succeed(agentCard)
    else ZIO.fail(A2AError.authenticatedExtendedCardNotConfigured)

  private final case class PreparedRun(
    message: A2AMessage,
    task: A2ATask,
    bus: A2AEventBus)

  private def prepare(params: A2ARequest.MessageSend, context: ServerCallContext): Task[PreparedRun] =
    val incoming = params.message
    val taskId   = incoming.taskId.getOrElse(TaskId.generate)
    for
      existing <- taskStore.load(taskId, context.tenant)
      _        <- existing match
        case Some(task) if task.isTerminal =>
          ZIO.fail(A2AError.unsupportedOperation(s"Task ${task.id.value} is terminal and cannot be modified"))
        case Some(task) if incoming.contextId.exists(_ != task.contextId) =>
          ZIO.fail(A2AError.invalidParams("contextId does not match task contextId"))
        case _ =>
          ZIO.unit
      contextId = incoming.contextId.orElse(existing.map(_.contextId)).getOrElse(ContextId.generate)
      message   = incoming.copy(taskId = Some(taskId), contextId = Some(contextId))
      task = existing
        .map(task => task.copy(status = TaskStatus.working(), history = task.history :+ message))
        .getOrElse(A2ATask(id = taskId, contextId = contextId, status = TaskStatus.working(), history = List(message)))
      bus = buses.getOrElseUpdate(taskRuntimeKey(taskId, context), A2AEventBus())
      _  <- taskStore.save(task, context.tenant)
    yield PreparedRun(message, task, bus)

  private def startExecution(prepared: PreparedRun, context: ServerCallContext): UIO[Unit] =
    val manager = ResultManager(taskStore, pushSender, prepared.bus, context, prepared.message)
    val key     = taskRuntimeKey(prepared.task.id, context)
    val run =
      manager.publish(A2AResponse.StreamEvent.TaskSnapshot(prepared.task)) *>
        execute(prepared, manager).catchAll { error =>
          val errorMessage = A2AMessage
            .agentText(s"Error: ${error.getMessage}", Some(prepared.task.contextId))
            .copy(taskId = Some(prepared.task.id))
          manager.publish(
            A2AResponse.StreamEvent.TaskStatusUpdate(
              prepared.task.id,
              prepared.task.contextId,
              TaskStatus.failed(errorMessage),
              `final` = true,
            )
          )
        }.ensuring(
          manager.finish *>
            ZIO.succeed {
              activeRuns.remove(key)
              buses.remove(key)
            }.unit
        )
    ZIO.succeed {
      Unsafe.unsafe { implicit unsafe =>
        activeRuns.update(key, runtime.unsafe.fork(run))
      }
    }

  private def execute(prepared: PreparedRun, publisher: A2AEventPublisher): Task[Unit] =
    config.executionOverride match
      case Some(overrideRun) =>
        overrideRun(prepared.message, prepared.task.id, prepared.task.contextId, publisher)
      case None =>
        val preparedInvocation =
          config.invocationPreparer match
            case Some(prep) => prep(prepared.message, prepared.task.id)
            case None       => ZIO.succeed(InvocationContext(prompt = prepared.message.text))

        val effect: Task[(QueryResult, List[Artifact])] =
          preparedInvocation.flatMap { invocation =>
            SessionLogger.logEvent(prepared.task.id.value, "prompt", invocation.prompt)
            ClaudeAgent
              .queryComplete(
                invocation.prompt,
                invocation.optionsModifier(config.agentOptions),
                collectionPolicy = CollectionPolicy.ResultOnly,
                sink = progressSink(prepared.task.id, prepared.task.contextId, publisher),
              )
              .provideLayer(ClaudeAgent.live)
              .flatMap(result => invocation.artifactsAfter.map(artifacts => (result, artifacts)))
              .ensuring(invocation.cleanup.ignore)
          }

        withTaskTimeout(prepared.task.id, effect).flatMap {
          case (queryResult, artifacts) =>
            val responseText =
              queryResult.outcome.resultText
                .orElse(queryResult.semanticText.toOption)
                .getOrElse("Error: " + queryResult.outcome.toString)
            val responseMsg = A2AMessage
              .agentText(responseText, Some(prepared.task.contextId))
              .copy(taskId = Some(prepared.task.id))
            SessionLogger.logEvent(prepared.task.id.value, "completed", responseText.take(500))
            ZIO.foreachDiscard(artifacts)(artifact =>
              publisher.publish(
                A2AResponse.StreamEvent.TaskArtifactUpdate(
                  prepared.task.id,
                  prepared.task.contextId,
                  artifact,
                  append = false,
                  lastChunk = true,
                )
              )
            ) *>
              publisher.publish(
                A2AResponse.StreamEvent.TaskStatusUpdate(
                  prepared.task.id,
                  prepared.task.contextId,
                  TaskStatus.completed(responseMsg),
                  `final` = true,
                )
              )
        }

  private def progressSink(taskId: TaskId, contextId: ContextId, publisher: A2AEventPublisher): QueryCollector.MessageSink =
    var state = A2AProgress.State.empty
    message =>
      val (nextState, statusMessage) = A2AProgress.statusMessage(message, contextId, taskId, state)
      state = nextState
      statusMessage match
        case Some(progressMessage) =>
          publisher.publish(
            A2AResponse.StreamEvent.TaskStatusUpdate(
              taskId,
              contextId,
              TaskStatus.working(Some(progressMessage)),
            )
          )
        case None =>
          ZIO.unit

  private def withTaskTimeout[A](taskId: TaskId, effect: Task[A]): Task[A] =
    config.taskTimeout match
      case Some(timeout) =>
        effect.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} timed out after $timeout"))(timeout)
      case None =>
        effect

  private def waitForFinal(
    taskId: TaskId,
    stream: ZStream[Any, Throwable, A2AResponse.StreamEvent],
    context: ServerCallContext,
  ): Task[A2ATask] =
    stream
      .filter(_.isFinal)
      .runHead
      .flatMap(_ => taskStore.load(taskId, context.tenant).someOrFail(A2AError.taskNotFound(taskId)))

  private def applyHistoryLength(task: A2ATask, historyLength: Option[Int]): A2ATask =
    historyLength match
      case Some(length) if length <= 0 => task.copy(history = Nil)
      case Some(length)               => task.copy(history = task.history.takeRight(length))
      case None                       => task

  private def saveInlinePushConfig(
    config: Option[MessageSendConfiguration],
    taskId: TaskId,
    context: ServerCallContext,
  ): Task[Unit] =
    config.flatMap(_.taskPushNotificationConfig) match
      case Some(pushConfig) if agentCard.capabilities.pushNotifications =>
        pushStore.save(taskId, context.tenant, pushConfig).unit
      case Some(_) =>
        ZIO.fail(A2AError.pushNotificationNotSupported)
      case None =>
        ZIO.unit

  private def requirePush: Task[Unit] =
    ZIO.fail(A2AError.pushNotificationNotSupported).unless(agentCard.capabilities.pushNotifications).unit

  private def requireStreaming: Task[Unit] =
    ZIO.fail(A2AError.unsupportedOperation("Streaming not supported")).unless(agentCard.capabilities.streaming).unit

  private def ensureTask(taskId: TaskId, context: ServerCallContext): Task[Unit] =
    taskStore.load(taskId, context.tenant).flatMap {
      case Some(_) => ZIO.unit
      case None    => ZIO.fail(A2AError.taskNotFound(taskId))
    }
end A2ARequestHandler

/** Live implementation of A2A Server. */
private final class A2AServerLive(config: A2AServer.Config, runtime: Runtime[Any]) extends A2AServer:

  private var bunServer: js.Dynamic = null
  private val activeRuns            = mutable.Map.empty[TaskRuntimeKey, Fiber.Runtime[Throwable, Unit]]
  private val buses                 = mutable.Map.empty[TaskRuntimeKey, A2AEventBus]
  private val taskStore             = config.taskStore.getOrElse(A2ATaskStore.inMemory)
  private val pushStore             = config.pushNotificationStore.getOrElse(A2APushNotificationStore.inMemory)
  private val requestHandler        = A2ARequestHandler(config, runtime, taskStore, pushStore, buses, activeRuns)

  override def agentCard: AgentCard = config.toAgentCard

  override def url: String = config.url

  override def start: Task[Unit] =
    ZIO.attempt {
      SessionLogger.configure(config.sessionLogDir)
      bunServer = BunServer.serve(
        js.Dynamic.literal(
          hostname = config.host,
          port = config.port,
          fetch = createFetchHandler,
        )
      )
      ()
    }

  override def stop: Task[Unit] =
    ZIO.foreachDiscard(activeRuns.values.toList)(_.interrupt).ignore *>
      ZIO.attempt {
        activeRuns.clear()
        buses.clear()
        if bunServer != null then bunServer.stop()
        ()
      }

  private def createFetchHandler: js.Function1[js.Dynamic, js.Promise[js.Dynamic]] =
    (req: js.Dynamic) =>
      val method   = req.method.asInstanceOf[String]
      val urlObj   = js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url.asInstanceOf[String])
      val pathname = urlObj.pathname.asInstanceOf[String]

      if pathname == A2APaths.AgentCard && method == "GET" then
        js.Promise.resolve(jsonResponse(agentCard))
      else if pathname == "/" && method == "POST" then
        req
          .text()
          .asInstanceOf[js.Promise[String]]
          .`then`[js.Dynamic](body => runToPromise(handleJsonRpc(body, contextFrom(req, None))))
      else
        routeRest(method, pathname, urlObj, req) match
          case Some(effect) => runToPromise(effect)
          case None         => js.Promise.resolve(textResponse("Not Found", 404, "text/plain"))

  private def contextFrom(req: js.Dynamic, tenant: Option[String]): ServerCallContext =
    val headers = req.selectDynamic("headers")
    def header(name: String): Option[String] =
      if js.isUndefined(headers) || headers == null then None
      else
        val value = headers.asInstanceOf[js.Dynamic].get(name)
        if js.isUndefined(value) || value == null then None
        else Some(value.asInstanceOf[String])
    ServerCallContext(
      tenant = tenant,
      requestedVersion = header(A2AHeader.Version),
      requestedExtensions = header(A2AHeader.StandardExtensions)
        .orElse(header(A2AHeader.Extensions))
        .toList
        .flatMap(_.split(",").map(_.trim).filter(_.nonEmpty)),
    )

  private def validateServiceParameters(context: ServerCallContext, binding: A2ATransport): Task[Unit] =
    validateVersion(context, binding) *> validateRequiredExtensions(context)

  private def validateVersion(context: ServerCallContext, binding: A2ATransport): Task[Unit] =
    val supported = agentCard.supportedInterfaces.filter(_.protocolBinding == binding).map(_.protocolVersion).toSet
    val version   = context.requestedVersion.getOrElse("0.3")
    if supported.contains(version) then ZIO.unit
    else ZIO.fail(A2AError.versionNotSupported(version))

  private def validateRequiredExtensions(context: ServerCallContext): Task[Unit] =
    val requested = context.requestedExtensions.toSet
    config.capabilities.extensions.find(extension => extension.required && !requested.contains(extension.uri)) match
      case Some(extension) => ZIO.fail(A2AError.extensionSupportRequired(extension.uri))
      case None            => ZIO.unit

  private def handleJsonRpc(body: String, context: ServerCallContext): Task[js.Dynamic] =
    ZIO
      .fromEither(body.fromJson[JsonRpcRequest].left.map(A2AError.invalidRequest))
      .foldZIO(
        error => ZIO.succeed(jsonResponse(JsonRpcResponse.fromA2AError(None, error))),
        request =>
          val isStreaming = request.method == A2AMethod.MessageStream || request.method == A2AMethod.TasksResubscribe
          val routed =
            if isStreaming then handleJsonRpcStream(request, context)
            else handleJsonRpcSingle(request, context).map(response => jsonResponse(response))
          (validateServiceParameters(context, A2ATransport.JSONRPC) *> routed)
            .catchAll(error => ZIO.succeed(jsonResponse(JsonRpcResponse.fromA2AError(request.id, toA2AError(error)))))
      )

  private def handleJsonRpcSingle(request: JsonRpcRequest, context: ServerCallContext): Task[JsonRpcResponse] =
    request.method match
      case A2AMethod.MessageSend =>
        paramsAs[A2ARequest.MessageSend](request).flatMap(requestHandler.sendMessage(_, withTenantFromParams(context, request.params))).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksGet =>
        paramsAs[A2ARequest.TasksGet](request).flatMap(requestHandler.getTask(_, withTenantFromParams(context, request.params))).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksList =>
        paramsAs[A2ARequest.TasksList](request).flatMap(requestHandler.listTasks(_, withTenantFromParams(context, request.params))).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.TasksCancel =>
        paramsAs[A2ARequest.TasksCancel](request).flatMap(requestHandler.cancelTask(_, withTenantFromParams(context, request.params))).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigSet =>
        paramsAs[TaskPushNotificationConfig](request).flatMap(requestHandler.createPushConfig(_, withTenantFromParams(context, request.params))).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigGet =>
        paramsAs[A2ARequest.PushNotificationConfigGet](request).flatMap(requestHandler.getPushConfig(_, withTenantFromParams(context, request.params))).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigList =>
        paramsAs[A2ARequest.PushNotificationConfigList](request).flatMap(requestHandler.listPushConfigs(_, withTenantFromParams(context, request.params))).map(JsonRpcResponse.success(request.id, _))
      case A2AMethod.PushNotificationConfigDelete =>
        paramsAs[A2ARequest.PushNotificationConfigDelete](request).flatMap(requestHandler.deletePushConfig(_, withTenantFromParams(context, request.params))).as(JsonRpcResponse.success(request.id, Json.Null))
      case A2AMethod.GetAuthenticatedExtendedCard =>
        requestHandler.getExtendedAgentCard(context).map(JsonRpcResponse.success(request.id, _))
      case other =>
        ZIO.fail(A2AError.methodNotFound(other))

  private def handleJsonRpcStream(request: JsonRpcRequest, context: ServerCallContext): Task[js.Dynamic] =
    val streamTask =
      request.method match
        case A2AMethod.MessageStream =>
          paramsAs[A2ARequest.MessageSend](request).flatMap(requestHandler.sendMessageStream(_, withTenantFromParams(context, request.params)))
        case A2AMethod.TasksResubscribe =>
          paramsAs[A2ARequest.TasksResubscribe](request).flatMap(requestHandler.resubscribe(_, withTenantFromParams(context, request.params)))
        case other =>
          ZIO.fail(A2AError.methodNotFound(other))
    streamTask.map { stream =>
      sseResponse(
        stream.map(event => JsonRpcResponse.success(request.id, event).toJson),
        isJsonRpc = true,
      )
    }

  private def paramsAs[A: JsonDecoder](request: JsonRpcRequest): Task[A] =
    ZIO.fromEither(request.params.toRight(A2AError.invalidParams("Missing params")).flatMap(_.as[A].left.map(A2AError.invalidParams)))

  private def withTenantFromParams(context: ServerCallContext, params: Option[Json]): ServerCallContext =
    params
      .flatMap(_.asObject)
      .flatMap(_.toMap.get("tenant"))
      .flatMap(_.asString)
      .filter(_.nonEmpty)
      .fold(context)(tenant => context.copy(tenant = Some(tenant)))

  private def routeRest(
    method: String,
    pathname: String,
    urlObj: js.Dynamic,
    req: js.Dynamic,
  ): Option[Task[js.Dynamic]] =
    val (tenant, path) = splitTenant(pathname)
    val query          = urlObj.searchParams.asInstanceOf[js.Dynamic]
    def queryString(name: String): Option[String] =
      val value = query.get(name)
      if js.isUndefined(value) || value == null then None
      else Some(value.asInstanceOf[String])
    val baseContext = contextFrom(req, tenant)
    val context = baseContext.copy(
      requestedVersion = baseContext.requestedVersion
        .orElse(queryString(A2AHeader.Version))
        .orElse(queryString("a2aVersion")),
    )
    def bodyAs[A: JsonDecoder]: Task[A] =
      ZIO.fromPromiseJS(req.text().asInstanceOf[js.Promise[String]]).flatMap { body =>
        ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidRequest))
      }
    def json[A: JsonEncoder](effect: Task[A], status: Int = 200): Task[js.Dynamic] =
      (validateServiceParameters(context, A2ATransport.HTTP_JSON) *> effect)
        .map(value => jsonResponse(value, status, A2AContentType.A2AJson))
        .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))

    (method, path) match
      case ("POST", "/message:send") =>
        Some(json(bodyAs[A2ARequest.MessageSend].flatMap(requestHandler.sendMessage(_, context))))
      case ("POST", "/message:stream") =>
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            bodyAs[A2ARequest.MessageSend]
              .flatMap(requestHandler.sendMessageStream(_, context))
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case ("GET", "/tasks") =>
        val params = A2ARequest.TasksList(
          contextId = queryString("contextId").map(ContextId(_)),
          status = queryString("status").flatMap(value => Json.Str(value).as[TaskState].toOption),
          pageSize = queryString("pageSize").flatMap(_.toIntOption),
          pageToken = queryString("pageToken"),
          historyLength = queryString("historyLength").flatMap(_.toIntOption),
          statusTimestampAfter = queryString("statusTimestampAfter"),
          includeArtifacts = queryString("includeArtifacts").flatMap(_.toBooleanOption),
          tenant = tenant,
        )
        Some(json(requestHandler.listTasks(params, context)))
      case ("GET", taskPath) if taskPath.startsWith("/tasks/") && !taskPath.contains(":") && !taskPath.contains("/pushNotificationConfigs") =>
        val taskId = TaskId(taskPath.stripPrefix("/tasks/"))
        Some(json(requestHandler.getTask(A2ARequest.TasksGet(taskId, queryString("historyLength").flatMap(_.toIntOption), tenant), context)))
      case ("POST", taskPath) if taskPath.startsWith("/tasks/") && taskPath.endsWith(":cancel") =>
        val taskId = TaskId(taskPath.stripPrefix("/tasks/").stripSuffix(":cancel"))
        Some(json(requestHandler.cancelTask(A2ARequest.TasksCancel(taskId, tenant = tenant), context), status = 202))
      case (verb, taskPath) if (verb == "GET" || verb == "POST") && taskPath.startsWith("/tasks/") && taskPath.endsWith(":subscribe") =>
        val taskId = TaskId(taskPath.stripPrefix("/tasks/").stripSuffix(":subscribe"))
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            requestHandler
              .resubscribe(A2ARequest.TasksResubscribe(taskId, tenant), context)
              .map(stream => sseResponse(stream.map(_.toJson), isJsonRpc = false)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case ("POST", pushPath) if pushPath.startsWith("/tasks/") && pushPath.endsWith("/pushNotificationConfigs") =>
        val taskId = TaskId(pushPath.stripPrefix("/tasks/").stripSuffix("/pushNotificationConfigs"))
        Some(json(bodyAs[TaskPushNotificationConfig].map(_.copy(taskId = Some(taskId), tenant = tenant)).flatMap(requestHandler.createPushConfig(_, context)), status = 201))
      case ("GET", pushPath) if pushPath.startsWith("/tasks/") && pushPath.endsWith("/pushNotificationConfigs") =>
        val taskId = TaskId(pushPath.stripPrefix("/tasks/").stripSuffix("/pushNotificationConfigs"))
        Some(json(requestHandler.listPushConfigs(A2ARequest.PushNotificationConfigList(taskId, tenant = tenant), context)))
      case ("GET", pushPath) if pushPath.startsWith("/tasks/") && pushPath.contains("/pushNotificationConfigs/") =>
        val parts = pushPath.stripPrefix("/tasks/").split("/pushNotificationConfigs/", 2)
        Some(json(requestHandler.getPushConfig(A2ARequest.PushNotificationConfigGet(TaskId(parts(0)), parts(1), tenant), context)))
      case ("DELETE", pushPath) if pushPath.startsWith("/tasks/") && pushPath.contains("/pushNotificationConfigs/") =>
        val parts = pushPath.stripPrefix("/tasks/").split("/pushNotificationConfigs/", 2)
        Some(
          (validateServiceParameters(context, A2ATransport.HTTP_JSON) *>
            requestHandler
              .deletePushConfig(A2ARequest.PushNotificationConfigDelete(TaskId(parts(0)), parts(1), tenant), context)
              .as(emptyResponse(204)))
            .catchAll(error => ZIO.succeed(restErrorResponse(toA2AError(error))))
        )
      case ("GET", "/extendedAgentCard") =>
        Some(json(requestHandler.getExtendedAgentCard(context)))
      case _ =>
        None

  private def splitTenant(pathname: String): (Option[String], String) =
    val knownPrefixes = Set("message:send", "message:stream", "tasks", "extendedAgentCard")
    val segments      = pathname.stripPrefix("/").split("/").filter(_.nonEmpty).toList
    segments match
      case first :: rest if !knownPrefixes.contains(first) =>
        Some(first) -> ("/" + rest.mkString("/"))
      case _ =>
        None -> pathname

  private def toA2AError(error: Throwable): A2AError =
    error match
      case err: A2AError => err
      case other         => A2AError.internalError(other.getMessage)

  private def runToPromise(effect: Task[js.Dynamic]): js.Promise[js.Dynamic] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect).toJSPromise
    }

  private def jsonResponse[A: JsonEncoder](
    value: A,
    status: Int = 200,
    contentType: String = A2AContentType.Json,
  ): js.Dynamic =
    textResponse(value.toJson, status, contentType)

  private def restErrorResponse(error: A2AError): js.Dynamic =
    val status = error.code match
      case A2AErrorCode.TaskNotFound => 404
      case _                         => 400
    val body = Json.Obj(
      "error" -> Json.Obj(
        "code"    -> Json.Num(java.math.BigDecimal.valueOf(status.toLong)),
        "status"  -> Json.Str(if status == 404 then "NOT_FOUND" else "INVALID_ARGUMENT"),
        "message" -> Json.Str(error.message),
        "details" -> Json.Arr(
          Json.Obj(
            "@type"  -> Json.Str("type.googleapis.com/google.rpc.ErrorInfo"),
            "reason" -> Json.Str(errorReason(error.code)),
            "domain" -> Json.Str("a2a-protocol.org"),
          )
        ),
      )
    )
    textResponse(body.toJson, status, A2AContentType.A2AJson)

  private def errorReason(code: Int): String =
    code match
      case A2AErrorCode.TaskNotFound                 => "TASK_NOT_FOUND"
      case A2AErrorCode.TaskNotCancelable            => "TASK_NOT_CANCELABLE"
      case A2AErrorCode.PushNotificationNotSupported => "PUSH_NOTIFICATION_NOT_SUPPORTED"
      case A2AErrorCode.UnsupportedOperation         => "UNSUPPORTED_OPERATION"
      case A2AErrorCode.ContentTypeNotSupported      => "CONTENT_TYPE_NOT_SUPPORTED"
      case A2AErrorCode.InvalidAgentResponse         => "INVALID_AGENT_RESPONSE"
      case A2AErrorCode.VersionNotSupported          => "VERSION_NOT_SUPPORTED"
      case A2AErrorCode.ExtensionSupportRequired     => "EXTENSION_SUPPORT_REQUIRED"
      case _                                         => "INVALID_PARAMS"

  private def emptyResponse(status: Int): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      null,
      js.Dynamic.literal(status = status, headers = js.Dynamic.literal()),
    )

  private def textResponse(
    body: String,
    status: Int,
    contentType: String,
  ): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      body,
      js.Dynamic.literal(status = status, headers = js.Dynamic.literal(`Content-Type` = contentType)),
    )

  private def sseResponse(
    stream: ZStream[Any, Throwable, String],
    isJsonRpc: Boolean,
  ): js.Dynamic =
    val encoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    var fiber: Fiber.Runtime[Throwable, Unit] = null
    var canceled = false
    val readable = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(
      js.Dynamic.literal(
        start = (controller: js.Dynamic) =>
          val run =
            stream
              .runForeach { data =>
                ZIO.attempt {
                  if !canceled then controller.enqueue(encoder.encode(s"data: $data\n\n"))
                }
              }
              .catchAll { error =>
                ZIO.attempt {
                  if !canceled then controller.enqueue(encoder.encode(s"event: error\ndata: ${streamErrorJson(error, isJsonRpc)}\n\n"))
                }
              }
              .ensuring(ZIO.attempt(if !canceled then controller.close()).ignore)
          Unsafe.unsafe { implicit unsafe =>
            fiber = runtime.unsafe.fork(run)
          }
        ,
        cancel = (_: js.Any) =>
          canceled = true
          if fiber != null then Unsafe.unsafe { implicit unsafe => runtime.unsafe.fork(fiber.interrupt) }
          (),
      )
    )
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      readable,
      js.Dynamic.literal(
        status = 200,
        headers = js.Dynamic.literal(
          `Content-Type` = A2AContentType.Sse,
          `Cache-Control` = "no-cache",
          Connection = "keep-alive",
          `X-Accel-Buffering` = "no",
        ),
      ),
    )

  private def streamErrorJson(error: Throwable, isJsonRpc: Boolean): String =
    val a2aError = toA2AError(error)
    if isJsonRpc then JsonRpcResponse.fromA2AError(None, a2aError).toJson
    else restErrorResponseBody(a2aError).toJson

  private def restErrorResponseBody(error: A2AError): Json =
    Json.Obj(
      "error" -> Json.Obj(
        "code"    -> Json.Num(java.math.BigDecimal.valueOf(400L)),
        "status"  -> Json.Str("INVALID_ARGUMENT"),
        "message" -> Json.Str(error.message),
        "details" -> Json.Arr(
          Json.Obj(
            "@type"  -> Json.Str("type.googleapis.com/google.rpc.ErrorInfo"),
            "reason" -> Json.Str(errorReason(error.code)),
            "domain" -> Json.Str("a2a-protocol.org"),
          )
        ),
      )
    )
end A2AServerLive

/** Bun.serve binding. */
@js.native
@JSGlobal("Bun")
private object BunServer extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
