package com.tjclp.scalagent.a2a

import munit.FunSuite

class A2AServiceParametersSpec extends FunSuite:
  private val requiredExt = AgentExtension("https://example.test/required", required = true)
  private val optionalExt = AgentExtension("https://example.test/optional")
  private val capabilities = AgentCapabilities(
    extensions = List(requiredExt, optionalExt)
  )
  private val agentCard = AgentCard(
    name = "ServiceParameters",
    description = "Service parameter test card",
    supportedInterfaces = List(
      AgentInterface.jsonRpc("https://agent.example.test/a2a").copy(protocolVersion = "1.0.0"),
      AgentInterface.rest("https://agent.example.test").copy(protocolVersion = "1.0"),
    ),
  )

  test("service parameter validation shares version and extension negotiation"):
    val accepted = ServerCallContext(
      requestedVersion = Some("1.0.2"),
      requestedExtensions = List(requiredExt.uri),
    )
    val missingExtension = accepted.copy(requestedExtensions = Nil)
    val unsupportedVersion = accepted.copy(requestedVersion = Some("2.0"))

    assertEquals(A2AServiceParameters.validate(agentCard, capabilities, accepted, A2ATransport.JSONRPC), Right(()))
    assert(
      A2AServiceParameters
        .validate(agentCard, capabilities, missingExtension, A2ATransport.JSONRPC)
        .left
        .exists(_.message.contains(requiredExt.uri))
    )
    assert(
      A2AServiceParameters
        .validate(agentCard, capabilities, unsupportedVersion, A2ATransport.JSONRPC)
        .left
        .exists(_.message.contains("2.0"))
    )

  test("activated extensions keeps only supported requested extensions once"):
    val context = ServerCallContext(
      requestedExtensions = List(optionalExt.uri, "https://example.test/unknown", optionalExt.uri, requiredExt.uri)
    )

    assertEquals(
      A2AServiceParameters.activatedExtensions(capabilities, context),
      List(optionalExt.uri, requiredExt.uri),
    )

  test("service parameter validation enforces advertised interface tenant"):
    val tenantCard = agentCard.copy(
      supportedInterfaces = List(
        AgentInterface.jsonRpc("https://tenant-a.example.test/a2a", Some("tenant-a")).copy(protocolVersion = "1.0.0"),
        AgentInterface.jsonRpc("https://tenant-b.example.test/a2a", Some("tenant-b")).copy(protocolVersion = "1.0"),
      )
    )
    val context = ServerCallContext(
      requestedVersion = Some("1.0"),
      requestedExtensions = List(requiredExt.uri),
    )

    assertEquals(
      A2AServiceParameters.validate(
        tenantCard,
        capabilities,
        context.copy(tenant = Some("tenant-a")),
        A2ATransport.JSONRPC,
      ),
      Right(()),
    )
    assertEquals(
      A2AServiceParameters.validate(
        tenantCard,
        capabilities,
        context.copy(tenant = Some("tenant-b")),
        A2ATransport.JSONRPC,
      ),
      Right(()),
    )
    assert(
      A2AServiceParameters
        .validate(tenantCard, capabilities, context, A2ATransport.JSONRPC)
        .left
        .exists(error =>
          error.code == A2AErrorCode.InvalidParams && error.message.contains("tenant is required")
        )
    )
    assert(
      A2AServiceParameters
        .validate(tenantCard, capabilities, context.copy(tenant = Some("tenant-c")), A2ATransport.JSONRPC)
        .left
        .exists(error =>
          error.code == A2AErrorCode.InvalidParams && error.message.contains("selected AgentInterface tenant")
        )
    )

  test("service parameter validation allows generic interface when advertised beside tenant interfaces"):
    val mixedCard = agentCard.copy(
      supportedInterfaces = List(
        AgentInterface.jsonRpc("https://generic.example.test/a2a").copy(protocolVersion = "1.0"),
        AgentInterface.jsonRpc("https://tenant.example.test/a2a", Some("tenant-a")).copy(protocolVersion = "1.0"),
      )
    )
    val context = ServerCallContext(
      requestedVersion = Some("1.0"),
      requestedExtensions = List(requiredExt.uri),
    )

    assertEquals(
      A2AServiceParameters.validate(mixedCard, capabilities, context, A2ATransport.JSONRPC),
      Right(()),
    )

  test("shared A2A error status mapping covers REST and gRPC response classes"):
    assertEquals(A2AError.httpStatus(A2AError.taskNotFound(TaskId("missing"))), 404)
    assertEquals(A2AError.grpcStatus(A2AError.taskNotFound(TaskId("missing"))), A2AGrpcStatus.NOT_FOUND)
    assertEquals(A2AError.httpStatus(A2AError.taskNotCancelable(TaskId("task-1"))), 400)
    assertEquals(A2AError.grpcStatus(A2AError.taskNotCancelable(TaskId("task-1"))), A2AGrpcStatus.FAILED_PRECONDITION)
    assertEquals(A2AError.httpStatus(A2AError.pushNotificationNotSupported), 400)
    assertEquals(A2AError.grpcStatus(A2AError.pushNotificationNotSupported), A2AGrpcStatus.FAILED_PRECONDITION)
    assertEquals(A2AError.httpStatus(A2AError.internalError("boom")), 500)
    assertEquals(A2AError.grpcStatus(A2AError.internalError("boom")), A2AGrpcStatus.INTERNAL)
    assertEquals(A2AError.httpStatus(A2AError.unauthenticated("missing credentials")), 401)
    assertEquals(A2AError.grpcStatus(A2AError.unauthenticated("missing credentials")), A2AGrpcStatus.UNAUTHENTICATED)
    assertEquals(A2AError.httpStatus(A2AError.invalidParams("bad")), 400)
    assertEquals(A2AError.grpcStatus(A2AError.invalidParams("bad")), A2AGrpcStatus.INVALID_ARGUMENT)
    assertEquals(A2AError.httpStatusName(404), "NOT_FOUND")
    assertEquals(A2AError.httpStatusName(500), "INTERNAL")
    assertEquals(A2AError.httpStatusName(400), "INVALID_ARGUMENT")

  test("shared operation catalog classifies JSON-RPC and gRPC service methods"):
    assertEquals(A2AOperation.methodNames, A2AOperation.grpcMethodNames)
    assertEquals(
      A2AOperation.streamingMethodNames,
      Set(A2AMethod.MessageStream, A2AMethod.TasksResubscribe),
    )
    assertEquals(
      A2AOperation.methodNames,
      Set(
        A2AMethod.MessageSend,
        A2AMethod.MessageStream,
        A2AMethod.TasksGet,
        A2AMethod.TasksList,
        A2AMethod.TasksCancel,
        A2AMethod.TasksResubscribe,
        A2AMethod.PushNotificationConfigSet,
        A2AMethod.PushNotificationConfigGet,
        A2AMethod.PushNotificationConfigList,
        A2AMethod.PushNotificationConfigDelete,
        A2AMethod.GetAuthenticatedExtendedCard,
      ),
    )
    assertEquals(A2AOperation.fromMethodName(A2AMethod.TasksGet), Some(A2AOperation.TasksGet))
    assertEquals(A2AOperation.fromGrpcMethodName(A2AMethod.TasksGet), Some(A2AOperation.TasksGet))
    assertEquals(A2AOperation.fromMethodName("unknown"), None)
