package com.tjclp.scalagent.a2a

import java.nio.charset.StandardCharsets
import java.util.Locale

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

class A2AProtoParitySpec extends FunSuite:
  import A2APathRouting.RestRoute

  private final case class ProtoBinding(rpc: String, verb: String, path: String, body: Option[String])
  private final case class ActsFile(name: String, text: String)
  private final case class SpecMethodMapping(
    functionality: String,
    jsonRpcMethod: String,
    grpcMethod: String,
    restVerb: String,
    restPath: String)
  private final case class RpcSignature(request: String, response: String, streaming: Boolean)
  private final case class SpecErrorInfo(typeUrl: String, domain: String)

  // Conformance inputs (proto, spec doc, ACTS fixtures) are vendored in-repo and
  // read from the test classpath — the single source of truth, identical locally
  // and in CI. To track a fresh upstream A2A revision, re-vendor the files under
  // proto/ and test/resources/ (provenance: test/resources/acts/SOURCE.txt).

  test("JSON-RPC method constants match A2AService RPC names from local a2a proto"):
    withProtoSpec { proto =>
      assertEquals(
        A2AOperation.methodNames,
        rpcNames(proto),
      )
    }

  test("REST router covers A2AService HTTP annotations from local a2a proto"):
    withProtoSpec { proto =>
      val bindings = protoBindings(proto)
      assertEquals(bindings.size, 22)

      bindings.foreach { binding =>
        val samplePath = sampleRoute(binding.path)
        val routed     = A2APathRouting.route(binding.verb.toUpperCase, samplePath)
        assertEquals(
          routed,
          A2APathRouting.RoutedRest(
            pathTenant = if binding.path.startsWith("/{tenant}/") then Some("tenant-a") else None,
            route = Some(expectedRoute(binding.rpc)),
          ),
          s"${binding.verb.toUpperCase} ${binding.path}",
        )
      }
    }

  test("REST body handling follows A2AService HTTP body annotations from local a2a proto"):
    withProtoSpec { proto =>
      protoBindings(proto).foreach { binding =>
        val route = expectedRoute(binding.rpc)
        assertEquals(
          binding.body,
          Option.when(routeConsumesRestBody(route))("*"),
          s"${binding.verb.toUpperCase} ${binding.path}",
        )
      }
    }

  test("REST streaming routes follow A2AService streaming return annotations from local a2a proto"):
    withProtoSpec { proto =>
      protoStreamingRpcs(proto).foreach { case (rpc, isStreaming) =>
        val route = expectedRoute(rpc)
        assertEquals(
          routeStreams(route),
          isStreaming,
          rpc,
        )
      }
    }

  test("A2AService RPC signatures match shared request and response model"):
    withProtoSpec { proto =>
      assertEquals(protoRpcSignatures(proto), expectedRpcSignatures)
    }

  test("A2AService method signatures match shared request parameter model"):
    withProtoSpec { proto =>
      assertEquals(protoMethodSignatures(proto), expectedMethodSignatures)
    }

  test("method mapping reference covers local JSON-RPC, gRPC, and REST routes"):
    withProtoAndDoc { (proto, doc) =>
      val mappings = specMethodMappings(doc)
      assertEquals(mappings.map(_.jsonRpcMethod).toSet, A2AOperation.methodNames)
      assertEquals(mappings.map(_.grpcMethod).toSet, A2AOperation.grpcMethodNames)
      assertEquals(A2AOperation.grpcMethodNames, rpcNames(proto))

      mappings.foreach { mapping =>
        val samplePath = sampleRoute(mapping.restPath)
        val routed     = A2APathRouting.route(mapping.restVerb, samplePath)
        assertEquals(
          routed,
          A2APathRouting.RoutedRest(
            pathTenant = None,
            route = Some(expectedRoute(mapping.grpcMethod)),
          ),
          s"${mapping.restVerb} ${mapping.restPath} (${mapping.functionality})",
        )
      }
    }

  test("ACTS conformance suite files stay in the audited surface"):
    withActsSuiteFiles { files =>
      assertEquals(files.map(_.name).toSet, expectedActsSuiteFiles)
    }

  test("ACTS conformance test IDs stay in the audited surface"):
    withActsSuiteFiles { files =>
      val testIds = actsTestIds(files)
      assertEquals(testIds, expectedActsTestIds)
      assert(
        expectedActsSpecDivergenceTestIds.subsetOf(testIds),
        s"Known ACTS/spec divergences are no longer ACTS cases: $expectedActsSpecDivergenceTestIds",
      )
    }

  test("ACTS conformance operation names stay mapped to shared A2A constants"):
    withActsSuiteFiles { files =>
      val operations = actsOperations(files)
      assertEquals(operations, expectedActsOperations)
      assertEquals(actsOperationMethodTargets.values.toSet, A2AOperation.methodNames)
    }

  test("ACTS raw transport requests stay covered by shared A2A routing"):
    withActsSuiteFiles { files =>
      val rawHttpRequests = actsRawHttpRequests(files)
      assertEquals(rawHttpRequests, expectedActsRawHttpRequests)
      rawHttpRequests.foreach {
        case ("POST", "/") =>
          ()
        case ("GET", A2APaths.AgentCard) =>
          ()
        case (method, path) =>
          assert(
            A2APathRouting.route(method, path).route.nonEmpty,
            s"ACTS raw HTTP request $method $path is not covered by shared REST routing",
          )
      }

      val rawJsonRpcMethods = actsRawJsonRpcMethods(files)
      assertEquals(rawJsonRpcMethods -- A2AOperation.methodNames, Set("DoSomethingUnsupported"))
      assertEquals(rawJsonRpcMethods.intersect(A2AOperation.methodNames), expectedActsRawJsonRpcMethods)
    }

  test("documentation REST method table and proto HTTP annotations only diverge for subscribe compatibility"):
    withProtoAndDoc { (proto, doc) =>
      val protoPrimary = protoBindings(proto)
        .filterNot(_.path.startsWith("/{tenant}/"))
        .map(binding => binding.rpc -> (binding.verb.toUpperCase -> canonicalRestPath(binding.path)))
        .toMap
      val documented = specMethodMappings(doc)
        .map(mapping => mapping.grpcMethod -> (mapping.restVerb -> canonicalRestPath(mapping.restPath)))
        .toMap

      val mismatches = documented.collect {
        case (rpc, documentedBinding) if protoPrimary.get(rpc).exists(_ != documentedBinding) =>
          rpc -> (protoPrimary(rpc), documentedBinding)
      }
      assertEquals(
        mismatches,
        Map("SubscribeToTask" -> (("GET", "/tasks/{id}:subscribe"), ("POST", "/tasks/{id}:subscribe"))),
      )

      mismatches.foreach { case (rpc, ((protoVerb, protoPath), (docVerb, docPath))) =>
        assertEquals(
          A2APathRouting.route(protoVerb, sampleRoute(protoPath)).route,
          Some(expectedRoute(rpc)),
          s"proto route for $rpc",
        )
        assertEquals(
          A2APathRouting.route(docVerb, sampleRoute(docPath)).route,
          Some(expectedRoute(rpc)),
          s"documented route for $rpc",
        )
      }
    }

  test("GET and DELETE REST query builders accept ProtoJSON request fields from local a2a proto"):
    withProtoSpec { proto =>
      val queryFieldsByRpc = protoRestQueryFields(proto)
      assertEquals(queryFieldsByRpc, expectedRestQueryFields)

      queryFieldsByRpc.foreach { case (rpc, fields) =>
        val query = A2APathRouting.query(fields.map(field => field -> sampleQueryValue(field)).toMap.get)
        routedQueryJsonFields(rpc, query) match
          case Right(actualFields) =>
            assert(
              fields.subsetOf(actualFields),
              s"$rpc query fields were not preserved: expected $fields in $actualFields",
            )
          case Left(error) =>
            fail(s"$rpc query builder failed: ${error.message}")
      }
    }

  test("representative shared codecs emit ProtoJSON field names from local a2a proto"):
    withProtoSpec { proto =>
      val expectedByMessage = protoJsonFieldSamples
      expectedByMessage.foreach { case (messageName, actualFields) =>
        assertEquals(
          actualFields,
          protoJsonFields(proto, messageName),
          messageName,
        )
      }
    }

  test("field-name parity samples cover every local a2a proto message"):
    withProtoSpec { proto =>
      assertEquals(
        protoJsonFieldSamples.map(_._1).toSet,
        protoMessageNames(proto),
      )
    }

  test("required-field parity samples track local a2a proto annotations"):
    withProtoSpec { proto =>
      val requiredByProto = protoMessageNames(proto).toList
        .map(name => name -> protoRequiredJsonFields(proto, name))
        .filter(_._2.nonEmpty)
        .toMap
      assertEquals(requiredJsonFieldsByMessage, requiredByProto)
    }

  test("shared enum codecs match local a2a proto enum values"):
    withProtoSpec { proto =>
      assertEquals(
        taskStateValues.view.mapValues(_._1).toMap,
        protoEnumValues(proto, "TaskState"),
      )
      taskStateValues.foreach { case (name, (_, state)) =>
        assertEquals(jsonString(state), name, s"TaskState encoder for $state")
        assertEquals(s""""$name"""".fromJson[TaskState], Right(state), s"TaskState decoder for $name")
      }

      assertEquals(
        roleValues.view.mapValues(_._1).toMap,
        protoEnumValues(proto, "Role"),
      )
      roleValues.foreach { case (name, (_, role)) =>
        assertEquals(jsonString(role), name, s"Role encoder for $role")
        assertEquals(s""""$name"""".fromJson[A2ARole], Right(role), s"Role decoder for $name")
      }
    }

  test("core protocol binding constants match local a2a proto AgentInterface values"):
    withProtoSpec { proto =>
      assertEquals(coreProtocolBindings(proto), transportValues)
      transportValues.foreach { raw =>
        assertEquals(A2ATransport.fromRaw(raw).map(_.toRaw), Right(raw), raw)
      }
    }

  test("task lifecycle helpers match local a2a proto state semantics"):
    withProtoSpec { proto =>
      val semantics = protoTaskStateSemantics(proto)
      val states    = taskStateValues.view.mapValues(_._2).toMap

      assertEquals(
        states.collect { case (name, state) if state.isTerminal => name }.toSet,
        semantics.terminal,
      )
      assertEquals(
        states.collect { case (name, state) if state.isInterrupted => name }.toSet,
        semantics.interrupted,
      )
      assertEquals(
        states.collect { case (name, state) if state.isStreamEnding => name }.toSet,
        semantics.terminal ++ semantics.interrupted,
      )
    }

  test("error code and status mappings match local a2a specification"):
    withSpecDoc { doc =>
      val a2aMappings = specA2AErrorMappings(doc)
      assertEquals(
        a2aSpecificErrorCodes,
        a2aMappings.view.mapValues(_.jsonRpcCode).toMap,
      )
      a2aMappings.foreach { case (errorType, mapping) =>
        val code = a2aSpecificErrorCodes(errorType)
        assertEquals(
          A2AError.httpStatus(A2AError(code, errorType)),
          mapping.httpStatus,
          errorType,
        )
        assertEquals(
          A2AError.grpcStatus(A2AError(code, errorType)).wireName,
          mapping.grpcStatus,
          errorType,
        )
        assertEquals(
          A2AError.errorInfoReason(code),
          Some(errorInfoReason(errorType)),
          errorType,
        )
      }

      assertEquals(
        standardJsonRpcErrorCodes,
        specStandardJsonRpcErrorCodes(doc),
      )
    }

  test("A2A-specific error detail objects match local a2a specification"):
    withSpecDoc { doc =>
      val expectedInfo = specRestErrorInfo(doc)
      assertEquals(A2AError.ErrorInfoType, expectedInfo.typeUrl)
      assertEquals(A2AError.ErrorInfoDomain, expectedInfo.domain)

      a2aSpecificErrorCodes.foreach { case (errorType, code) =>
        val reason = errorInfoReason(errorType)
        val error  = A2AError(code, errorType)
        assertEquals(A2AError.errorInfoReason(code), Some(reason), errorType)
        assertErrorInfo(restErrorInfo(A2AHttpBinding.restErrorBody(error)), expectedInfo, reason, s"REST $errorType")
        assertErrorInfo(
          jsonRpcErrorInfo(JsonRpcResponse.fromA2AError(None, error)),
          expectedInfo,
          reason,
          s"JSON-RPC $errorType",
        )
      }
    }

  test("service parameter constants match local a2a specification"):
    withSpecDoc { doc =>
      val expected = Set(A2AHeader.StandardExtensions, A2AHeader.Version)
      assertEquals(specServiceParameterNames(doc), expected)
      assertEquals(specRegisteredHeaderNames(doc), expected)
    }

  test("service parameter version negotiation follows local a2a specification"):
    withSpecDoc { doc =>
      val fallbackVersion = specEmptyVersionFallback(doc)
      val latestVersion   = A2AProtocol.negotiationVersion(specLatestReleasedVersion(doc))
      val card = AgentCard(
        name = "VersionedAgent",
        description = "Version negotiation parity card",
        supportedInterfaces = List(
          AgentInterface.jsonRpc("https://agent.example.test/a2a/legacy").copy(protocolVersion = s"$fallbackVersion.12"),
          AgentInterface.jsonRpc("https://agent.example.test/a2a/latest").copy(protocolVersion = s"$latestVersion.99"),
        ),
      )

      assertEquals(
        A2AServiceParameters.validateVersion(card, ServerCallContext(), A2ATransport.JSONRPC),
        Right(()),
      )
      assertEquals(
        A2AServiceParameters.validateVersion(card, ServerCallContext(requestedVersion = Some("")), A2ATransport.JSONRPC),
        Right(()),
      )
      assertEquals(
        A2AServiceParameters.validateVersion(
          card,
          ServerCallContext(requestedVersion = Some(s"$latestVersion.7")),
          A2ATransport.JSONRPC,
        ),
        Right(()),
      )
      assert(
        A2AServiceParameters
          .validateVersion(card, ServerCallContext(requestedVersion = Some("9.9")), A2ATransport.JSONRPC)
          .left
          .exists(error => error.code == A2AErrorCode.VersionNotSupported && error.message.contains("9.9"))
      )
    }

  test("protocol version constant matches local a2a specification release"):
    withSpecDoc { doc =>
      assertEquals(A2AProtocol.Version, A2AProtocol.negotiationVersion(specLatestReleasedVersion(doc)))
    }

  test("message/send default execution mode follows local a2a specification"):
    withProtoAndDoc { (proto, doc) =>
      assert(protoReturnImmediatelyDefaultIsBlocking(proto))
      assert(specReturnImmediatelyDefaultIsBlocking(doc))
      assertEquals(ExecutionMode.Default, ExecutionMode.Synchronous)
    }

  test("media type and well-known path constants match local a2a specification"):
    withSpecDoc { doc =>
      assertEquals(specRegisteredMediaType(doc), A2AContentType.A2AJson)
      assertEquals(specJsonRpcContentType(doc), A2AContentType.Json)
      assertEquals(specJsonRpcSseType(doc), A2AContentType.Sse)
      assertEquals(specWellKnownPath(doc), A2APaths.AgentCard)
    }

  // All conformance inputs are read from vendored classpath resources — the
  // single source of truth, identical locally and in CI (no env, no ~/git/a2a).
  private def classpathResource(name: String): String =
    Option(getClass.getResourceAsStream(name))
      .map { stream =>
        try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
        finally stream.close()
      }
      .getOrElse(fail(s"vendored A2A resource $name missing from the test classpath"))

  private def withProtoSpec(assertions: String => Unit): Unit =
    assertions(classpathResource("/a2a.proto"))

  private def withSpecDoc(assertions: String => Unit): Unit =
    assertions(classpathResource("/specification.md"))

  private def withProtoAndDoc(assertions: (String, String) => Unit): Unit =
    withProtoSpec(proto => withSpecDoc(doc => assertions(proto, doc)))

  private def withActsSuiteFiles(assertions: List[ActsFile] => Unit): Unit =
    val suite    = readActsFile("suite.acts.yaml")
    val includes = actsSuiteIncludes(suite)
    assert(includes.nonEmpty, "ACTS suite.acts.yaml did not include any conformance files")
    assertions(includes.map(name => ActsFile(name, readActsFile(name))))

  private def readActsFile(name: String): String =
    classpathResource(s"/acts/$name")

  private def actsSuiteIncludes(suite: String): List[String] =
    val Include = """^\s*-\s+([A-Za-z0-9_-]+\.acts\.yaml)\s*(?:#.*)?$""".r
    suite.linesIterator.collect { case Include(name) => name }.toList

  private def actsOperations(files: List[ActsFile]): Set[String] =
    val Operation = """^\s*operation:\s*"?([a-z][a-z0-9_]*)"?\s*$""".r
    files.flatMap(_.text.linesIterator.collect { case Operation(name) => name }).toSet

  private def actsTestIds(files: List[ActsFile]): Set[String] =
    val TestId = """^\s{6}-\s+id:\s*"?([A-Z][A-Z0-9-]+)"?\s*$""".r
    files.flatMap(_.text.linesIterator.collect { case TestId(id) => id }).toSet

  private def actsRawHttpRequests(files: List[ActsFile]): Set[(String, String)] =
    val HttpMethod = """^\s*method:\s*(GET|POST|DELETE)\s*$""".r
    val Path       = """^\s*path:\s*"(/[^"]*)"\s*$""".r
    var method     = Option.empty[String]
    var requests   = Set.empty[(String, String)]
    files.foreach { file =>
      file.text.linesIterator.foreach {
        case HttpMethod(value) =>
          method = Some(value)
        case Path(path) =>
          method.foreach(value => requests = requests + (value -> path))
          method = None
        case _ =>
          ()
      }
    }
    requests

  private def actsRawJsonRpcMethods(files: List[ActsFile]): Set[String] =
    val Method      = """^\s*method:\s*"?([A-Z][A-Za-z0-9]+)"?\s*$""".r
    val httpMethods = Set("GET", "POST", "DELETE")
    files
      .flatMap(_.text.linesIterator.collect { case Method(name) if !httpMethods.contains(name) => name })
      .toSet

  private def expectedActsSuiteFiles: Set[String] =
    Set(
      "auth-security.acts.yaml",
      "client-parsing.acts.yaml",
      "core-operations.acts.yaml",
      "data-types.acts.yaml",
      "discovery.acts.yaml",
      "error-handling.acts.yaml",
      "history.acts.yaml",
      "multi-turn.acts.yaml",
      "polling.acts.yaml",
      "push-notifications.acts.yaml",
      "streaming.acts.yaml",
      "transport-bindings.acts.yaml",
      "version-negotiation.acts.yaml",
      "wire-format.acts.yaml",
    )

  private def actsOperationMethodTargets: Map[String, String] =
    Map(
      "send_message" -> A2AMethod.MessageSend,
      "send_streaming_message" -> A2AMethod.MessageStream,
      "get_task" -> A2AMethod.TasksGet,
      "list_tasks" -> A2AMethod.TasksList,
      "cancel_task" -> A2AMethod.TasksCancel,
      "subscribe_to_task" -> A2AMethod.TasksResubscribe,
      "set_push_notification_config" -> A2AMethod.PushNotificationConfigSet,
      "get_push_notification_config" -> A2AMethod.PushNotificationConfigGet,
      "list_push_notification_configs" -> A2AMethod.PushNotificationConfigList,
      "delete_push_notification_config" -> A2AMethod.PushNotificationConfigDelete,
      "get_extended_agent_card" -> A2AMethod.GetAuthenticatedExtendedCard,
    )

  private def expectedActsOperations: Set[String] =
    actsOperationMethodTargets.keySet + "get_agent_card"

  private def expectedActsTestIds: Set[String] =
    Set(
      "CARD-CACHE-001",
      "CARD-DISC-001",
      "CARD-DISC-002",
      "CARD-DISC-003",
      "CARD-DISC-004",
      "CARD-DISC-005",
      "CARD-DISC-006",
      "CARD-DISC-007",
      "CARD-EXT-001",
      "CARD-SCHEMA-001",
      "CLIENT-AUTH-001",
      "CLIENT-CAP-001",
      "CLIENT-PARSE-001",
      "CLIENT-PARSE-002",
      "CLIENT-PARSE-003",
      "CLIENT-PARSE-004",
      "CLIENT-PARSE-005",
      "CLIENT-PARSE-006",
      "CORE-CANCEL-001",
      "CORE-CANCEL-002",
      "CORE-CAP-001",
      "CORE-CAP-002",
      "CORE-CTX-001",
      "CORE-ERR-001",
      "CORE-ERR-002",
      "CORE-ERR-003",
      "CORE-ERR-004",
      "CORE-ERR-005",
      "CORE-ERR-006",
      "CORE-ERR-007",
      "CORE-ERR-008",
      "CORE-ERR-009",
      "CORE-EXEC-001",
      "CORE-EXEC-002",
      "CORE-FAIL-001",
      "CORE-GET-001",
      "CORE-GET-002",
      "CORE-HIST-001",
      "CORE-HIST-002",
      "CORE-HIST-003",
      "CORE-HIST-004",
      "CORE-HIST-005",
      "CORE-HIST-006",
      "CORE-LIST-001",
      "CORE-LIST-002",
      "CORE-LIST-003",
      "CORE-MULTI-001",
      "CORE-MULTI-003",
      "CORE-MULTI-005",
      "CORE-MULTI-006",
      "CORE-SEND-001",
      "CORE-SEND-002",
      "CORE-SEND-003",
      "CORE-SEND-004",
      "DM-ART-001",
      "DM-ART-002",
      "DM-ART-003",
      "DM-ART-004",
      "DM-EXTRA-001",
      "DM-FMT-001",
      "DM-FMT-002",
      "DM-FMT-003",
      "DM-SERIAL-001",
      "DM-SERIAL-002",
      "GRPC-STATUS-001",
      "GRPC-STREAM-001",
      "GRPC-STREAM-002",
      "JSONRPC-CT-001",
      "JSONRPC-ENV-001",
      "JSONRPC-ERR-001",
      "JSONRPC-ERR-002",
      "JSONRPC-ERR-003",
      "JSONRPC-SSE-001",
      "PUSH-CFG-001",
      "PUSH-CFG-002",
      "PUSH-CFG-003",
      "PUSH-CFG-004",
      "PUSH-DELIV-001",
      "PUSH-DELIV-002",
      "PUSH-DELIV-003",
      "PUSH-ERR-001",
      "PUSH-IDEM-001",
      "PUSH-LIST-001",
      "REST-CT-001",
      "REST-PD-001",
      "REST-STATUS-001",
      "SEC-AUTH-001",
      "SEC-AUTH-002",
      "SEC-AUTH-003",
      "SEC-AUTH-004",
      "SEC-AUTH-005",
      "SEC-AUTH-006",
      "SEC-EXTCARD-001",
      "SEC-EXTCARD-002",
      "SEC-EXTCARD-003",
      "SEC-EXTCARD-004",
      "SEC-PUSH-001",
      "SEC-PUSH-002",
      "STREAM-MSG-001",
      "STREAM-MULTI-001",
      "STREAM-MULTI-002",
      "STREAM-RESUB-001",
      "STREAM-SSE-001",
      "STREAM-SSE-002",
      "STREAM-SSE-003",
      "STREAM-SSE-004",
      "STREAM-SUB-001",
      "STREAM-SUB-002",
      "STREAM-SUB-003",
      "VER-NEG-001",
      "VER-NEG-002",
    )

  private def expectedActsSpecDivergenceTestIds: Set[String] =
    // ACTS currently expects UnsupportedOperationError for push-disabled config
    // operations, while docs/specification.md requires PushNotificationNotSupportedError.
    Set("CORE-CAP-001", "PUSH-CFG-004")

  private def expectedActsRawHttpRequests: Set[(String, String)] =
    Set(
      "POST" -> "/",
      "GET"  -> "/extendedAgentCard",
      "GET"  -> A2APaths.AgentCard,
      "GET"  -> "/tasks/nonexistent-id",
    )

  private def expectedActsRawJsonRpcMethods: Set[String] =
    Set(
      A2AMethod.MessageSend,
      A2AMethod.MessageStream,
      A2AMethod.TasksGet,
    )

  private def rpcNames(proto: String): Set[String] =
    val Rpc = """^\s*rpc\s+([A-Za-z0-9_]+)\s*\(.*""".r
    serviceBlock(proto).linesIterator.collect { case Rpc(name) => name }.toSet

  private def protoRpcSignatures(proto: String): Map[String, RpcSignature] =
    val Rpc =
      """^\s*rpc\s+([A-Za-z0-9_]+)\s*\(([A-Za-z0-9_.]+)\)\s+returns\s+\((stream\s+)?([A-Za-z0-9_.]+)\).*""".r
    serviceBlock(proto).linesIterator.collect { case Rpc(name, request, streamMarker, response) =>
      name -> RpcSignature(request, response, streaming = Option(streamMarker).exists(_.trim == "stream"))
    }.toMap

  private def protoStreamingRpcs(proto: String): Map[String, Boolean] =
    protoRpcSignatures(proto).view.mapValues(_.streaming).toMap

  private def protoMethodSignatures(proto: String): Map[String, String] =
    val Rpc       = """^\s*rpc\s+([A-Za-z0-9_]+)\s*\(.*""".r
    val Signature = """^\s*option\s+\(google\.api\.method_signature\)\s*=\s*"([^"]+)";.*""".r
    var currentRpc = Option.empty[String]
    var signatures = Map.empty[String, String]
    serviceBlock(proto).linesIterator.foreach {
      case Rpc(name) =>
        currentRpc = Some(name)
      case Signature(signature) =>
        currentRpc.foreach(rpc => signatures = signatures + (rpc -> signature))
      case _ =>
        ()
    }
    signatures

  private def protoBindings(proto: String): List[ProtoBinding] =
    val Rpc   = """^\s*rpc\s+([A-Za-z0-9_]+)\s*\(.*""".r
    val Route = """^\s*(get|post|delete):\s*"([^"]+)".*""".r
    val Body  = """^\s*body:\s*"([^"]+)".*""".r
    var currentRpc = Option.empty[String]
    var bindings   = Vector.empty[ProtoBinding]
    serviceBlock(proto).linesIterator.foreach {
      case Rpc(name) =>
        currentRpc = Some(name)
      case Route(verb, path) =>
        currentRpc.foreach(rpc => bindings = bindings :+ ProtoBinding(rpc, verb, path, body = None))
      case Body(value) =>
        bindings.lastOption.foreach { last =>
          bindings = bindings.dropRight(1) :+ last.copy(body = Some(value))
        }
      case _ =>
        ()
    }
    bindings.toList

  private def protoRestQueryFields(proto: String): Map[String, Set[String]] =
    val signatures = protoRpcSignatures(proto)
    protoBindings(proto)
      .filterNot(_.path.startsWith("/{tenant}/"))
      .filter(binding => binding.verb == "get" || binding.verb == "delete")
      .map { binding =>
        val requestFields = protoJsonFields(proto, signatures(binding.rpc).request)
        binding.rpc -> (requestFields -- protoPathJsonFields(binding.path) - "tenant")
      }
      .toMap

  private def protoPathJsonFields(path: String): Set[String] =
    val PathField = """\{([^}=]+)(?:=[^}]*)?\}""".r
    PathField.findAllMatchIn(path).map(match_ => snakeToLowerCamel(match_.group(1))).filterNot(_ == "tenant").toSet

  private def serviceBlock(proto: String): String =
    val start = proto.indexOf("service A2AService")
    val end   = proto.indexOf("// Configuration of a send message request.")
    assert(start >= 0, "service A2AService not found in proto")
    assert(end > start, "A2AService block end marker not found in proto")
    proto.substring(start, end)

  private def messageBlock(proto: String, messageName: String): String =
    val marker = s"message $messageName"
    block(proto, marker)

  private def enumBlock(proto: String, enumName: String): String =
    block(proto, s"enum $enumName")

  private def block(proto: String, marker: String): String =
    val start  = proto.indexOf(marker)
    assert(start >= 0, s"$marker not found in proto")
    val open = proto.indexOf('{', start)
    assert(open >= 0, s"$marker opening brace not found in proto")
    var index = open + 1
    var depth = 1
    while index < proto.length && depth > 0 do
      proto.charAt(index) match
        case '{' => depth += 1
        case '}' => depth -= 1
        case _   => ()
      index += 1
    assert(depth == 0, s"$marker closing brace not found in proto")
    proto.substring(open + 1, index - 1)

  private def protoJsonFields(proto: String, messageName: String): Set[String] =
    val Field =
      """^\s*(?:optional\s+|repeated\s+)?(?:map<[^>]+>|[A-Za-z_][A-Za-z0-9_.]*)\s+([a-z][A-Za-z0-9_]*)\s*=\s*\d+.*;.*$""".r
    messageBlock(proto, messageName).linesIterator.collect { case Field(name) => snakeToLowerCamel(name) }.toSet

  private def protoRequiredJsonFields(proto: String, messageName: String): Set[String] =
    val Field =
      """^\s*(?:optional\s+|repeated\s+)?(?:map<[^>]+>|[A-Za-z_][A-Za-z0-9_.]*)\s+([a-z][A-Za-z0-9_]*)\s*=\s*\d+.*\bREQUIRED\b.*;.*$""".r
    messageBlock(proto, messageName).linesIterator.collect { case Field(name) => snakeToLowerCamel(name) }.toSet

  private def protoMessageNames(proto: String): Set[String] =
    val Message = """^message\s+([A-Za-z0-9_]+)\s*\{.*$""".r
    proto.linesIterator.collect { case Message(name) => name }.toSet

  private def protoEnumValues(proto: String, enumName: String): Map[String, Int] =
    val Value = """^\s*([A-Z][A-Z0-9_]*)\s*=\s*(\d+)\s*;.*$""".r
    enumBlock(proto, enumName).linesIterator.collect { case Value(name, number) => name -> number.toInt }.toMap

  private def coreProtocolBindings(proto: String): Set[String] =
    val Supported = """.*supported are (.+)\..*""".r
    val Binding   = "`([^`]+)`".r
    messageBlock(proto, "AgentInterface").linesIterator.collectFirst {
      case Supported(values) => Binding.findAllMatchIn(values).map(_.group(1)).toSet
    }.getOrElse(fail("AgentInterface core protocol bindings not found in proto"))

  private final case class TaskStateSemantics(terminal: Set[String], interrupted: Set[String])

  private def protoTaskStateSemantics(proto: String): TaskStateSemantics =
    val Value = """^\s*([A-Z][A-Z0-9_]*)\s*=\s*\d+\s*;.*$""".r
    val Comment = """^\s*//\s?(.*)$""".r
    var pendingComments = Vector.empty[String]
    var terminal        = Set.empty[String]
    var interrupted     = Set.empty[String]

    enumBlock(proto, "TaskState").linesIterator.foreach {
      case Comment(text) =>
        pendingComments = pendingComments :+ text
      case Value(name) =>
        val description = pendingComments.mkString(" ").toLowerCase
        if description.contains("terminal state") then terminal = terminal + name
        if description.contains("interrupted state") then interrupted = interrupted + name
        pendingComments = Vector.empty
      case line if line.trim.isEmpty =>
        ()
      case _ =>
        pendingComments = Vector.empty
    }

    TaskStateSemantics(terminal, interrupted)

  private final case class SpecErrorMapping(jsonRpcCode: Int, grpcStatus: String, httpStatus: Int)

  private def specA2AErrorMappings(doc: String): Map[String, SpecErrorMapping] =
    val section = docSection(doc, "### 5.4. Error Code Mappings", "**Custom Binding Requirements:**")
    val Row = """^\|\s*`([^`]+)`\s*\|\s*`(-?\d+)`\s*\|\s*`([^`]+)`\s*\|\s*`(\d{3})\s+[^`]+`\s*\|.*$""".r
    section.linesIterator.collect {
      case Row(errorType, code, grpcStatus, httpStatus) =>
        errorType -> SpecErrorMapping(code.toInt, grpcStatus, httpStatus.toInt)
    }.toMap

  private def specStandardJsonRpcErrorCodes(doc: String): Map[String, Int] =
    val section = docSection(doc, "**Standard JSON-RPC Error Codes:**", "**A2A-Specific Error Codes:**")
    val Row     = """^\|\s*`(-?\d+)`\s*\|\s*`([^`]+)`\s*\|.*$""".r
    section.linesIterator.collect { case Row(code, errorName) => errorName -> code.toInt }.toMap

  private def specRestErrorInfo(doc: String): SpecErrorInfo =
    val section = docSection(doc, "### 11.6. Error Handling", "### 11.7. Streaming")
    val TypeUrl = """^\s*- `@type`: Set to `"([^"]+)"`.*$""".r
    val Domain  = """^\s*- `domain`: Set to `"([^"]+)"`.*$""".r
    SpecErrorInfo(
      typeUrl = section.linesIterator.collectFirst { case TypeUrl(value) => value }
        .getOrElse(fail("REST ErrorInfo @type not found in specification doc")),
      domain = section.linesIterator.collectFirst { case Domain(value) => value }
        .getOrElse(fail("REST ErrorInfo domain not found in specification doc")),
    )

  private def specMethodMappings(doc: String): List[SpecMethodMapping] =
    val section = docSection(doc, "### 5.3. Method Mapping Reference", "### 5.4. Error Code Mappings")
    val Row =
      """^\|\s*([^|`][^|]*?)\s*\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*`(GET|POST|DELETE)\s+([^`]+)`\s*\|.*$""".r
    section.linesIterator.collect {
      case Row(functionality, jsonRpcMethod, grpcMethod, restVerb, restPath) =>
        SpecMethodMapping(functionality.trim, jsonRpcMethod, grpcMethod, restVerb, restPath)
    }.toList

  private def specServiceParameterNames(doc: String): Set[String] =
    val section = docSection(doc, "**Standard A2A Service Parameters:**", "As service parameter names")
    val Row     = """^\|\s*`([^`]+)`\s*\|.*$""".r
    section.linesIterator.collect { case Row(name) => name }.toSet

  private def specRegisteredHeaderNames(doc: String): Set[String] =
    val section = docSection(doc, "### 14.2. HTTP Header Field Registrations", "### 14.3. Well-Known URI Registration")
    val Header  = """^\*\*Header field name:\*\*\s+(.+)\s*$""".r
    section.linesIterator.collect { case Header(name) => name.trim }.toSet

  private def specLatestReleasedVersion(doc: String): String =
    val Latest = """.*\*\*Latest Released Version\*\* \[`([^`]+)`\].*""".r
    doc.linesIterator.collectFirst { case Latest(version) => version }.getOrElse(fail("latest released A2A version not found"))

  private def specEmptyVersionFallback(doc: String): String =
    val Fallback = """.*empty value as ([0-9]+\.[0-9]+) version\..*""".r
    doc.linesIterator.collectFirst { case Fallback(version) => version }
      .getOrElse(fail("empty A2A-Version fallback not found in specification doc"))

  private def specRegisteredMediaType(doc: String): String =
    val section = docSection(doc, "### 14.1. Media Type Registration", "### 14.2. HTTP Header Field Registrations")
    val Media   = """^####\s+14\.1\.1\.\s+(.+)\s*$""".r
    section.linesIterator.collectFirst { case Media(mediaType) => mediaType.trim }.getOrElse(fail("registered media type not found"))

  private def specJsonRpcContentType(doc: String): String =
    val section = docSection(doc, "### 9.1. Protocol Requirements", "### 9.2. Service Parameter Transmission")
    val Content = """^- \*\*Content-Type:\*\* `([^`]+)`.*$""".r
    section.linesIterator.collectFirst { case Content(contentType) => contentType }.getOrElse(fail("JSON-RPC content type not found"))

  private def specJsonRpcSseType(doc: String): String =
    val section = docSection(doc, "### 9.1. Protocol Requirements", "### 9.2. Service Parameter Transmission")
    val Sse     = """.*\(`([^`]+)`\).*$""".r
    section.linesIterator.collectFirst { case Sse(contentType) => contentType }.getOrElse(fail("JSON-RPC SSE media type not found"))

  private def specWellKnownPath(doc: String): String =
    val section = docSection(doc, "### 14.3. Well-Known URI Registration", "## Appendix A.")
    val Suffix  = """^\*\*URI suffix:\*\*\s+(.+)\s*$""".r
    val suffix = section.linesIterator.collectFirst { case Suffix(value) => value.trim }.getOrElse(fail("well-known URI suffix not found"))
    s"/.well-known/$suffix"

  private def protoReturnImmediatelyDefaultIsBlocking(proto: String): Boolean =
    val section = messageBlock(proto, "SendMessageConfiguration")
    section.contains("If `false` (default)") && section.contains("bool return_immediately = 4;")

  private def specReturnImmediatelyDefaultIsBlocking(doc: String): Boolean =
    val section = docSection(doc, "**Execution Mode:**", "The `return_immediately` field has no effect:")
    val normalized = section.replace("`", "").replace("*", "").toLowerCase(Locale.ROOT)
    normalized.contains("return_immediately: false or unset") &&
      normalized.contains("this is the default behavior")

  private def docSection(doc: String, startMarker: String, endMarker: String): String =
    val start = doc.indexOf(startMarker)
    assert(start >= 0, s"$startMarker not found in A2A specification doc")
    val end = doc.indexOf(endMarker, start + startMarker.length)
    assert(end > start, s"$endMarker not found after $startMarker in A2A specification doc")
    doc.substring(start, end)

  private def errorInfoReason(errorType: String): String =
    val base = errorType.stripSuffix("Error")
    base
      .flatMap { char =>
        if char.isUpper then Seq('_', char)
        else Seq(char.toUpper)
      }
      .mkString
      .stripPrefix("_")

  private def restErrorInfo(body: Json): Json =
    val error = body.asObject.flatMap(_.toMap.get("error")).getOrElse(fail("REST error body missing error object"))
    errorDetailInfo(error, "details", "REST error")

  private def jsonRpcErrorInfo(response: JsonRpcResponse): Json =
    val error = response.error.getOrElse(fail("JSON-RPC response missing error object"))
    error.data match
      case Some(data) => errorDetailInfo(Json.Obj("data" -> data), "data", "JSON-RPC error")
      case None       => fail("JSON-RPC error missing data")

  private def errorDetailInfo(error: Json, field: String, label: String): Json =
    error.asObject
      .flatMap(_.toMap.get(field))
      .flatMap(_.asArray)
      .flatMap(_.headOption)
      .getOrElse(fail(s"$label missing $field ErrorInfo detail"))

  private def assertErrorInfo(
    detail: Json,
    expected: SpecErrorInfo,
    reason: String,
    label: String,
  ): Unit =
    val fields = detail.asObject.map(_.toMap).getOrElse(fail(s"$label ErrorInfo detail must be an object"))
    assertEquals(fields.get("@type").flatMap(_.asString), Some(expected.typeUrl), label)
    assertEquals(fields.get("reason").flatMap(_.asString), Some(reason), label)
    assertEquals(fields.get("domain").flatMap(_.asString), Some(expected.domain), label)

  private def snakeToLowerCamel(name: String): String =
    name.split("_", -1).toList match
      case Nil          => name
      case head :: tail =>
        head + tail.map(part => part.headOption.fold(part)(_.toUpper.toString + part.drop(1))).mkString

  private def sampleRoute(path: String): String =
    path
      .replace("{tenant}", "tenant-a")
      .replace("{configId}", "push-1")
      .replace("pushNotificationConfigs/{id=*}", "pushNotificationConfigs/push-1")
      .replace("{task_id=*}", "task-1")
      .replace("{id=*}", "task-1")
      .replace("{id}", "task-1")

  private def sampleQueryValue(field: String): String =
    field match
      case "contextId"            => "ctx-1"
      case "status"               => "TASK_STATE_WORKING"
      case "pageSize"             => "25"
      case "pageToken"            => "page-1"
      case "historyLength"        => "3"
      case "statusTimestampAfter" => "2026-01-01T00:00:00Z"
      case "includeArtifacts"     => "true"
      case other                  => fail(s"missing sample query value for $other")

  private def routedQueryJsonFields(
    rpc: String,
    query: A2APathRouting.Query,
  ): Either[A2AError, Set[String]] =
    rpc match
      case "GetTask" =>
        A2APathRouting.tasksGet("task-1", query, None).map(jsonFields(_))
      case "ListTasks" =>
        A2APathRouting.tasksList(query, None).map(jsonFields(_))
      case "SubscribeToTask" =>
        A2APathRouting.tasksResubscribe("task-1:subscribe", None).map(jsonFields(_))
      case "GetTaskPushNotificationConfig" =>
        A2APathRouting.pushConfigGet("task-1", "push-1", None).map(jsonFields(_))
      case "ListTaskPushNotificationConfigs" =>
        A2APathRouting.pushConfigList("task-1", query, None).map(jsonFields(_))
      case "GetExtendedAgentCard" =>
        Right(jsonFields(A2ARequest.GetAuthenticatedExtendedCard()))
      case "DeleteTaskPushNotificationConfig" =>
        A2APathRouting.pushConfigDelete("task-1", "push-1", None).map(jsonFields(_))
      case other =>
        fail(s"unexpected query-routed A2AService rpc: $other")

  private def canonicalRestPath(path: String): String =
    path
      .replace("{tenant}/", "")
      .replace("/{tenant}", "")
      .replace("pushNotificationConfigs/{id=*}", "pushNotificationConfigs/{configId}")
      .replace("{task_id=*}", "{id}")
      .replace("{id=*}", "{id}")

  private def expectedRoute(rpc: String): RestRoute =
    rpc match
      case "SendMessage"                      => RestRoute.MessageSend
      case "SendStreamingMessage"             => RestRoute.MessageStream
      case "GetTask"                          => RestRoute.TaskGet("task-1")
      case "ListTasks"                        => RestRoute.TasksList
      case "CancelTask"                       => RestRoute.TaskCancel("task-1:cancel")
      case "SubscribeToTask"                  => RestRoute.TaskSubscribe("task-1:subscribe")
      case "CreateTaskPushNotificationConfig" => RestRoute.PushConfigCreate("task-1")
      case "GetTaskPushNotificationConfig"    => RestRoute.PushConfigGet("task-1", "push-1")
      case "ListTaskPushNotificationConfigs"  => RestRoute.PushConfigList("task-1")
      case "DeleteTaskPushNotificationConfig" => RestRoute.PushConfigDelete("task-1", "push-1")
      case "GetExtendedAgentCard"             => RestRoute.ExtendedAgentCard
      case other                              => fail(s"unexpected A2AService rpc: $other")

  private def routeConsumesRestBody(route: RestRoute): Boolean =
    route match
      case RestRoute.MessageSend | RestRoute.MessageStream | RestRoute.TaskCancel(_) | RestRoute.PushConfigCreate(_) =>
        true
      case _ =>
        false

  private def routeStreams(route: RestRoute): Boolean =
    route match
      case RestRoute.MessageStream | RestRoute.TaskSubscribe(_) => true
      case _                                                    => false

  private def expectedRpcSignatures: Map[String, RpcSignature] =
    Map(
      A2AMethod.MessageSend -> RpcSignature("SendMessageRequest", "SendMessageResponse", streaming = false),
      A2AMethod.MessageStream -> RpcSignature("SendMessageRequest", "StreamResponse", streaming = true),
      A2AMethod.TasksGet -> RpcSignature("GetTaskRequest", "Task", streaming = false),
      A2AMethod.TasksList -> RpcSignature("ListTasksRequest", "ListTasksResponse", streaming = false),
      A2AMethod.TasksCancel -> RpcSignature("CancelTaskRequest", "Task", streaming = false),
      A2AMethod.TasksResubscribe -> RpcSignature("SubscribeToTaskRequest", "StreamResponse", streaming = true),
      A2AMethod.PushNotificationConfigSet ->
        RpcSignature("TaskPushNotificationConfig", "TaskPushNotificationConfig", streaming = false),
      A2AMethod.PushNotificationConfigGet ->
        RpcSignature("GetTaskPushNotificationConfigRequest", "TaskPushNotificationConfig", streaming = false),
      A2AMethod.PushNotificationConfigList ->
        RpcSignature("ListTaskPushNotificationConfigsRequest", "ListTaskPushNotificationConfigsResponse", streaming = false),
      A2AMethod.PushNotificationConfigDelete ->
        RpcSignature("DeleteTaskPushNotificationConfigRequest", "google.protobuf.Empty", streaming = false),
      A2AMethod.GetAuthenticatedExtendedCard ->
        RpcSignature("GetExtendedAgentCardRequest", "AgentCard", streaming = false),
    )

  private def expectedMethodSignatures: Map[String, String] =
    Map(
      A2AMethod.TasksGet -> "id",
      A2AMethod.PushNotificationConfigSet -> "task_id,config",
      A2AMethod.PushNotificationConfigGet -> "task_id,id",
      A2AMethod.PushNotificationConfigList -> "task_id",
      A2AMethod.PushNotificationConfigDelete -> "task_id,id",
    )

  private def expectedRestQueryFields: Map[String, Set[String]] =
    Map(
      A2AMethod.TasksGet -> Set("historyLength", "includeArtifacts"),
      A2AMethod.TasksList -> Set(
        "contextId",
        "status",
        "pageSize",
        "pageToken",
        "historyLength",
        "statusTimestampAfter",
        "includeArtifacts",
      ),
      A2AMethod.TasksResubscribe -> Set.empty,
      A2AMethod.PushNotificationConfigGet -> Set.empty,
      A2AMethod.PushNotificationConfigList -> Set("pageSize", "pageToken"),
      A2AMethod.GetAuthenticatedExtendedCard -> Set.empty,
      A2AMethod.PushNotificationConfigDelete -> Set.empty,
    )

  private def protoJsonFieldSamples: List[(String, Set[String])] =
    val flow = OAuth2Flow(
      authorizationUrl = Some("https://auth.example.test/authorize"),
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
      pkceRequired = true,
    )
    val clientCredentialsFlow = OAuth2Flow(
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val implicitFlow = OAuth2Flow(
      authorizationUrl = Some("https://auth.example.test/authorize"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val passwordFlow = OAuth2Flow(
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val deviceCodeFlow = OAuth2Flow(
      deviceAuthorizationUrl = Some("https://auth.example.test/device"),
      tokenUrl = Some("https://auth.example.test/token"),
      refreshUrl = Some("https://auth.example.test/refresh"),
      scopes = Map("tasks:read" -> "Read tasks"),
    )
    val securityRequirement = SecurityRequirement(Map("bearer" -> List("tasks:read")))
    val extension = AgentExtension(
      uri = "https://example.test/ext",
      description = "Extension",
      required = true,
      params = Some(Json.Obj("enabled" -> Json.Bool(true))),
    )
    val capabilities = AgentCapabilities(
      streaming = true,
      pushNotifications = true,
      extensions = List(extension),
      extendedAgentCard = true,
    )
    val interface = AgentInterface(
      url = "https://agent.example.test/a2a",
      protocolBinding = A2ATransport.JSONRPC,
      tenant = Some("tenant-a"),
      protocolVersion = "1.0",
    )
    val skill = AgentSkill(
      id = "skill-1",
      name = "Skill",
      description = "Skill description",
      tags = List("tag"),
      examples = List("example"),
      inputModes = List("text/plain"),
      outputModes = List("text/plain"),
      securityRequirements = List(securityRequirement),
    )
    val signature = AgentCardSignature(
      `protected` = "protected",
      signature = "signature",
      header = Some(Json.Obj("alg" -> Json.Str("ES256"))),
    )
    val card = AgentCard(
      name = "ParityAgent",
      description = "Parity agent",
      supportedInterfaces = List(interface),
      version = "1.0.0",
      provider = Some(AgentProvider("https://provider.example.test", "Provider")),
      documentationUrl = Some("https://agent.example.test/docs"),
      capabilities = capabilities,
      securitySchemes = Map("bearer" -> SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth")),
      securityRequirements = List(securityRequirement),
      defaultInputModes = List("text/plain"),
      defaultOutputModes = List("text/plain"),
      skills = List(skill),
      signatures = List(signature),
      iconUrl = Some("https://agent.example.test/icon.png"),
    )
    val taskId    = TaskId("task-1")
    val contextId = ContextId("ctx-1")
    val textPart  = Part.Text(
      "hello",
      metadata = Some(Json.Obj("m" -> Json.Str("text"))),
      filename = Some("hello.txt"),
      mediaType = Some("text/plain"),
    )
    val rawPart = Part.File(
      FileContent.Bytes("cmF3", name = Some("raw.bin"), mimeType = Some("application/octet-stream")),
      metadata = Some(Json.Obj("m" -> Json.Str("raw"))),
    )
    val urlPart = Part.File(
      FileContent.Uri("https://files.example.test/file.txt", name = Some("file.txt"), mimeType = Some("text/plain")),
      metadata = Some(Json.Obj("m" -> Json.Str("url"))),
    )
    val dataPart = Part.Data(
      Json.Obj("value" -> Json.Str("data")),
      metadata = Some(Json.Obj("m" -> Json.Str("data"))),
      filename = Some("data.json"),
      mediaType = Some("application/json"),
    )
    val message = A2AMessage(
      role = A2ARole.User,
      parts = List(textPart, rawPart, urlPart, dataPart),
      messageId = MessageId("msg-1"),
      contextId = Some(contextId),
      taskId = Some(taskId),
      referenceTaskIds = List(TaskId("task-ref")),
      metadata = Some(Json.Obj("source" -> Json.Str("parity"))),
      extensions = List(extension.uri),
    )
    val artifact = Artifact(
      artifactId = "artifact-1",
      parts = List(textPart),
      name = Some("Artifact"),
      description = Some("Artifact description"),
      metadata = Some(Json.Obj("kind" -> Json.Str("text"))),
      extensions = List(extension.uri),
    )
    val status = TaskStatus(
      state = TaskState.Working,
      message = Some(message),
      timestamp = Some("2026-05-31T00:00:00Z"),
    )
    val task = A2ATask(
      id = taskId,
      contextId = contextId,
      status = status,
      artifacts = List(artifact),
      history = List(message),
      metadata = Some(Json.Obj("tenant" -> Json.Str("tenant-a"))),
    )
    val pushConfig = TaskPushNotificationConfig(
      tenant = Some("tenant-a"),
      id = Some("push-1"),
      taskId = Some(taskId),
      url = "https://callback.example.test/a2a",
      token = Some("token-1"),
      authentication = Some(AuthenticationInfo("Bearer", "secret")),
    )
    val sendConfig = MessageSendConfiguration(
      acceptedOutputModes = List("text/plain"),
      taskPushNotificationConfig = Some(pushConfig),
      historyLength = Some(3),
      returnImmediately = true,
    )

    List(
      "AgentCard" -> jsonFields(card),
      "AgentProvider" -> jsonFields(AgentProvider("https://provider.example.test", "Provider")),
      "AgentCapabilities" -> jsonFields(capabilities),
      "AgentExtension" -> jsonFields(extension),
      "AgentInterface" -> jsonFields(interface),
      "AgentSkill" -> jsonFields(skill),
      "AgentCardSignature" -> jsonFields(signature),
      "SecurityRequirement" -> jsonFields(securityRequirement),
      "SecurityScheme" -> jsonFieldUnion(
        SecurityScheme.ApiKey("x-api-key", "header", "API key"),
        SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth"),
        SecurityScheme.OAuth2(OAuth2Flows(authorizationCode = Some(flow)), Some("https://auth.example.test/.well-known/oauth"), "OAuth"),
        SecurityScheme.OpenIdConnect("https://auth.example.test/.well-known/openid-configuration", "OIDC"),
        SecurityScheme.MutualTLS("mTLS"),
      ),
      "StringList" -> nestedJsonFields(securityRequirement, "schemes", "bearer"),
      "APIKeySecurityScheme" -> nestedJsonFields(
        SecurityScheme.ApiKey("x-api-key", "header", "API key"),
        "apiKeySecurityScheme",
      ),
      "HTTPAuthSecurityScheme" -> nestedJsonFields(
        SecurityScheme.Http("Bearer", Some("JWT"), "Bearer auth"),
        "httpAuthSecurityScheme",
      ),
      "OAuth2SecurityScheme" -> nestedJsonFields(
        SecurityScheme.OAuth2(OAuth2Flows(authorizationCode = Some(flow)), Some("https://auth.example.test/.well-known/oauth"), "OAuth"),
        "oauth2SecurityScheme",
      ),
      "OpenIdConnectSecurityScheme" -> nestedJsonFields(
        SecurityScheme.OpenIdConnect("https://auth.example.test/.well-known/openid-configuration", "OIDC"),
        "openIdConnectSecurityScheme",
      ),
      "MutualTlsSecurityScheme" -> nestedJsonFields(
        SecurityScheme.MutualTLS("mTLS"),
        "mtlsSecurityScheme",
      ),
      "OAuthFlows" -> jsonFieldUnion(
        OAuth2Flows(authorizationCode = Some(flow)),
        OAuth2Flows(clientCredentials = Some(clientCredentialsFlow)),
        OAuth2Flows(implicit_ = Some(implicitFlow)),
        OAuth2Flows(password = Some(passwordFlow)),
        OAuth2Flows(deviceCode = Some(deviceCodeFlow)),
      ),
      "AuthorizationCodeOAuthFlow" -> jsonFields(flow),
      "ClientCredentialsOAuthFlow" -> jsonFields(clientCredentialsFlow),
      "ImplicitOAuthFlow" -> jsonFields(implicitFlow),
      "PasswordOAuthFlow" -> jsonFields(passwordFlow),
      "DeviceCodeOAuthFlow" -> jsonFields(deviceCodeFlow),
      "SendMessageConfiguration" -> jsonFields(sendConfig),
      "AuthenticationInfo" -> jsonFields(AuthenticationInfo("Bearer", "secret")),
      "TaskPushNotificationConfig" -> jsonFields(pushConfig),
      "Message" -> jsonFields(message),
      "Part" -> jsonFieldUnion(textPart, rawPart, urlPart, dataPart),
      "Artifact" -> jsonFields(artifact),
      "TaskStatus" -> jsonFields(status),
      "Task" -> jsonFields(task),
      "TaskStatusUpdateEvent" -> nestedJsonFields(
        A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, status, metadata = Some(Json.Obj("m" -> Json.Str("status")))): A2AResponse.StreamEvent,
        "statusUpdate",
      ),
      "TaskArtifactUpdateEvent" -> nestedJsonFields(
        A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact, append = true, lastChunk = true, metadata = Some(Json.Obj("m" -> Json.Str("artifact")))): A2AResponse.StreamEvent,
        "artifactUpdate",
      ),
      "SendMessageRequest" -> jsonFields(
        A2ARequest.MessageSend(message, configuration = Some(sendConfig), metadata = Some(Json.Obj("m" -> Json.Str("request"))), tenant = Some("tenant-a"))
      ),
      "GetTaskRequest" -> jsonFields(
        A2ARequest.TasksGet(taskId, historyLength = Some(2), includeArtifacts = Some(false), tenant = Some("tenant-a"))
      ),
      "ListTasksRequest" -> jsonFields(
        A2ARequest.TasksList(
          contextId = Some(contextId),
          status = Some(TaskState.Working),
          pageSize = Some(10),
          pageToken = Some("page-1"),
          historyLength = Some(3),
          statusTimestampAfter = Some("2026-05-31T00:00:00Z"),
          includeArtifacts = Some(true),
          tenant = Some("tenant-a"),
        )
      ),
      "ListTasksResponse" -> jsonFields(
        A2AResponse.ListTasksResult(
          tasks = List(task),
          nextPageToken = Some("next-task-page"),
          pageSize = 1,
          totalSize = 1,
        )
      ),
      "CancelTaskRequest" -> jsonFields(A2ARequest.TasksCancel(taskId, metadata = Some(Json.Obj("m" -> Json.Str("cancel"))), tenant = Some("tenant-a"))),
      "GetTaskPushNotificationConfigRequest" -> jsonFields(A2ARequest.PushNotificationConfigGet(taskId, "push-1", tenant = Some("tenant-a"))),
      "DeleteTaskPushNotificationConfigRequest" -> jsonFields(A2ARequest.PushNotificationConfigDelete(taskId, "push-1", tenant = Some("tenant-a"))),
      "SubscribeToTaskRequest" -> jsonFields(A2ARequest.TasksResubscribe(taskId, tenant = Some("tenant-a"))),
      "ListTaskPushNotificationConfigsRequest" -> jsonFields(
        A2ARequest.PushNotificationConfigList(taskId, pageSize = Some(5), pageToken = Some("push-page"), tenant = Some("tenant-a"))
      ),
      "GetExtendedAgentCardRequest" -> jsonFields(A2ARequest.GetAuthenticatedExtendedCard(Some("tenant-a"))),
      "SendMessageResponse" -> jsonFieldUnion(
        A2AResponse.SendMessageResult.TaskResult(task),
        A2AResponse.SendMessageResult.MessageResult(message.copy(role = A2ARole.Agent)),
      ),
      "StreamResponse" -> jsonFieldUnion(
        A2AResponse.StreamEvent.TaskSnapshot(task),
        A2AResponse.StreamEvent.TaskMessage(taskId, contextId, message.copy(role = A2ARole.Agent)),
        A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, status),
        A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, artifact),
      ),
      "ListTaskPushNotificationConfigsResponse" -> jsonFields(
        A2AResponse.PushNotificationConfigListResult(List(pushConfig), nextPageToken = Some("next-push-page"))
      ),
    )

  private def requiredJsonFieldsByMessage: Map[String, Set[String]] =
    Map(
      "APIKeySecurityScheme" -> Set("location", "name"),
      "AgentCard" -> Set(
        "name",
        "description",
        "supportedInterfaces",
        "version",
        "capabilities",
        "defaultInputModes",
        "defaultOutputModes",
        "skills",
      ),
      "AgentCardSignature"                   -> Set("protected", "signature"),
      "AgentInterface"                       -> Set("url", "protocolBinding", "protocolVersion"),
      "AgentProvider"                        -> Set("url", "organization"),
      "AgentSkill"                           -> Set("id", "name", "description", "tags"),
      "Artifact"                             -> Set("artifactId", "parts"),
      "AuthenticationInfo"                   -> Set("scheme"),
      "AuthorizationCodeOAuthFlow"           -> Set("authorizationUrl", "tokenUrl", "scopes"),
      "CancelTaskRequest"                    -> Set("id"),
      "ClientCredentialsOAuthFlow"           -> Set("tokenUrl", "scopes"),
      "DeleteTaskPushNotificationConfigRequest" -> Set("taskId", "id"),
      "DeviceCodeOAuthFlow"                  -> Set("deviceAuthorizationUrl", "tokenUrl", "scopes"),
      "GetTaskPushNotificationConfigRequest" -> Set("taskId", "id"),
      "GetTaskRequest"                       -> Set("id"),
      "HTTPAuthSecurityScheme"               -> Set("scheme"),
      "ListTaskPushNotificationConfigsRequest" -> Set("taskId"),
      "ListTasksResponse"                    -> Set("tasks", "nextPageToken", "pageSize", "totalSize"),
      "Message"                              -> Set("messageId", "role", "parts"),
      "OAuth2SecurityScheme"                 -> Set("flows"),
      "OpenIdConnectSecurityScheme"          -> Set("openIdConnectUrl"),
      "SendMessageRequest"                   -> Set("message"),
      "SubscribeToTaskRequest"               -> Set("id"),
      "Task"                                 -> Set("id", "status"),
      "TaskArtifactUpdateEvent"              -> Set("taskId", "contextId", "artifact"),
      "TaskPushNotificationConfig"           -> Set("url"),
      "TaskStatus"                           -> Set("state"),
      "TaskStatusUpdateEvent"                -> Set("taskId", "contextId", "status"),
    )

  private def jsonFields[A: JsonEncoder](value: A): Set[String] =
    value.toJsonAST.toOption.map(jsonObjectFields).getOrElse(Set.empty)

  private def jsonFieldUnion[A: JsonEncoder](values: A*): Set[String] =
    values.flatMap(jsonFields[A]).toSet

  private def nestedJsonFields[A: JsonEncoder](value: A, fields: String*): Set[String] =
    fields
      .foldLeft(value.toJsonAST.toOption) { case (json, field) =>
        json.flatMap(_.asObject).flatMap(_.toMap.get(field))
      }
      .map(jsonObjectFields)
      .getOrElse(Set.empty)

  private def jsonObjectFields(json: Json): Set[String] =
    json.asObject.map(_.toMap.keySet).getOrElse(Set.empty)

  private def jsonString[A: JsonEncoder](value: A): String =
    value.toJsonAST.toOption.flatMap(_.asString).getOrElse(fail(s"$value did not encode as a JSON string"))

  private def taskStateValues: Map[String, (Int, TaskState)] =
    Map(
      "TASK_STATE_UNSPECIFIED"  -> (0, TaskState.Unknown),
      "TASK_STATE_SUBMITTED"    -> (1, TaskState.Submitted),
      "TASK_STATE_WORKING"      -> (2, TaskState.Working),
      "TASK_STATE_COMPLETED"    -> (3, TaskState.Completed),
      "TASK_STATE_FAILED"       -> (4, TaskState.Failed),
      "TASK_STATE_CANCELED"     -> (5, TaskState.Canceled),
      "TASK_STATE_INPUT_REQUIRED" -> (6, TaskState.InputRequired),
      "TASK_STATE_REJECTED"     -> (7, TaskState.Rejected),
      "TASK_STATE_AUTH_REQUIRED" -> (8, TaskState.AuthRequired),
    )

  private def roleValues: Map[String, (Int, A2ARole)] =
    Map(
      "ROLE_UNSPECIFIED" -> (0, A2ARole.Unspecified),
      "ROLE_USER"        -> (1, A2ARole.User),
      "ROLE_AGENT"       -> (2, A2ARole.Agent),
    )

  private def transportValues: Set[String] =
    Set(
      A2ATransport.JSONRPC.toRaw,
      A2ATransport.GRPC.toRaw,
      A2ATransport.HTTP_JSON.toRaw,
    )

  private def a2aSpecificErrorCodes: Map[String, Int] =
    Map(
      "TaskNotFoundError"                   -> A2AErrorCode.TaskNotFound,
      "TaskNotCancelableError"              -> A2AErrorCode.TaskNotCancelable,
      "PushNotificationNotSupportedError"   -> A2AErrorCode.PushNotificationNotSupported,
      "UnsupportedOperationError"           -> A2AErrorCode.UnsupportedOperation,
      "ContentTypeNotSupportedError"        -> A2AErrorCode.ContentTypeNotSupported,
      "InvalidAgentResponseError"           -> A2AErrorCode.InvalidAgentResponse,
      "ExtendedAgentCardNotConfiguredError" -> A2AErrorCode.AuthenticatedExtendedCardNotConfigured,
      "ExtensionSupportRequiredError"       -> A2AErrorCode.ExtensionSupportRequired,
      "VersionNotSupportedError"            -> A2AErrorCode.VersionNotSupported,
    )

  private def standardJsonRpcErrorCodes: Map[String, Int] =
    Map(
      "JSONParseError"      -> A2AErrorCode.ParseError,
      "InvalidRequestError" -> A2AErrorCode.InvalidRequest,
      "MethodNotFoundError" -> A2AErrorCode.MethodNotFound,
      "InvalidParamsError"  -> A2AErrorCode.InvalidParams,
      "InternalError"       -> A2AErrorCode.InternalError,
    )
end A2AProtoParitySpec
