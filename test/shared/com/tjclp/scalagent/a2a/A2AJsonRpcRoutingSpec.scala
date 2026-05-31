package com.tjclp.scalagent.a2a

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

class A2AJsonRpcRoutingSpec extends FunSuite:
  private def request(method: String, params: Option[Json]): JsonRpcRequest =
    JsonRpcRequest(method = method, params = params, id = Some(JsonRpcId.Num(1)))

  test("JSON-RPC contextFor extracts tenant from request params"):
    val params = A2ARequest.TasksList(tenant = Some("tenant-a")).toJsonAST.toOption

    assertEquals(
      A2AJsonRpcRouting.contextFor(request(A2AMethod.TasksList, params), ServerCallContext()),
      Right(ServerCallContext(tenant = Some("tenant-a"))),
    )

  test("JSON-RPC contextFor rejects conflicting tenant values"):
    val params = A2ARequest.TasksList(tenant = Some("tenant-a")).toJsonAST.toOption
    val result =
      A2AJsonRpcRouting.contextFor(request(A2AMethod.TasksList, params), ServerCallContext(tenant = Some("tenant-b")))

    assert(
      result.left.exists(error =>
        error.code == A2AErrorCode.InvalidParams && error.message.contains("Conflicting tenant values")
      )
    )

  test("JSON-RPC contextFor validates tenant type for extended card params"):
    val params = """{"tenant":1}""".fromJson[Json].toOption
    val result = A2AJsonRpcRouting.contextFor(request(A2AMethod.GetAuthenticatedExtendedCard, params), ServerCallContext())

    assert(
      result.left.exists(error => error.code == A2AErrorCode.InvalidParams && error.message.contains("tenant must be a string"))
    )
end A2AJsonRpcRoutingSpec
