package com.tjclp.scalagent.a2a

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSON as JsJSON
import scala.scalajs.js.annotation.*
import java.util.concurrent.TimeoutException
import zio.*
import zio.stream.*
import com.tjclp.scalagent.{ClaudeAgent, CollectionPolicy, QueryCollector}
import com.tjclp.scalagent.config.AgentOptions
import com.tjclp.scalagent.a2a.facade.*
import com.tjclp.scalagent.streaming.{AsyncIterator, AsyncIteratorOps}

/** Legacy A2A 0.3 client backed by `@a2a-js/sdk`. Prefer [[A2AClient]] for v1. */
trait A2AClientV03:
  def agentCard: Task[AgentCard]
  def send(message: A2AMessage, config: Option[MessageSendConfiguration] = None): Task[A2ATask]

  def submit(message: A2AMessage, config: Option[MessageSendConfiguration] = None): Task[A2ATask] =
    val asyncConfig = config
      .getOrElse(MessageSendConfiguration.default)
      .copy(returnImmediately = true)
    send(message, Some(asyncConfig))

  def awaitTask(
    taskId: TaskId,
    pollEvery: Duration = 1.second,
    timeout: Option[Duration] = None,
    historyLength: Option[Int] = None,
  ): Task[A2ATask] =
    def loop: Task[A2ATask] =
      getTask(taskId, historyLength).flatMap { task =>
        if task.isTerminal then ZIO.succeed(task)
        else ZIO.sleep(pollEvery) *> loop
      }

    timeout match
      case Some(duration) =>
        loop.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} did not finish within $duration"))(duration)
      case None =>
        loop

  def sendAndPoll(
    message: A2AMessage,
    config: Option[MessageSendConfiguration] = None,
    pollEvery: Duration = 1.second,
    timeout: Option[Duration] = None,
    historyLength: Option[Int] = None,
  ): Task[A2ATask] =
    submit(message, config).flatMap { task =>
      if task.isTerminal then ZIO.succeed(task)
      else awaitTask(task.id, pollEvery, timeout, historyLength)
    }

  def stream(message: A2AMessage, config: Option[MessageSendConfiguration] = None)
    : ZStream[Any, Throwable, A2AResponse.StreamEvent]
  def getTask(taskId: TaskId, historyLength: Option[Int] = None): Task[A2ATask]
  def cancelTask(taskId: TaskId): Task[A2ATask]
  def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent]
  def getAgentCard: Task[AgentCard]
  def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig]
  def getPushNotificationConfig(taskId: TaskId, configId: Option[String] = None): Task[PushNotificationConfig]
  def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]]
  def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit]
end A2AClientV03

object A2AClientV03:
  final case class Config(
    url: String,
    headers: Map[String, String] = Map.empty)

  def discover(url: String, headers: Map[String, String] = Map.empty): Task[A2AClientV03] =
    ZIO
      .fail(new IllegalArgumentException(s"Invalid URL scheme. Must be http:// or https://: $url"))
      .when(!url.startsWith("http://") && !url.startsWith("https://"))
      .zipRight {
        ZIO
          .fromPromiseJS {
            val factory = new JsClientFactory()
            factory.createFromUrl(url)
          }
          .map(jsClient => A2AClientV03Live(jsClient))
      }

  def fromCard(card: AgentCard, headers: Map[String, String] = Map.empty): Task[A2AClientV03] =
    ZIO
      .fromPromiseJS {
        val factory = new JsClientFactory()
        factory.createFromAgentCard(A2AConverters.toJs(card))
      }
      .map(jsClient => A2AClientV03Live(jsClient))

  def fromConfig(config: Config): Task[A2AClientV03] =
    discover(config.url, config.headers)

  def live(url: String): ZLayer[Any, Throwable, A2AClientV03] =
    ZLayer.fromZIO(discover(url))

  def live(config: Config): ZLayer[Any, Throwable, A2AClientV03] =
    ZLayer.fromZIO(fromConfig(config))
end A2AClientV03

private final class A2AClientV03Live(jsClient: JsA2AClient) extends A2AClientV03:
  private def toJsConfig(config: MessageSendConfiguration): JsMessageSendConfiguration =
    JsBuilders.messageSendConfiguration(
      acceptedOutputModes = Some(config.acceptedOutputModes),
      blocking = config.blocking,
      historyLength = config.historyLength,
      pushNotificationConfig = config.pushNotificationConfig.map(A2AConverters.toJs),
    )

  override def agentCard: Task[AgentCard] =
    getAgentCard

  override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
    ZIO
      .fromPromiseJS {
        val effectiveConfig = config match
          case Some(value) if value.blocking.isDefined => Some(value)
          case Some(value)                             => Some(value.copy(returnImmediately = false))
          case None                                    => Some(MessageSendConfiguration.default.copy(returnImmediately = false))
        val params = JsBuilders.sendMessageParams(A2AConverters.toJs(message), effectiveConfig.map(toJsConfig))
        jsClient.sendMessage(params)
      }
      .flatMap { result =>
        val dyn = result.asInstanceOf[js.Dynamic]
        dyn.kind.asInstanceOf[js.UndefOr[String]].toOption match
          case Some("task") =>
            ZIO.succeed(A2AConverters.toScala(result.asInstanceOf[JsTask]))
          case Some("message") =>
            val msg = A2AConverters.toScala(result.asInstanceOf[JsMessage])
            ZIO.succeed(A2AStreamEventParser.taskFromMessage(message, msg))
          case other =>
            ZIO.fail(new IllegalArgumentException(s"Unexpected A2A send result kind: ${other.getOrElse("<missing>")}"))
      }

  override def stream(
    message: A2AMessage,
    config: Option[MessageSendConfiguration],
  ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrap {
      ZIO.attempt {
        val params   = JsBuilders.sendMessageParams(A2AConverters.toJs(message), config.map(toJsConfig))
        val asyncGen = jsClient.sendMessageStream(params)
        AsyncIteratorOps
          .toZStream(asyncGen.asInstanceOf[AsyncIterator[js.Any]])
          .mapZIO(A2AStreamEventParser.parse)
      }
    }

  override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
    ZIO.fromPromiseJS(jsClient.getTask(JsBuilders.taskQueryParams(taskId.value, historyLength))).map(A2AConverters.toScala)

  override def cancelTask(taskId: TaskId): Task[A2ATask] =
    ZIO.fromPromiseJS(jsClient.cancelTask(JsBuilders.taskIdParams(taskId.value))).map(A2AConverters.toScala)

  override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    ZStream.unwrap {
      ZIO.attempt {
        AsyncIteratorOps
          .toZStream(jsClient.resubscribeTask(JsBuilders.taskIdParams(taskId.value)).asInstanceOf[AsyncIterator[js.Any]])
          .mapZIO(A2AStreamEventParser.parse)
      }
    }

  override def getAgentCard: Task[AgentCard] =
    ZIO.fromPromiseJS(jsClient.getAgentCard()).map(A2AConverters.toScala)

  override def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig] =
    ZIO
      .fromPromiseJS(jsClient.setTaskPushNotificationConfig(JsBuilders.taskPushNotificationConfigParams(taskId.value, A2AConverters.toJs(config))))
      .map(A2AConverters.toScalaPushNotificationConfigResult)

  override def getPushNotificationConfig(taskId: TaskId, configId: Option[String]): Task[PushNotificationConfig] =
    ZIO
      .fromPromiseJS(jsClient.getTaskPushNotificationConfig(JsBuilders.getPushNotificationConfigParams(taskId.value, configId)))
      .map(A2AConverters.toScalaPushNotificationConfigResult)

  override def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]] =
    ZIO
      .fromPromiseJS(jsClient.listTaskPushNotificationConfig(JsBuilders.taskIdParams(taskId.value)))
      .map(result => A2AConverters.toScalaPushNotificationConfigResults(result.asInstanceOf[js.Array[js.Any]]))

  override def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
    ZIO.fromPromiseJS(jsClient.deleteTaskPushNotificationConfig(JsBuilders.deletePushNotificationConfigParams(taskId.value, configId))).unit
end A2AClientV03Live

/** Legacy A2A 0.3 server backed by `@a2a-js/sdk`. Prefer [[A2AServer]] for v1. */
trait A2AServerV03:
  def start: Task[Unit]
  def stop: Task[Unit]
  def agentCard: AgentCard
  def url: String

object A2AServerV03:
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
    executorFactory: Option[(JsTaskStore, Runtime[Any]) => JsAgentExecutor] = None):
    def url: String = s"http://$host:$port"

    def toAgentCard: AgentCard =
      AgentCard(
        name = name,
        description = description,
        supportedInterfaces = List(AgentInterface(url = url, protocolVersion = "0.3.0")),
        capabilities = capabilities,
        skills = skills,
      )
  end Config

  val workspaceStaging: (A2AMessage, TaskId) => Task[InvocationContext] =
    A2AServerLive.workspaceStaging

  def create(config: Config): ZIO[Scope, Throwable, A2AServerV03] =
    for
      runtime <- ZIO.runtime[Any]
      server  <- ZIO.acquireRelease(start(config, runtime))(_.stop.ignore)
    yield server

  def start(config: Config, runtime: Runtime[Any]): Task[A2AServerV03] =
    ZIO.attempt(A2AServerV03Live(config, runtime)).tap(_.start)

  def live(config: Config): ZLayer[Scope, Throwable, A2AServerV03] =
    ZLayer.fromZIO(create(config))
end A2AServerV03

private final class A2AServerV03Live(config: A2AServerV03.Config, runtime: Runtime[Any]) extends A2AServerV03:
  private var bunServer: js.Dynamic = null
  private val activeRuns            = mutable.Map.empty[String, Fiber.Runtime[Throwable, Unit]]

  override def agentCard: AgentCard = config.toAgentCard
  override def url: String          = config.url

  override def start: Task[Unit] =
    ZIO.attempt {
      SessionLogger.configure(config.sessionLogDir)
      val card      = A2AConverters.toJs(config.toAgentCard)
      val taskStore = new JsInMemoryTaskStore()
      val executor  = config.executorFactory.fold(createExecutor(taskStore))(factory => factory(taskStore, runtime))
      val handler   = new JsDefaultRequestHandler(card, taskStore, executor)
      val transport = new JsJsonRpcTransportHandler(handler.asInstanceOf[js.Dynamic])
      bunServer = BunServerV03.serve(
        js.Dynamic.literal(
          hostname = config.host,
          port = config.port,
          fetch = createFetchHandler(transport, card),
        )
      )
      ()
    }

  override def stop: Task[Unit] =
    ZIO.foreachDiscard(activeRuns.values.toList)(_.interrupt).ignore *>
      ZIO.attempt {
        activeRuns.clear()
        if bunServer != null then bunServer.stop()
        ()
      }

  private def createExecutor(taskStore: JsTaskStore): JsAgentExecutor =
    JsExecutorBuilder.create(
      handler = (ctx, bus) =>
        val message             = A2AConverters.toScala(ctx.userMessage)
        val taskId              = TaskId(ctx.taskId)
        val contextId           = ContextId(ctx.contextId)
        val preparedInvocation =
          config.invocationPreparer match
            case Some(prep) => prep(message, taskId)
            case None       => ZIO.succeed(InvocationContext(prompt = message.text))

        publishTaskSnapshot(taskId, contextId, bus)

        Unsafe.unsafe { implicit unsafe =>
          val run =
            execute(message, taskId, contextId, preparedInvocation, bus)
              .catchAll(error => publishFailure(taskId, contextId, bus, error))
              .ensuring(ZIO.succeed(activeRuns.remove(taskId.value)).unit)
          activeRuns.update(taskId.value, runtime.unsafe.fork(run))
        }
        js.Promise.resolve(())
      ,
      cancelHandler = (taskId, bus) =>
        Unsafe.unsafe { implicit unsafe =>
          val cancel = activeRuns.remove(taskId) match
            case Some(fiber) => fiber.interrupt.unit
            case None        => ZIO.unit
          runtime.unsafe.runToFuture(cancel *> ZIO.succeed(bus.finished())).toJSPromise.`then`[Unit](_ => ())
        },
    )

  private def execute(
    requestMessage: A2AMessage,
    taskId: TaskId,
    contextId: ContextId,
    preparedInvocation: Task[InvocationContext],
    bus: JsExecutionEventBus,
  ): Task[Unit] =
    val effect =
      preparedInvocation.flatMap { invocation =>
        SessionLogger.logEvent(taskId.value, "prompt", invocation.prompt)
        ClaudeAgent
          .queryComplete(
            invocation.prompt,
            invocation.optionsModifier(config.agentOptions),
            collectionPolicy = CollectionPolicy.ResultOnly,
            sink = progressSink(taskId, contextId, bus),
          )
          .provideLayer(ClaudeAgent.live)
          .flatMap(result => invocation.artifactsAfter.map(artifacts => (result, artifacts)))
          .ensuring(invocation.cleanup.ignore)
      }

    withTaskTimeout(taskId, effect).flatMap { case (result, artifacts) =>
      val responseText =
        result.outcome.resultText
          .orElse(result.semanticText.toOption)
          .getOrElse("Error: " + result.outcome.toString)
      val responseMsg = A2AMessage.agentText(responseText, Some(contextId)).copy(taskId = Some(taskId))
      SessionLogger.logEvent(taskId.value, "completed", responseText.take(500))
      ZIO.succeed {
        artifacts.foreach(artifact => publishArtifactUpdate(taskId, contextId, bus, artifact))
        publishStatusUpdate(taskId, contextId, bus, TaskStatus.completed(responseMsg), finalUpdate = true)
        bus.publish(A2AConverters.toJs(responseMsg))
        bus.finished()
      }
    }

  private def progressSink(taskId: TaskId, contextId: ContextId, bus: JsExecutionEventBus): QueryCollector.MessageSink =
    var state = A2AProgress.State.empty
    message =>
      val (next, maybeStatus) = A2AProgress.statusMessage(message, contextId, taskId, state)
      state = next
      ZIO.succeed {
        maybeStatus.foreach(statusMessage =>
          publishStatusUpdate(taskId, contextId, bus, TaskStatus.working(Some(statusMessage)))
        )
      }

  private def withTaskTimeout[A](taskId: TaskId, effect: Task[A]): Task[A] =
    config.taskTimeout match
      case Some(timeout) =>
        effect.timeoutFail(new TimeoutException(s"A2A task ${taskId.value} timed out after $timeout"))(timeout)
      case None =>
        effect

  private def publishFailure(
    taskId: TaskId,
    contextId: ContextId,
    bus: JsExecutionEventBus,
    error: Throwable,
  ): UIO[Unit] =
    ZIO.succeed {
      val errorText = s"Error: ${error.getMessage}"
      val errorMsg  = A2AMessage.agentText(errorText, Some(contextId)).copy(taskId = Some(taskId))
      SessionLogger.logEvent(taskId.value, "failed", errorText)
      publishStatusUpdate(taskId, contextId, bus, TaskStatus.failed(errorMsg), finalUpdate = true)
      bus.publish(A2AConverters.toJs(errorMsg))
      bus.finished()
    }

  private def publishTaskSnapshot(taskId: TaskId, contextId: ContextId, bus: JsExecutionEventBus): Unit =
    bus.publish(
      A2AConverters.toJs(
        A2ATask(
          id = taskId,
          contextId = contextId,
          status = TaskStatus.working(),
        )
      )
    )

  private def publishStatusUpdate(
    taskId: TaskId,
    contextId: ContextId,
    bus: JsExecutionEventBus,
    status: TaskStatus,
    finalUpdate: Boolean = false,
  ): Unit =
    bus.publish(
      js.Dynamic.literal(
        kind = "status-update",
        taskId = taskId.value,
        contextId = contextId.value,
        status = A2AConverters.toJs(status),
        `final` = finalUpdate,
      )
    )

  private def publishArtifactUpdate(
    taskId: TaskId,
    contextId: ContextId,
    bus: JsExecutionEventBus,
    artifact: Artifact,
  ): Unit =
    bus.publish(
      js.Dynamic.literal(
        kind = "artifact-update",
        taskId = taskId.value,
        contextId = contextId.value,
        artifact = A2AConverters.toJs(artifact),
        append = false,
        lastChunk = true,
      )
    )

  private def createFetchHandler(
    transportHandler: JsJsonRpcTransportHandler,
    card: JsAgentCard,
  ): js.Function1[js.Dynamic, js.Promise[js.Dynamic]] =
    (req: js.Dynamic) =>
      val method   = req.method.asInstanceOf[String]
      val urlObj   = js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url.asInstanceOf[String])
      val pathname = urlObj.pathname.asInstanceOf[String]
      val Response = js.Dynamic.global.Response
      if pathname == A2APaths.AgentCard && method == "GET" then
        js.Promise.resolve(
          js.Dynamic.newInstance(Response)(
            JsJSON.stringify(card),
            js.Dynamic.literal(status = 200, headers = js.Dynamic.literal(`Content-Type` = A2AContentType.Json)),
          )
        )
      else if pathname == "/" && method == "POST" then
        req
          .text()
          .asInstanceOf[js.Promise[String]]
          .`then`[js.Dynamic](body => handleJsonRpc(body, transportHandler))
      else
        js.Promise.resolve(
          js.Dynamic.newInstance(Response)("Not Found", js.Dynamic.literal(status = 404))
        )

  private def handleJsonRpc(body: String, transportHandler: JsJsonRpcTransportHandler): js.Promise[js.Dynamic] =
    val normalizedBody =
      if config.executionMode == ExecutionMode.Asynchronous then
        A2AJsonRpcRequests.withDefaultMessageSendBlocking(body, blocking = false)
      else body
    val requestId = BunJsonRpcResponses.requestIdOf(normalizedBody)
    transportHandler
      .handle(normalizedBody)
      .`then`[js.Dynamic](result => BunJsonRpcResponses.fromResult(result, requestId))
      .`catch`[js.Dynamic] { error =>
        val errorMsg =
          if error == null || js.isUndefined(error) then "Internal error"
          else error.asInstanceOf[js.Dynamic].selectDynamic("message").asInstanceOf[js.UndefOr[String]].toOption.getOrElse(error.toString)
        BunJsonRpcResponses.jsonRpcError(A2AErrorCode.InternalError, errorMsg, requestId)
      }
end A2AServerV03Live

/** Legacy app entry point backed by [[A2AServerV03]]. Prefer [[A2AServerApp]] for v1. */
trait A2AServerAppV03[Self <: Singleton] extends ZIOAppDefault:
  def name: String = getClass.getSimpleName.stripSuffix("$")
  def description: String
  def host: String =
    sys.env.get("A2A_HOST").orElse(sys.env.get("SERVICE_HOST")).getOrElse("localhost")
  def port: Int =
    sys.env.get("A2A_PORT").orElse(sys.env.get("SERVICE_PORT")).flatMap(_.toIntOption).getOrElse(3000)
  def agentOptions: AgentOptions = AgentOptions.default
  def agentOptionsZIO: Task[AgentOptions] = ZIO.succeed(agentOptions)
  def skills: List[AgentSkill]                                                = Nil
  def executionMode: ExecutionMode                                            = ExecutionMode.Default
  def taskTimeout: Option[Duration]                                           = None
  def capabilities: AgentCapabilities                                         = AgentCapabilities.default
  def sessionLogDir: Option[String]                                           = None
  def invocationPreparer: Option[(A2AMessage, TaskId) => Task[InvocationContext]] = None
  def executorFactory: Option[(JsTaskStore, Runtime[Any]) => JsAgentExecutor] = None

  protected final def configWith(options: AgentOptions): A2AServerV03.Config =
    A2AServerV03.Config(
      name = name,
      description = description,
      host = host,
      port = port,
      agentOptions = options,
      executionMode = executionMode,
      taskTimeout = taskTimeout,
      capabilities = capabilities,
      skills = skills,
      sessionLogDir = sessionLogDir,
      invocationPreparer = invocationPreparer,
      executorFactory = executorFactory,
    )

  final def config: A2AServerV03.Config =
    configWith(agentOptions)

  final def configZIO: Task[A2AServerV03.Config] =
    agentOptionsZIO.map(configWith)

  def onStarted(server: A2AServerV03): UIO[Unit] = ZIO.unit

  override final def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      ZIO.runtime[Any].flatMap { runtime =>
        configZIO
          .flatMap(config => ZIO.acquireRelease(A2AServerV03.start(config, runtime))(_.stop.ignore))
          .flatMap(server => onStarted(server) *> ZIO.never)
      }
    }
end A2AServerAppV03

extension (client: A2AClientV03)
  def sendTextV03(text: String, contextId: Option[ContextId] = None): Task[A2ATask] =
    client.send(A2AMessage.userText(text, contextId))

  def submitTextV03(text: String, contextId: Option[ContextId] = None): Task[A2ATask] =
    client.submit(A2AMessage.userText(text, contextId))

  def sendAndPollTextV03(
    text: String,
    contextId: Option[ContextId] = None,
    pollEvery: Duration = 1.second,
    timeout: Option[Duration] = None,
  ): Task[A2ATask] =
    client.sendAndPoll(A2AMessage.userText(text, contextId), pollEvery = pollEvery, timeout = timeout)

  def streamTextV03(text: String, contextId: Option[ContextId] = None): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    client.stream(A2AMessage.userText(text, contextId))

  def askTextV03(text: String, contextId: Option[ContextId] = None): Task[String] =
    client.sendAndPollTextV03(text, contextId).map(task => task.status.message.map(_.text).getOrElse(""))
end extension

@js.native
@JSGlobal("Bun")
private object BunServerV03 extends js.Object:
  def serve(options: js.Dynamic): js.Dynamic = js.native
