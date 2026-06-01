package com.tjclp.scalagent.a2a

import zio.UIO
import zio.stream.ZStream

private[a2a] trait A2AHttpResponseRenderer[Response]:
  protected final def dispatchHttpResponse(
    request: A2AHttpRequestView,
    agentCard: AgentCard,
    capabilities: AgentCapabilities,
    requestHandler: A2ARequestHandler,
    executionMode: ExecutionMode = ExecutionMode.Default,
  ): UIO[Response] =
    A2AHttpBinding
      .dispatchHttp(request, agentCard, capabilities, requestHandler, executionMode)
      .map(renderHttpResponse)

  protected final def renderHttpResponse(plan: A2AHttpResponsePlan): Response =
    plan match
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        textResponse(body, status, headers)
      case A2AHttpResponsePlan.Empty(status, headers) =>
        emptyResponse(status, headers)
      case A2AHttpResponsePlan.Sse(stream, isJsonRpc, headers, errorId) =>
        sseResponse(A2AHttpBinding.sseWireStream(stream, isJsonRpc, errorId), headers)

  protected def emptyResponse(status: Int, headers: List[(String, String)]): Response

  protected def textResponse(
    body: String,
    status: Int,
    headers: List[(String, String)],
  ): Response

  protected def sseResponse(
    wireStream: ZStream[Any, Nothing, String],
    headers: List[(String, String)],
  ): Response
end A2AHttpResponseRenderer
