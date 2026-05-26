package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.JSON as JsJSON
import scala.scalajs.js.JSConverters.*
import java.util.concurrent.TimeoutException
import zio.*
import zio.stream.*
import zio.json.*
import zio.json.ast.Json

/** Type-safe native Scala client for A2A v1 JSON-RPC/SSE. */
trait A2AClient:
  /** Get the agent card for this client's target agent. */
  def agentCard: Task[AgentCard]

  /** Send a message and wait for complete response. */
  def send(message: A2AMessage, config: Option[MessageSendConfiguration] = None): Task[A2ATask]

  /** Submit a message without holding the HTTP request until terminal state. */
  def submit(message: A2AMessage, config: Option[MessageSendConfiguration] = None): Task[A2ATask] =
    val asyncConfig = config
      .getOrElse(MessageSendConfiguration.default)
      .copy(returnImmediately = true)
    send(message, Some(asyncConfig))

  /** Poll a task until it reaches a terminal state. */
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

  /** Submit a message and poll `GetTask` until the task reaches a terminal state. */
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

  /** Send a message and stream responses. */
  def stream(message: A2AMessage, config: Option[MessageSendConfiguration] = None)
    : ZStream[Any, Throwable, A2AResponse.StreamEvent]

  /** Get task by ID. */
  def getTask(taskId: TaskId, historyLength: Option[Int] = None): Task[A2ATask]

  /** List tasks. */
  def listTasks(params: A2ARequest.TasksList = A2ARequest.TasksList()): Task[A2AResponse.ListTasksResult]

  /** Cancel a running task. */
  def cancelTask(taskId: TaskId): Task[A2ATask]

  /** Re-subscribe to task updates. */
  def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent]

  /** Get agent card. */
  def getAgentCard: Task[AgentCard]

  /** Create push notification config for a task. */
  def createTaskPushNotificationConfig(taskId: TaskId, config: TaskPushNotificationConfig)
    : Task[TaskPushNotificationConfig]

  /** Backwards-compatible method name for createTaskPushNotificationConfig. */
  def setPushNotificationConfig(taskId: TaskId, config: PushNotificationConfig): Task[PushNotificationConfig] =
    createTaskPushNotificationConfig(taskId, config)

  /** Get push notification config for a task. */
  def getTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[TaskPushNotificationConfig]

  /** Backwards-compatible method name for getTaskPushNotificationConfig. */
  def getPushNotificationConfig(taskId: TaskId, configId: Option[String] = None): Task[PushNotificationConfig] =
    configId match
      case Some(id) => getTaskPushNotificationConfig(taskId, id)
      case None     =>
        listTaskPushNotificationConfigs(taskId).flatMap {
          case head :: _ => ZIO.succeed(head)
          case Nil       => ZIO.fail(A2AError.invalidParams(s"No push notification config for task ${taskId.value}"))
        }

  /** List push notification configs for a task. */
  def listTaskPushNotificationConfigs(taskId: TaskId): Task[List[TaskPushNotificationConfig]]

  /** Backwards-compatible method name for listTaskPushNotificationConfigs. */
  def listPushNotificationConfigs(taskId: TaskId): Task[List[PushNotificationConfig]] =
    listTaskPushNotificationConfigs(taskId)

  /** Delete push notification config for a task. */
  def deleteTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[Unit]

  /** Backwards-compatible method name for deleteTaskPushNotificationConfig. */
  def deletePushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
    deleteTaskPushNotificationConfig(taskId, configId)
end A2AClient

object A2AClient:

  /** Configuration for A2A client. */
  final case class Config(
    url: String,
    headers: Map[String, String] = Map.empty)

  /** Create a client by discovering an agent at the given URL. */
  def discover(url: String, headers: Map[String, String] = Map.empty): Task[A2AClient] =
    ZIO
      .fail(new IllegalArgumentException(s"Invalid URL scheme. Must be http:// or https://: $url"))
      .when(!url.startsWith("http://") && !url.startsWith("https://"))
      .zipRight {
        val cardUrl = url.stripSuffix("/") + A2APaths.AgentCard
        Http.fetchJson[AgentCard](cardUrl, headers).flatMap(fromCard(_, headers))
      }

  /** Create a client from an existing agent card. */
  def fromCard(card: AgentCard, headers: Map[String, String] = Map.empty): Task[A2AClient] =
    card.supportedInterfaces.find(_.protocolBinding == A2ATransport.JSONRPC) match
      case Some(iface) =>
        ZIO.succeed(A2AClientLive(card, iface, headers))
      case None =>
        ZIO.fail(new IllegalArgumentException("Agent card does not advertise a JSONRPC interface"))

  /** Create a client from config. */
  def fromConfig(config: Config): Task[A2AClient] =
    discover(config.url, config.headers)

  /** ZLayer for A2AClient. */
  def live(url: String): ZLayer[Any, Throwable, A2AClient] =
    ZLayer.fromZIO(discover(url))

  /** ZLayer for A2AClient with config. */
  def live(config: Config): ZLayer[Any, Throwable, A2AClient] =
    ZLayer.fromZIO(fromConfig(config))
end A2AClient

private final class A2AClientLive(
  initialCard: AgentCard,
  iface: AgentInterface,
  headers: Map[String, String])
    extends A2AClient:

  private var currentCard = initialCard
  private var requestId   = 0L

  override def agentCard: Task[AgentCard] =
    getAgentCard

  override def getAgentCard: Task[AgentCard] =
    ZIO.succeed(currentCard)

  override def send(message: A2AMessage, config: Option[MessageSendConfiguration]): Task[A2ATask] =
    val effective = config.getOrElse(MessageSendConfiguration.default.copy(returnImmediately = false))
    rpc[A2ARequest.MessageSend, A2AResponse.SendMessageResult](
      A2AMethod.MessageSend,
      A2ARequest.MessageSend(message = message, configuration = Some(effective), tenant = iface.tenant),
    ).map {
      case A2AResponse.SendMessageResult.TaskResult(task)               => task
      case A2AResponse.SendMessageResult.MessageResult(responseMessage) =>
        A2AStreamEventParser.taskFromMessage(message, responseMessage)
    }

  override def stream(
    message: A2AMessage,
    config: Option[MessageSendConfiguration],
  ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    val effective = config.getOrElse(MessageSendConfiguration.default.copy(returnImmediately = false))
    rpcStream[A2ARequest.MessageSend](
      A2AMethod.MessageStream,
      A2ARequest.MessageSend(message = message, configuration = Some(effective), tenant = iface.tenant),
    )

  override def getTask(taskId: TaskId, historyLength: Option[Int]): Task[A2ATask] =
    rpc[A2ARequest.TasksGet, A2ATask](
      A2AMethod.TasksGet,
      A2ARequest.TasksGet(id = taskId, historyLength = historyLength, tenant = iface.tenant),
    )

  override def listTasks(params: A2ARequest.TasksList): Task[A2AResponse.ListTasksResult] =
    rpc[A2ARequest.TasksList, A2AResponse.ListTasksResult](
      A2AMethod.TasksList,
      params.copy(tenant = params.tenant.orElse(iface.tenant)),
    )

  override def cancelTask(taskId: TaskId): Task[A2ATask] =
    rpc[A2ARequest.TasksCancel, A2ATask](
      A2AMethod.TasksCancel,
      A2ARequest.TasksCancel(id = taskId, tenant = iface.tenant),
    )

  override def resubscribe(taskId: TaskId): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    rpcStream[A2ARequest.TasksResubscribe](
      A2AMethod.TasksResubscribe,
      A2ARequest.TasksResubscribe(id = taskId, tenant = iface.tenant),
    )

  override def createTaskPushNotificationConfig(
    taskId: TaskId,
    config: TaskPushNotificationConfig,
  ): Task[TaskPushNotificationConfig] =
    if !currentCard.capabilities.pushNotifications then ZIO.fail(A2AError.pushNotificationNotSupported)
    else
      rpc[TaskPushNotificationConfig, TaskPushNotificationConfig](
        A2AMethod.PushNotificationConfigSet,
        config.copy(taskId = Some(taskId), tenant = iface.tenant.orElse(config.tenant)),
      )

  override def getTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[TaskPushNotificationConfig] =
    if !currentCard.capabilities.pushNotifications then ZIO.fail(A2AError.pushNotificationNotSupported)
    else
      rpc[A2ARequest.PushNotificationConfigGet, TaskPushNotificationConfig](
        A2AMethod.PushNotificationConfigGet,
        A2ARequest.PushNotificationConfigGet(taskId = taskId, id = configId, tenant = iface.tenant),
      )

  override def listTaskPushNotificationConfigs(taskId: TaskId): Task[List[TaskPushNotificationConfig]] =
    if !currentCard.capabilities.pushNotifications then ZIO.fail(A2AError.pushNotificationNotSupported)
    else
      rpc[A2ARequest.PushNotificationConfigList, A2AResponse.PushNotificationConfigListResult](
        A2AMethod.PushNotificationConfigList,
        A2ARequest.PushNotificationConfigList(taskId = taskId, tenant = iface.tenant),
      ).map(_.configs)

  override def deleteTaskPushNotificationConfig(taskId: TaskId, configId: String): Task[Unit] =
    if !currentCard.capabilities.pushNotifications then ZIO.fail(A2AError.pushNotificationNotSupported)
    else
      rpcUnit[A2ARequest.PushNotificationConfigDelete](
        A2AMethod.PushNotificationConfigDelete,
        A2ARequest.PushNotificationConfigDelete(taskId = taskId, id = configId, tenant = iface.tenant),
      )

  private def nextRequestId(): JsonRpcId =
    requestId += 1
    JsonRpcId.Num(requestId)

  private def rpc[A: JsonEncoder, B: JsonDecoder](method: String, params: A): Task[B] =
    val id      = nextRequestId()
    val request = JsonRpcRequest(method = method, params = params.toJsonAST.toOption, id = Some(id))
    Http
      .postJson(iface.url, request.toJson, requestHeaders(A2AContentType.Json))
      .flatMap { body =>
        ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(A2AError.invalidRequest)).flatMap { response =>
          ensureResponseId(response, id, method) *> (response.error match
            case Some(error) => ZIO.fail(error.toA2AError)
            case None        =>
              response.result match
                case Some(result) => ZIO.fromEither(result.as[B].left.map(A2AError.invalidAgentResponse))
                case None         => ZIO.fail(A2AError.invalidAgentResponse(s"Missing result for $method")))
        }
      }

  private def rpcUnit[A: JsonEncoder](method: String, params: A): Task[Unit] =
    val id      = nextRequestId()
    val request = JsonRpcRequest(method = method, params = params.toJsonAST.toOption, id = Some(id))
    Http
      .postJson(iface.url, request.toJson, requestHeaders(A2AContentType.Json))
      .flatMap { body =>
        ZIO.fromEither(body.fromJson[JsonRpcResponse].left.map(A2AError.invalidRequest)).flatMap { response =>
          ensureResponseId(response, id, method) *> (response.error match
            case Some(error) => ZIO.fail(error.toA2AError)
            case None        => ZIO.unit)
        }
      }

  private def rpcStream[A: JsonEncoder](
    method: String,
    params: A,
  ): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    val id      = nextRequestId()
    val request = JsonRpcRequest(method = method, params = params.toJsonAST.toOption, id = Some(id))
    Http
      .sse(iface.url, Some(request.toJson), requestHeaders(A2AContentType.Json) + ("Accept" -> A2AContentType.Sse))
      .mapZIO { data =>
        ZIO.fromEither(data.fromJson[JsonRpcResponse].left.map(A2AError.invalidRequest)).flatMap { response =>
          ensureResponseId(response, id, method) *> (response.error match
            case Some(error) => ZIO.fail(error.toA2AError)
            case None        =>
              response.result match
                case Some(result) =>
                  ZIO.fromEither(result.as[A2AResponse.StreamEvent].left.map(A2AError.invalidAgentResponse))
                case None => ZIO.fail(A2AError.invalidAgentResponse(s"Missing stream result for $method")))
        }
      }

  private def ensureResponseId(
    response: JsonRpcResponse,
    expected: JsonRpcId,
    method: String,
  ): Task[Unit] =
    response.id match
      case Some(actual) if actual == expected =>
        ZIO.unit
      case Some(actual) =>
        ZIO.fail(
          A2AError.invalidAgentResponse(s"JSON-RPC response id mismatch for $method: expected $expected, got $actual")
        )
      case None =>
        ZIO.fail(A2AError.invalidAgentResponse(s"Missing JSON-RPC response id for $method"))

  private def requestHeaders(contentType: String): Map[String, String] =
    headers ++ Map(
      "Content-Type"    -> contentType,
      A2AHeader.Version -> iface.protocolVersion,
    )
end A2AClientLive

private object Http:
  def fetchJson[A: JsonDecoder](url: String, headers: Map[String, String]): Task[A] =
    get(url, headers).flatMap(body => ZIO.fromEither(body.fromJson[A].left.map(A2AError.invalidAgentResponse)))

  def get(url: String, headers: Map[String, String]): Task[String] =
    fetchText(url, js.Dynamic.literal(method = "GET", headers = toJsHeaders(headers)))

  def postJson(
    url: String,
    body: String,
    headers: Map[String, String],
  ): Task[String] =
    fetchText(url, js.Dynamic.literal(method = "POST", headers = toJsHeaders(headers), body = body))

  def sse(
    url: String,
    body: Option[String],
    headers: Map[String, String],
  ): ZStream[Any, Throwable, String] =
    ZStream.unwrapScoped {
      for
        queue <- Queue.unbounded[Take[Throwable, String]]
        init = js.Dynamic.literal(
          method = body.fold("GET")(_ => "POST"),
          headers = toJsHeaders(headers),
        )
        _ = body.foreach(value => init.body = value)
        response <- ZIO.fromPromiseJS(js.Dynamic.global.fetch(url, init).asInstanceOf[js.Promise[js.Dynamic]])
        _        <- failIfHttpError(response)
        contentType = Option(response.headers.get("content-type"))
          .filter(value => !js.isUndefined(value) && value != null)
          .map(_.asInstanceOf[String])
          .getOrElse("")
        stream <-
          if contentType.startsWith(A2AContentType.Sse) then
            val reader  = response.body.getReader().asInstanceOf[js.Dynamic]
            val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
            pumpSse(reader, decoder, "", queue).forkScoped.as(ZStream.fromQueue(queue).flattenTake)
          else
            ZIO
              .fromPromiseJS(response.text().asInstanceOf[js.Promise[String]])
              .map(body => ZStream.succeed(body))
      yield stream
    }

  private def fetchText(url: String, init: js.Dynamic): Task[String] =
    ZIO
      .fromPromiseJS(js.Dynamic.global.fetch(url, init).asInstanceOf[js.Promise[js.Dynamic]])
      .flatMap(response =>
        failIfHttpError(response) *> ZIO.fromPromiseJS(response.text().asInstanceOf[js.Promise[String]])
      )

  private def failIfHttpError(response: js.Dynamic): Task[Unit] =
    val ok = response.ok.asInstanceOf[Boolean]
    if ok then ZIO.unit
    else
      ZIO
        .fromPromiseJS(response.text().asInstanceOf[js.Promise[String]])
        .flatMap(body => ZIO.fail(A2AError.invalidAgentResponse(s"HTTP ${response.status}: $body")))

  private def pumpSse(
    reader: js.Dynamic,
    decoder: js.Dynamic,
    buffer: String,
    queue: Queue[Take[Throwable, String]],
  ): UIO[Unit] =
    ZIO
      .fromPromiseJS(reader.read().asInstanceOf[js.Promise[js.Dynamic]])
      .flatMap { step =>
        if step.done.asInstanceOf[Boolean] then emitEvents(buffer, queue).ignore *> queue.offer(Take.end).unit
        else
          val chunk          = decoder.decode(step.value).asInstanceOf[String]
          val (events, rest) = splitEvents(buffer + chunk)
          ZIO.foreachDiscard(events)(event => queue.offer(Take.single(event)).unit) *> pumpSse(
            reader,
            decoder,
            rest,
            queue,
          )
      }
      .catchAll(error => queue.offer(Take.fail(error)).unit)

  private def emitEvents(buffer: String, queue: Queue[Take[Throwable, String]]): UIO[Unit] =
    val trimmed = buffer.trim
    if trimmed.isEmpty then ZIO.unit
    else parseEvent(trimmed).fold(ZIO.unit)(data => queue.offer(Take.single(data)).unit)

  private def splitEvents(buffer: String): (List[String], String) =
    val normalized = buffer.replace("\r\n", "\n")
    val parts      = normalized.split("\n\n", -1).toList
    val complete   = parts.dropRight(1).flatMap(parseEvent)
    val rest       = parts.lastOption.getOrElse("")
    (complete, rest)

  private def parseEvent(raw: String): Option[String] =
    val lines     = raw.linesIterator.toList
    val eventType = lines
      .find(_.startsWith("event:"))
      .map(_.drop("event:".length).trim)
      .getOrElse("message")
    val data = lines
      .filter(_.startsWith("data:"))
      .map(_.drop("data:".length).trim)
      .mkString("\n")
    if eventType == "error" then Some(data)
    else Option.when(data.nonEmpty)(data)

  private def toJsHeaders(headers: Map[String, String]): js.Dynamic =
    val obj = js.Dynamic.literal()
    headers.foreach { case (key, value) => obj.updateDynamic(key)(value) }
    obj
end Http

/** Extension methods for convenient message sending. */
extension (client: A2AClient)
  /** Send a text message. */
  def sendText(text: String, contextId: Option[ContextId] = None): Task[A2ATask] =
    client.send(A2AMessage.userText(text, contextId))

  /** Submit a text message without waiting for terminal state. */
  def submitText(text: String, contextId: Option[ContextId] = None): Task[A2ATask] =
    client.submit(A2AMessage.userText(text, contextId))

  /** Submit a text message and poll until terminal state. */
  def sendAndPollText(
    text: String,
    contextId: Option[ContextId] = None,
    pollEvery: Duration = 1.second,
    timeout: Option[Duration] = None,
  ): Task[A2ATask] =
    client.sendAndPoll(A2AMessage.userText(text, contextId), pollEvery = pollEvery, timeout = timeout)

  /** Stream a text message. */
  def streamText(text: String, contextId: Option[ContextId] = None): ZStream[Any, Throwable, A2AResponse.StreamEvent] =
    client.stream(A2AMessage.userText(text, contextId))

  /** Send and wait for text response. */
  def askText(text: String, contextId: Option[ContextId] = None): Task[String] =
    client.sendAndPollText(text, contextId).map { task => task.status.message.map(_.text).getOrElse("") }
end extension
