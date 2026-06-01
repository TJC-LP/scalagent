package com.tjclp.scalagent.a2a

import scala.scalajs.js
import scala.scalajs.js.JSON as JsJSON
import scala.scalajs.js.timers.{SetIntervalHandle, clearInterval, setInterval}
import zio.*
import zio.json.*

// `A2AEventIds` was extracted to `src/shared/a2a/A2AEventIds.scala` (cross-built;
// pure Scala). The remaining objects in this file are JS-only.

private[a2a] object A2AStreamEventParser:
  def taskFromMessage(requestMessage: A2AMessage, responseMessage: A2AMessage): A2ATask =
    A2ATask(
      id = A2AEventIds.taskIdFor(responseMessage),
      contextId = A2AEventIds.contextIdFor(responseMessage),
      status = TaskStatus.completed(responseMessage),
      history = List(requestMessage, responseMessage),
    )

  def parse(jsEvent: js.Any): Task[A2AResponse.StreamEvent] =
    val raw = safeStringify(jsEvent)
    ZIO.fromEither(raw.fromJson[A2AResponse.StreamEvent].left.map(parseError(_, raw)))

  private def parseError(error: String, raw: String): IllegalArgumentException =
    val unknownPrefix = "Unknown stream event kind: "
    if error.startsWith(unknownPrefix) then
      IllegalArgumentException(s"Unknown A2A stream event kind: ${error.stripPrefix(unknownPrefix)} ($raw)")
    else IllegalArgumentException(s"Invalid A2A stream event: $error ($raw)")

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
  def withDefaultMessageSendExecutionMode(body: String, executionMode: ExecutionMode): String =
    A2AMessageSendDefaults.normalizeJsonRpcBodyForMode(body, executionMode)

  def withDefaultMessageSendBlocking(body: String, blocking: Boolean): String =
    A2AMessageSendDefaults.normalizeJsonRpcBody(body, returnImmediately = !blocking)
end A2AJsonRpcRequests

private[scalagent] object BunJsonRpcResponses:
  def fromResult(result: js.Any, requestId: js.Any = null): js.Dynamic =
    fromResult(result, requestId, A2AHttpBinding.SseKeepAliveInterval.toMillis.toInt)

  def fromResult(
    result: js.Any,
    requestId: js.Any,
    keepAliveMillis: Int,
  ): js.Dynamic =
    if isAsyncIterable(result) then
      response(
        body = sseStream(result.asInstanceOf[js.Dynamic], requestId, keepAliveMillis),
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

  private def sseStream(
    asyncIterable: js.Dynamic,
    requestId: js.Any,
    keepAliveMillis: Int,
  ): js.Dynamic =
    // The `[Symbol.asyncIterator]` method on an async-iterable expects `this`
    // to be the iterable itself (it accesses the iterable's captured state).
    // `Reflect.get` returns the method detached, so we must rebind `this` via
    // `Function.prototype.call(asyncIterable)`. Calling it bare gives
    // `this = undefined`, the factory returns broken state, and `iterator`
    // ends up undefined — causing `iterator.next()` to throw a TypeError.
    val iteratorFactory =
      js.Dynamic.global.Reflect.get(asyncIterable, js.Symbol.asyncIterator).asInstanceOf[js.Dynamic]
    val iterator                             = iteratorFactory.call(asyncIterable).asInstanceOf[js.Dynamic]
    val encoder                              = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
    var canceled                             = false
    var keepAlive: Option[SetIntervalHandle] = None

    def stopKeepAlive(): Unit =
      keepAlive.foreach(clearInterval)
      keepAlive = None

    def startKeepAlive(controller: js.Dynamic): Unit =
      if keepAliveMillis > 0 then
        keepAlive = Some(
          setInterval(keepAliveMillis.toDouble) {
            if !canceled then controller.enqueue(encoder.encode(A2AHttpBinding.sseKeepAliveFrame))
          }
        )

    def pump(controller: js.Dynamic): Unit =
      iterator
        .next()
        .asInstanceOf[js.Promise[js.Dynamic]]
        .`then`[Unit] { step =>
          if canceled then ()
          else if step.done.asInstanceOf[Boolean] then
            stopKeepAlive()
            controller.close()
            ()
          else
            controller.enqueue(encoder.encode(formatSseEvent(step.value)))
            pump(controller)
        }
        .`catch`[Unit] { error =>
          if !canceled then
            stopKeepAlive()
            val jsError = error.asInstanceOf[js.Any]
            controller.enqueue(encoder.encode(formatSseErrorEvent(jsonRpcErrorBody(requestId, jsError))))
            controller.close()
          ()
        }

    js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(
      js.Dynamic.literal(
        start = (controller: js.Dynamic) =>
          startKeepAlive(controller)
          pump(controller)
        ,
        cancel = (_: js.Any) =>
          canceled = true
          stopKeepAlive()
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
    A2AHttpBinding.sseDataFrame(JsJSON.stringify(event))

  private def formatSseErrorEvent(error: js.Any): String =
    A2AHttpBinding.sseErrorFrame(JsJSON.stringify(error))

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
