package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.JSON as JsJSON
import com.tjclp.scalagent.a2a.facade.*
import zio.*

private[a2a] object A2AEventIds:
  def taskIdFor(message: A2AMessage): TaskId =
    message.taskId.getOrElse(TaskId(message.messageId.value))

  def contextIdFor(message: A2AMessage): ContextId =
    message.contextId
      .orElse(message.taskId.map(taskId => ContextId(taskId.value)))
      .getOrElse(ContextId(message.messageId.value))

  def contextIdFor(taskId: TaskId, explicitContextId: Option[String]): ContextId =
    explicitContextId.map(ContextId(_)).getOrElse(ContextId(taskId.value))

  def artifactIdFor(taskId: TaskId, explicitArtifactId: Option[String]): String =
    explicitArtifactId.getOrElse(taskId.value)

private[a2a] object A2AStreamEventParser:
  def taskFromMessage(requestMessage: A2AMessage, responseMessage: A2AMessage): A2ATask =
    A2ATask(
      id = A2AEventIds.taskIdFor(responseMessage),
      contextId = A2AEventIds.contextIdFor(responseMessage),
      status = TaskStatus.completed(responseMessage),
      history = List(requestMessage, responseMessage),
    )

  def parse(jsEvent: js.Any): Task[A2AResponse.StreamEvent] =
    ZIO.attempt {
      val dyn = jsEvent.asInstanceOf[js.Dynamic]
      requiredString(dyn, "kind", jsEvent) match
        case "task" =>
          A2AResponse.StreamEvent.TaskSnapshot(A2AConverters.toScala(jsEvent.asInstanceOf[JsTask]))
        case "message" =>
          val message = A2AConverters.toScala(jsEvent.asInstanceOf[JsMessage])
          A2AResponse.StreamEvent.TaskMessage(
            A2AEventIds.taskIdFor(message),
            A2AEventIds.contextIdFor(message),
            message,
          )
        case "status-update" =>
          val taskId    = TaskId(requiredString(dyn, "taskId", jsEvent))
          val contextId = A2AEventIds.contextIdFor(
            taskId,
            optionalString(dyn, "contextId"),
          )
          val status  = A2AConverters.toScala(dyn.status.asInstanceOf[JsTaskStatus])
          val isFinal = dyn.selectDynamic("final").asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)
          A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, status, isFinal)
        case "artifact-update" | "artifact" =>
          val taskId    = TaskId(requiredString(dyn, "taskId", jsEvent))
          val contextId = A2AEventIds.contextIdFor(
            taskId,
            optionalString(dyn, "contextId"),
          )
          val artifactDyn = dyn.artifact.asInstanceOf[js.Dynamic]
          val artifactId  = optionalString(artifactDyn, "artifactId")
            .orElse(optionalString(dyn, "artifactId"))
          if artifactId.isEmpty then
            artifactDyn.updateDynamic("artifactId")(A2AEventIds.artifactIdFor(taskId, artifactId))
          val artifact = A2AConverters.toScala(artifactDyn.asInstanceOf[JsArtifact])
          val append   = dyn.append
            .asInstanceOf[js.UndefOr[Boolean]]
            .toOption
            .orElse(artifactDyn.append.asInstanceOf[js.UndefOr[Boolean]].toOption)
            .getOrElse(false)
          val lastChunk = dyn.lastChunk
            .asInstanceOf[js.UndefOr[Boolean]]
            .toOption
            .orElse(artifactDyn.lastChunk.asInstanceOf[js.UndefOr[Boolean]].toOption)
            .getOrElse(true)
          A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact, append, lastChunk)
        case other =>
          throw new IllegalArgumentException(
            s"Unknown A2A stream event kind: $other (${safeStringify(jsEvent)})"
          )
      end match
    }

  private def optionalString(dyn: js.Dynamic, field: String): Option[String] =
    dyn.selectDynamic(field).asInstanceOf[js.UndefOr[String]].toOption

  private def requiredString(
    dyn: js.Dynamic,
    field: String,
    raw: js.Any,
  ): String =
    optionalString(dyn, field).getOrElse {
      throw new IllegalArgumentException(s"Missing '$field' in A2A stream event: ${safeStringify(raw)}")
    }

  private def safeStringify(value: js.Any): String =
    try
      if value == null || js.isUndefined(value) then "null"
      else
        val json = JsJSON.stringify(value)
        if json == null || js.isUndefined(json) then value.toString
        else json
    catch case _: Throwable => value.toString
end A2AStreamEventParser

private[scalagent] object A2AJsonRpcRequests:
  def withDefaultMessageSendBlocking(body: String, blocking: Boolean): String =
    try
      val request = JsJSON.parse(body)
      if normalizeValue(request, blocking) then JsJSON.stringify(request)
      else body
    catch case _: Throwable => body

  private def normalizeValue(value: js.Any, blocking: Boolean): Boolean =
    if value == null || js.isUndefined(value) then false
    else if js.Array.isArray(value) then
      value
        .asInstanceOf[js.Array[js.Any]]
        .foldLeft(false)((changed, item) => normalizeRequest(item, blocking) || changed)
    else normalizeRequest(value, blocking)

  private def normalizeRequest(value: js.Any, blocking: Boolean): Boolean =
    if value == null || js.isUndefined(value) then false
    else
      val request = value.asInstanceOf[js.Dynamic]
      request.selectDynamic("method").asInstanceOf[js.UndefOr[String]].toOption match
        case Some(method) if method == A2AMethod.MessageSend || method == "message/send" =>
          val paramsValue = request.selectDynamic("params").asInstanceOf[js.Any]
          if paramsValue == null || js.isUndefined(paramsValue) then false
          else normalizeConfiguration(paramsValue.asInstanceOf[js.Dynamic], blocking)
        case _ =>
          false

  private def normalizeConfiguration(params: js.Dynamic, blocking: Boolean): Boolean =
    val configValue = params.selectDynamic("configuration").asInstanceOf[js.Any]
    if configValue == null || js.isUndefined(configValue) then
      params.updateDynamic("configuration")(js.Dynamic.literal(blocking = blocking))
      true
    else
      val config        = configValue.asInstanceOf[js.Dynamic]
      val blockingValue = config.selectDynamic("blocking").asInstanceOf[js.Any]
      if blockingValue == null || js.isUndefined(blockingValue) then
        config.updateDynamic("blocking")(blocking)
        true
      else false
end A2AJsonRpcRequests

private[scalagent] object BunJsonRpcResponses:
  def fromResult(result: js.Any, requestId: js.Any = null): js.Dynamic =
    if isAsyncIterable(result) then
      response(
        body = sseStream(result.asInstanceOf[js.Dynamic], requestId),
        status = 200,
        headers = sseHeaders,
      )
    else
      response(
        body = JsJSON.stringify(result),
        status = 200,
        headers = jsonHeaders,
      )

  def jsonRpcError(
    code: Int,
    message: String,
    requestId: js.Any = null,
  ): js.Dynamic =
    response(
      body = JsJSON.stringify(
        js.Dynamic.literal(
          jsonrpc = A2AProtocol.JsonRpcVersion,
          error = js.Dynamic.literal(code = code, message = message),
          id = requestId,
        )
      ),
      status = 200,
      headers = jsonHeaders,
    )

  def requestIdOf(body: String): js.Any =
    try
      val request = JsJSON.parse(body).asInstanceOf[js.Dynamic]
      request.selectDynamic("id").asInstanceOf[js.UndefOr[js.Any]].getOrElse(null)
    catch case _: Throwable => null

  private val jsonHeaders =
    js.Dynamic.literal(
      `Content-Type` = "application/json"
    )

  private val sseHeaders =
    js.Dynamic.literal(
      `Content-Type` = "text/event-stream",
      `Cache-Control` = "no-cache",
      Connection = "keep-alive",
      `X-Accel-Buffering` = "no",
    )

  private def response(
    body: js.Any,
    status: Int,
    headers: js.Dynamic,
  ): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(
      body,
      js.Dynamic.literal(status = status, headers = headers),
    )

  private def isAsyncIterable(value: js.Any): Boolean =
    if value == null || js.isUndefined(value) then false
    else js.typeOf(js.Dynamic.global.Reflect.get(value.asInstanceOf[js.Any], js.Symbol.asyncIterator)) == "function"

  private def sseStream(asyncIterable: js.Dynamic, requestId: js.Any): js.Dynamic =
    // The `[Symbol.asyncIterator]` method on an async-iterable expects `this`
    // to be the iterable itself (it accesses the iterable's captured state).
    // `Reflect.get` returns the method detached, so we must rebind `this` via
    // `Function.prototype.call(asyncIterable)`. Calling it bare gives
    // `this = undefined`, the factory returns broken state, and `iterator`
    // ends up undefined — causing `iterator.next()` to throw a TypeError.
    val iteratorFactory =
      js.Dynamic.global.Reflect.get(asyncIterable, js.Symbol.asyncIterator).asInstanceOf[js.Dynamic]
    val iterator = iteratorFactory.call(asyncIterable).asInstanceOf[js.Dynamic]
    val encoder  = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    var canceled  = false

    def pump(controller: js.Dynamic): Unit =
      iterator
        .next()
        .asInstanceOf[js.Promise[js.Dynamic]]
        .`then`[Unit] { step =>
          if canceled then ()
          else if step.done.asInstanceOf[Boolean] then
            controller.close()
            ()
          else
            controller.enqueue(encoder.encode(formatSseEvent(step.value)))
            pump(controller)
        }
        .`catch`[Unit] { error =>
          if !canceled then
            val jsError = error.asInstanceOf[js.Any]
            controller.enqueue(encoder.encode(formatSseErrorEvent(jsonRpcErrorBody(requestId, jsError))))
            controller.close()
          ()
        }

    js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(
      js.Dynamic.literal(
        start = (controller: js.Dynamic) => pump(controller),
        cancel = (_: js.Any) =>
          canceled = true
          iteratorReturn(iterator),
      )
    )
  end sseStream

  private def iteratorReturn(iterator: js.Dynamic): js.Promise[Unit] =
    val returnFn = iterator.selectDynamic("return")
    if js.typeOf(returnFn) == "function" then
      returnFn
        .asInstanceOf[js.Function1[js.Any, js.Promise[js.Any]]](js.undefined)
        .`then`[Unit](_ => ())
    else js.Promise.resolve(())

  private def formatSseEvent(event: js.Any): String =
    s"data: ${JsJSON.stringify(event)}\n\n"

  private def formatSseErrorEvent(error: js.Any): String =
    s"event: error\ndata: ${JsJSON.stringify(error)}\n\n"

  private def jsonRpcErrorBody(requestId: js.Any, error: js.Any): js.Any =
    js.Dynamic.literal(
      jsonrpc = A2AProtocol.JsonRpcVersion,
      error = js.Dynamic.literal(
        code = A2AErrorCode.InternalError,
        message = streamErrorMessage(error),
      ),
      id = requestId,
    )

  private def streamErrorMessage(error: js.Any): String =
    if error == null || js.isUndefined(error) then "Streaming error."
    else
      val dyn = error.asInstanceOf[js.Dynamic]
      dyn.selectDynamic("message").asInstanceOf[js.UndefOr[String]].toOption.getOrElse(error.toString)
end BunJsonRpcResponses
