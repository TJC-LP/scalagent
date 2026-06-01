package com.tjclp.scalagent.a2a

import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import munit.FunSuite
import org.yaml.snakeyaml.Yaml
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Executes canonical ACTS scenarios against the shared request handler. */
class A2AActsStreamingSpec extends FunSuite:
  private val runtime = Runtime.default

  private final case class ActsTest(
    id: String,
    steps: List[Map[String, Any]],
    transports: Set[String] = Set.empty,
    variables: Map[String, String] = Map.empty)
  private final case class StepResult(
    json: Json,
    error: Option[A2AError] = None,
    status: Option[Int] = None,
    headers: Map[String, String] = Map.empty)
  private final case class ActsState(captures: Map[String, String] = Map.empty):
    def updated(stepId: String, captures: Map[String, String]): ActsState =
      copy(captures = this.captures ++ captures.map { case (name, value) => s"$stepId.$name" -> value })

  private final case class TestConfig(
    taskStore: Option[A2ATaskStore] = None,
    capabilities: AgentCapabilities = AgentCapabilities.default.copy(streaming = true),
    extendedAgentCard: Option[AgentCard] = None,
    pushNotificationStore: Option[A2APushNotificationStore] = None,
    eventStore: Option[A2AEventStore] = None,
    replayProvider: Option[A2AReplayProvider] = None,
    eventReplayLimit: Int = A2AServerDefaults.EventReplayLimit,
    eventStoreAppendTimeout: Duration = A2AServerDefaults.EventStoreAppendTimeout,
    eventStoreLoadTimeout: Duration = A2AServerDefaults.EventStoreLoadTimeout,
    pushNotificationUrlPolicy: PushNotificationUrlPolicy = PushNotificationUrlPolicy.allowAll,
    override val agentCardAuth: A2AAgentCardAuth = A2AAgentCardAuth.permitAll,
    extendedAgentCardAuth: A2AExtendedAgentCardAuth = A2AExtendedAgentCardAuth.permitAll,
    requestAuth: A2ARequestAuth = A2ARequestAuth.permitAll,
    messageResponseSelectorOverride: Option[A2ARequest.MessageSend => Task[Option[A2AMessage]]] = None,
    override val messageResponseOverride: Option[A2ARequest.MessageSend => Task[A2AMessage]] = None)
      extends A2AServerCoreConfig:
    override def messageResponseSelector: Option[A2ARequest.MessageSend => Task[Option[A2AMessage]]] =
      messageResponseSelectorOverride.orElse(super.messageResponseSelector)

  private final case class ActsHttpRequest(
    methodName: String,
    path: String,
    headers: Map[String, String],
    body: String)
      extends A2AHttpRequestView:
    def header(name: String): Option[String]     = headers.get(name)
    override def headerEntries: Iterable[(String, String)] = headers
    def queryParam(name: String): Option[String] = None
    def readBody: Task[String]                   = ZIO.succeed(body)

  private object NoopPushPoster extends A2APushNotificationPoster:
    def post(event: A2AResponse.StreamEvent, config: TaskPushNotificationConfig, headers: List[(String, String)]): Task[Unit] =
      ZIO.unit

  private val executableActsIds: Set[String] =
    Set(
      "CARD-DISC-001",
      "CARD-DISC-002",
      "CARD-DISC-003",
      "CARD-DISC-004",
      "CARD-DISC-005",
      "CARD-DISC-006",
      "CARD-CACHE-001",
      "CARD-SCHEMA-001",
      "CARD-EXT-001",
      "CARD-DISC-007",
      "SEC-AUTH-001",
      "SEC-AUTH-002",
      "SEC-AUTH-003",
      "SEC-AUTH-004",
      "SEC-AUTH-005",
      "SEC-AUTH-006",
      "SEC-PUSH-001",
      "SEC-PUSH-002",
      "SEC-EXTCARD-001",
      "SEC-EXTCARD-002",
      "SEC-EXTCARD-003",
      "SEC-EXTCARD-004",
      "CLIENT-PARSE-001",
      "CLIENT-PARSE-002",
      "CLIENT-PARSE-003",
      "CLIENT-PARSE-004",
      "CLIENT-PARSE-005",
      "CLIENT-PARSE-006",
      "CLIENT-AUTH-001",
      "CLIENT-CAP-001",
      "CORE-SEND-001",
      "CORE-SEND-002",
      "CORE-SEND-003",
      "CORE-GET-001",
      "CORE-GET-002",
      "CORE-CANCEL-001",
      "CORE-CANCEL-002",
      "CORE-FAIL-001",
      "CORE-SEND-004",
      "CORE-LIST-001",
      "CORE-LIST-002",
      "CORE-LIST-003",
      "DM-ART-001",
      "DM-ART-002",
      "DM-ART-003",
      "DM-ART-004",
      "DM-SERIAL-001",
      "DM-SERIAL-002",
      "DM-EXTRA-001",
      "CORE-HIST-001",
      "CORE-HIST-002",
      "CORE-HIST-003",
      "CORE-HIST-004",
      "CORE-HIST-005",
      "CORE-HIST-006",
      "CORE-MULTI-001",
      "CORE-MULTI-005",
      "CORE-MULTI-006",
      "CORE-MULTI-003",
      "CORE-CTX-001",
      "CORE-ERR-001",
      "CORE-ERR-002",
      "CORE-ERR-003",
      "CORE-ERR-004",
      "CORE-ERR-005",
      "JSONRPC-ERR-001",
      "JSONRPC-ERR-002",
      "CORE-ERR-006",
      "CORE-CAP-002",
      "CORE-ERR-007",
      "CORE-ERR-008",
      "CORE-ERR-009",
      "JSONRPC-ERR-003",
      "CORE-EXEC-001",
      "CORE-EXEC-002",
      "PUSH-CFG-001",
      "PUSH-CFG-002",
      "PUSH-CFG-003",
      "PUSH-LIST-001",
      "PUSH-ERR-001",
      "PUSH-IDEM-001",
      "PUSH-DELIV-001",
      "PUSH-DELIV-002",
      "PUSH-DELIV-003",
      "JSONRPC-ENV-001",
      "JSONRPC-CT-001",
      "JSONRPC-SSE-001",
      "GRPC-STATUS-001",
      "GRPC-STREAM-001",
      "GRPC-STREAM-002",
      "REST-STATUS-001",
      "REST-PD-001",
      "REST-CT-001",
      "STREAM-SSE-001",
      "STREAM-SSE-002",
      "STREAM-SSE-003",
      "STREAM-SUB-001",
      "STREAM-SUB-002",
      "STREAM-SUB-003",
      "STREAM-SSE-004",
      "STREAM-MSG-001",
      "STREAM-MULTI-001",
      "STREAM-MULTI-002",
      "STREAM-RESUB-001",
      "VER-NEG-001",
      "VER-NEG-002",
      "DM-FMT-001",
      "DM-FMT-002",
      "DM-FMT-003",
    )

  // Deterministic coordination for the concurrent-stream scenarios
  // (STREAM-MULTI-00x). The tck-stream-basic agent emits its first `working`
  // status (so the runner learns the task id and the secondary can resubscribe
  // to a STILL-ACTIVE task) and then blocks on this gate until the runner
  // releases it after the secondary has subscribed — otherwise the task races
  // to terminal and resubscribe is (correctly) rejected. `None` when no
  // concurrent step is in flight; ACTS scenarios run sequentially.
  private val concurrentStreamGate =
    new java.util.concurrent.atomic.AtomicReference[Option[Promise[Nothing, Unit]]](None)

  // ACTS conformance fixtures are vendored in-repo (test/resources/acts/) and
  // read from the test classpath — identical locally and in CI.
  private lazy val actsTests: List[ActsTest] =
    loadActsTests.filter(test => executableActsIds.contains(test.id))

  test("ACTS executable subset stays explicit"):
    assertEquals(actsTests.map(_.id).toSet, executableActsIds)

  actsTests.foreach { acts =>
    test(s"ACTS ${acts.id} scenario"):
      runTask(runActsTest(acts))
  }

  private def runTask[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(effect)
    }

  private def runActsTest(acts: ActsTest): Task[Unit] =
    for
      store    <- ZIO.succeed(A2ATaskStore.inMemory)
      registry <- A2ARuntimeRegistry.make
      capabilities = AgentCapabilities.default.copy(
        streaming = acts.id != "CORE-CAP-002",
        pushNotifications = pushNotificationActs(acts.id),
        extendedAgentCard = extendedAgentCardActs(acts.id),
      )
      card = actsAgentCard(acts.id, capabilities)
      core = A2AServerCore.make(
        TestConfig(
          taskStore = Some(store),
          capabilities = capabilities,
          extendedAgentCard = Option.when(extendedAgentCardActs(acts.id))(card.copy(
            description = "ACTS extended card",
            skills = List(AgentSkill("extended", "Extended ACTS", "Extended ACTS fixture", tags = List("test"))),
          )),
          agentCardAuth = agentCardAuth(acts.id),
          extendedAgentCardAuth = extendedAgentCardAuth(acts.id),
          requestAuth = requestAuth(acts.id),
          messageResponseSelectorOverride = Option.when(messageResponseActs(acts.id))(messageResponder),
        ),
        runtime,
        registry,
        NoopPushPoster,
        () => card,
        tckExecution,
      )
      _ <- runSteps(core, acts.steps, ActsState(acts.variables), acts.id)
    yield ()

  private def runSteps(core: A2AServerCore, steps: List[Map[String, Any]], state: ActsState, actsId: String): Task[ActsState] =
    steps match
      case Nil => ZIO.succeed(state)
      case step :: tail =>
        for
          next <- runStep(core, step, state, actsId)
          done <- runSteps(core, tail, next, actsId)
        yield done

  private def runStep(core: A2AServerCore, step: Map[String, Any], state: ActsState, actsId: String): Task[ActsState] =
    val stepId = step("id").toString
    val once =
      step.get("client_response") match
        case Some(clientResponse) =>
          runClientResponseStep(stepId, toJson(clientResponse, state))
        case None =>
          step.get("raw") match
            case Some(raw) =>
              runRawStep(core, stepId, toJson(raw, state))
            case None =>
              val operation = step("operation").toString
              val params    = withGeneratedMessageId(toJson(step.getOrElse("params", Map.empty[String, Any]), state), stepId)
              runOperationStep(core, stepId, operation, params, actsId)
    for
      outcome <- runWithRepeat(stepId, step, once)
      _       <- checkOutcome(stepId, step, outcome, state)
      _       <- if outcome.error.isEmpty then checkAssertions(stepId, step, outcome.json, state) else ZIO.unit
    yield outcome.error.fold(capture(stepId, step, outcome.json, state))(_ => state)

  private def runOperationStep(core: A2AServerCore, stepId: String, operation: String, params: Json, actsId: String): Task[StepResult] =
    if grpcActs(actsId) then runGrpcOperationStep(core, stepId, operation, params)
    else
      runDirectOperationStep(core, stepId, operation, params, actsId)

  private def runDirectOperationStep(
    core: A2AServerCore,
    stepId: String,
    operation: String,
    params: Json,
    actsId: String,
  ): Task[StepResult] =
    operation match
      case "get_agent_card" =>
        if field(params, "extended").contains(Json.Bool(true)) then
          core.requestHandler
            .getExtendedAgentCard(ServerCallContext(authorization = Some("Bearer acts")))
            .map(card => StepResult(card.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        else
          ZIO.succeed(StepResult(core.requestHandler.agentCard.toJsonAST.toOption.get))
      case "send_message" =>
        decode[A2ARequest.MessageSend](params).flatMap { request =>
          core.requestHandler
            .sendMessage(request, ServerCallContext())
            .map(result => StepResult(result.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "send_streaming_message" =>
        decode[A2ARequest.MessageSend](params).flatMap { request =>
          val run =
            if concurrentStreamActs(actsId) then runConcurrentStreamingStep(core, stepId, request, closePrimary = actsId == "STREAM-MULTI-002")
            else
              core.requestHandler
                .sendMessageStream(request, ServerCallContext())
                .flatMap(_.runCollect.timeoutFail(new RuntimeException(s"$stepId stream did not close"))(3.seconds))
                .map(events => StepResult(streamJson(events.toList)))
          run.catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "subscribe_to_task" =>
        decode[A2ARequest.TasksResubscribe](params).flatMap { request =>
          core.requestHandler
            .resubscribe(request, ServerCallContext())
            .flatMap(_.runCollect.timeoutFail(new RuntimeException(s"$stepId subscribe stream did not close"))(3.seconds))
            .map(events => StepResult(streamJson(events.toList)))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "get_task" =>
        decode[A2ARequest.TasksGet](params).flatMap { request =>
          core.requestHandler
            .getTask(request, ServerCallContext())
            .map(task => StepResult(task.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "cancel_task" =>
        decode[A2ARequest.TasksCancel](params).flatMap { request =>
          core.requestHandler
            .cancelTask(request, ServerCallContext())
            .map(task => StepResult(task.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "list_tasks" =>
        decode[A2ARequest.TasksList](params).flatMap { request =>
          core.requestHandler
            .listTasks(request, ServerCallContext())
            .map(result => StepResult(result.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "set_push_notification_config" =>
        pushConfigParams(params).flatMap { request =>
          core.requestHandler
            .createPushConfig(request, ServerCallContext())
            .map(result => StepResult(result.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "get_push_notification_config" =>
        decode[A2ARequest.PushNotificationConfigGet](params).flatMap { request =>
          core.requestHandler
            .getPushConfig(request, ServerCallContext())
            .map(result => StepResult(result.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "list_push_notification_configs" =>
        decode[A2ARequest.PushNotificationConfigList](params).flatMap { request =>
          core.requestHandler
            .listPushConfigs(request, ServerCallContext())
            .map(result => StepResult(result.toJsonAST.toOption.get))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case "delete_push_notification_config" =>
        decode[A2ARequest.PushNotificationConfigDelete](params).flatMap { request =>
          core.requestHandler
            .deletePushConfig(request, ServerCallContext())
            .as(StepResult(Json.Obj()))
            .catchAll(error => ZIO.succeed(errorResult(error)))
        }
      case other =>
        ZIO.fail(new RuntimeException(s"Unsupported executable ACTS operation: $other"))

  private def runGrpcOperationStep(
    core: A2AServerCore,
    stepId: String,
    operation: String,
    params: Json,
  ): Task[StepResult] =
    grpcRequest(operation, params).flatMap { request =>
      A2AGrpcBinding
        .dispatch(
          request,
          ServerCallContext(),
          core.requestHandler.agentCard,
          core.requestHandler.agentCard.capabilities,
          core.requestHandler,
        )
        .flatMap {
          case A2AGrpcDispatch.Unary(response, _) =>
            ZIO.succeed(StepResult(grpcResponseJson(response)))
          case A2AGrpcDispatch.Stream(events, _) =>
            events.runCollect
              .timeoutFail(new RuntimeException(s"$stepId gRPC stream did not close"))(3.seconds)
              .map(events => StepResult(streamJson(events.toList)))
          case A2AGrpcDispatch.Error(error, _) =>
            ZIO.succeed(errorResult(error))
        }
        .catchAll(error => ZIO.succeed(errorResult(error)))
    }

  private def grpcRequest(operation: String, params: Json): Task[A2AGrpcRequest] =
    operation match
      case "send_message" =>
        decode[A2ARequest.MessageSend](params).map(A2AGrpcRequest.MessageSend(_))
      case "send_streaming_message" =>
        decode[A2ARequest.MessageSend](params).map(A2AGrpcRequest.MessageStream(_))
      case "subscribe_to_task" =>
        decode[A2ARequest.TasksResubscribe](params).map(A2AGrpcRequest.TasksResubscribe(_))
      case "get_task" =>
        decode[A2ARequest.TasksGet](params).map(A2AGrpcRequest.TasksGet(_))
      case "cancel_task" =>
        decode[A2ARequest.TasksCancel](params).map(A2AGrpcRequest.TasksCancel(_))
      case "list_tasks" =>
        decode[A2ARequest.TasksList](params).map(A2AGrpcRequest.TasksList(_))
      case "set_push_notification_config" =>
        pushConfigParams(params).map(A2AGrpcRequest.PushNotificationConfigSet(_))
      case "get_push_notification_config" =>
        decode[A2ARequest.PushNotificationConfigGet](params).map(A2AGrpcRequest.PushNotificationConfigGet(_))
      case "list_push_notification_configs" =>
        decode[A2ARequest.PushNotificationConfigList](params).map(A2AGrpcRequest.PushNotificationConfigList(_))
      case "delete_push_notification_config" =>
        decode[A2ARequest.PushNotificationConfigDelete](params).map(A2AGrpcRequest.PushNotificationConfigDelete(_))
      case "get_extended_agent_card" =>
        decode[A2ARequest.GetAuthenticatedExtendedCard](params).map(A2AGrpcRequest.GetAuthenticatedExtendedCard(_))
      case other =>
        ZIO.fail(new RuntimeException(s"Unsupported executable ACTS gRPC operation: $other"))

  private def grpcResponseJson(response: A2AGrpcResponse): Json =
    response match
      case A2AGrpcResponse.SendMessage(result) =>
        result.toJsonAST.toOption.get
      case A2AGrpcResponse.Task(task) =>
        task.toJsonAST.toOption.get
      case A2AGrpcResponse.ListTasks(result) =>
        result.toJsonAST.toOption.get
      case A2AGrpcResponse.PushNotificationConfig(config) =>
        config.toJsonAST.toOption.get
      case A2AGrpcResponse.PushNotificationConfigList(result) =>
        result.toJsonAST.toOption.get
      case A2AGrpcResponse.Empty =>
        Json.Obj()
      case A2AGrpcResponse.AgentCard(card) =>
        card.toJsonAST.toOption.get

  private def runConcurrentStreamingStep(
    core: A2AServerCore,
    stepId: String,
    request: A2ARequest.MessageSend,
    closePrimary: Boolean,
  ): Task[StepResult] =
    val program =
      for
        releaseGate   <- Promise.make[Nothing, Unit]
        _             <- ZIO.succeed(concurrentStreamGate.set(Some(releaseGate)))
        primary       <- core.requestHandler.sendMessageStream(request, ServerCallContext())
        taskIdReady   <- Promise.make[Nothing, TaskId]
        secondarySeen <- Promise.make[Nothing, Unit]
        primaryFiber <- primary
          .tap(event => taskIdReady.succeed(event.taskId).ignore)
          .runCollect
          .fork
        taskId <- taskIdReady.await.timeoutFail(new RuntimeException(s"$stepId primary stream emitted no task id"))(500.millis)
        // Task is still `working` here (the agent is blocked on releaseGate), so
        // the secondary resubscribes to an active task rather than racing it to
        // terminal. After it subscribes we release the agent, so both streams
        // receive the artifact + completion events.
        secondary <- core.requestHandler.resubscribe(A2ARequest.TasksResubscribe(taskId), ServerCallContext())
        secondaryFiber <- secondary
          .tap(_ => secondarySeen.succeed(()).ignore)
          .runCollect
          .fork
        _ <- secondarySeen.await.timeoutFail(new RuntimeException(s"$stepId secondary stream did not subscribe"))(500.millis)
        _ <- releaseGate.succeed(())
        _ <- if closePrimary then ZIO.sleep(10.millis) *> primaryFiber.interrupt.unit else ZIO.unit
        secondaryEvents <- secondaryFiber.join.timeoutFail(new RuntimeException(s"$stepId secondary stream did not close"))(3.seconds)
        primaryEvents <-
          if closePrimary then ZIO.succeed(Chunk.empty[A2AResponse.StreamEvent])
          else primaryFiber.join.timeoutFail(new RuntimeException(s"$stepId primary stream did not close"))(3.seconds)
        _ <-
          if closePrimary then ZIO.unit
          else verifyConcurrentStreamEvents(stepId, primaryEvents.toList, secondaryEvents.toList)
      yield StepResult(streamJson(secondaryEvents.toList))

    program.ensuring(ZIO.succeed(concurrentStreamGate.set(None)))

  private def verifyConcurrentStreamEvents(
    stepId: String,
    primaryEvents: List[A2AResponse.StreamEvent],
    secondaryEvents: List[A2AResponse.StreamEvent],
  ): Task[Unit] =
    val primaryComparable   = comparableConcurrentEvents(primaryEvents)
    val secondaryComparable = comparableConcurrentEvents(secondaryEvents)
    // The secondary subscribes after the primary (once it has the task id), so
    // it sees a SUFFIX of the primary's events — it must receive every event
    // published from its subscription onward, identically.
    if secondaryComparable.nonEmpty && primaryComparable.endsWith(secondaryComparable) then ZIO.unit
    else
      ZIO.fail(
        new RuntimeException(
          s"$stepId concurrent streams diverged: primary=${streamEventsJson(primaryComparable)}, secondary=${streamEventsJson(secondaryComparable)}"
        )
      )

  private def comparableConcurrentEvents(events: List[A2AResponse.StreamEvent]): List[A2AResponse.StreamEvent] =
    events.filterNot(_.isInstanceOf[A2AResponse.StreamEvent.TaskSnapshot])

  private def streamEventsJson(events: List[A2AResponse.StreamEvent]): List[String] =
    events.map(event => event.toJsonAST.toOption.map(_.toJson).getOrElse(event.toString))

  private def runRawStep(core: A2AServerCore, stepId: String, raw: Json): Task[StepResult] =
    val fields = raw.asObject.map(_.toMap).getOrElse(Map.empty)
    val method = fields.get("method").flatMap(_.asString).getOrElse("GET")
    val path   = fields.get("path").flatMap(_.asString).getOrElse("/")
    val headers = fields
      .get("headers")
      .flatMap(_.asObject)
      .map(_.toMap.flatMap { case (name, value) => value.asString.map(name -> _) })
      .getOrElse(Map.empty)
    val body =
      fields.get("body_raw").flatMap(_.asString).getOrElse {
        fields
          .get("body")
          .map(json => withGeneratedMessageId(json, stepId).toJson)
          .getOrElse("")
      }
    val request = ActsHttpRequest(method, path, headers, body)
    A2AHttpBinding
      .dispatchHttp(
        request,
        core.requestHandler.agentCard,
        core.requestHandler.agentCard.capabilities,
        core.requestHandler,
      )
      .flatMap(httpPlanToStepResult)

  private def runClientResponseStep(stepId: String, clientResponse: Json): Task[StepResult] =
    val fields    = clientResponse.asObject.map(_.toMap).getOrElse(Map.empty)
    val operation = fields.get("operation").flatMap(_.asString).getOrElse("")
    val payload   = fields.getOrElse("wire_payload", Json.Obj())

    operation match
      case "send_message" =>
        decode[JsonRpcResponse](payload).flatMap { response =>
          response.error match
            case Some(error) => ZIO.succeed(StepResult(Json.Obj("error" -> error.toJsonAST.toOption.get)))
            case None =>
              val parsed =
                for
                  resultJson <- response.getResult.left.map(error => new RuntimeException(error.message))
                  result <- A2AClientPayloadNormalizer
                    .decode[A2AResponse.SendMessageResult](resultJson)
                    .left
                    .map(new RuntimeException(_))
                yield StepResult(result.toJsonAST.toOption.get)
              parsed.fold(ZIO.fail(_), ZIO.succeed(_))
        }
      case "get_task" =>
        decode[JsonRpcResponse](payload).flatMap { response =>
          response.error match
            case Some(error) => ZIO.succeed(StepResult(Json.Obj("error" -> error.toJsonAST.toOption.get)))
            case None =>
              val parsed =
                for
                  resultJson <- response.getResult.left.map(error => new RuntimeException(error.message))
                  task <- A2AClientPayloadNormalizer
                    .decode[A2ATask](resultJson)
                    .left
                    .map(new RuntimeException(_))
                yield StepResult(task.toJsonAST.toOption.get)
              parsed.fold(ZIO.fail(_), ZIO.succeed(_))
        }
      case "get_agent_card" | "get_extended_agent_card" =>
        A2AClientPayloadNormalizer
          .decode[AgentCard](payload)
          .map(card => StepResult(clientAgentCardJson(card)))
          .fold(error => ZIO.fail(new RuntimeException(s"$stepId could not parse AgentCard: $error")), ZIO.succeed(_))
      case other =>
        ZIO.fail(new RuntimeException(s"Unsupported executable ACTS client_response operation: $other"))

  private def httpPlanToStepResult(plan: A2AHttpResponsePlan): Task[StepResult] =
    plan match
      case A2AHttpResponsePlan.Text(body, status, headers) =>
        ZIO.succeed(StepResult(parseJsonOrString(body), status = Some(status), headers = headers.toMap))
      case A2AHttpResponsePlan.Empty(status, headers) =>
        ZIO.succeed(StepResult(Json.Obj(), status = Some(status), headers = headers.toMap))
      case A2AHttpResponsePlan.Sse(stream, isJsonRpc, headers, errorId) =>
        A2AHttpBinding
          .sseWireStream(stream, isJsonRpc, errorId)
          .runCollect
          .timeoutFail(new RuntimeException("raw SSE stream did not close"))(3.seconds)
          .map(frames => StepResult(Json.Obj("events" -> Json.Arr(frames.toList.flatMap(sseFrameJson)*)), status = Some(200), headers = headers.toMap))

  private def runWithRepeat(stepId: String, step: Map[String, Any], once: Task[StepResult]): Task[StepResult] =
    step.get("repeat").collect { case repeat: Map[?, ?] => repeat } match
      case None => once
      case Some(repeat) =>
        val repeatJson  = toJson(repeat, ActsState())
        val maxAttempts = field(repeatJson, "max_attempts").flatMap(intValue).getOrElse(1).max(1)
        val delayMs     = field(repeatJson, "delay_ms").flatMap(intValue).getOrElse(0).max(0)
        val until       = field(repeatJson, "until").flatMap(_.asString).getOrElse("")
        val delay       = Duration.fromMillis(delayMs.toLong.min(50L))

        def loop(remaining: Int): Task[StepResult] =
          once.flatMap { result =>
            if result.error.nonEmpty || repeatSatisfied(result.json, until) then ZIO.succeed(result)
            else if remaining <= 1 then
              ZIO.fail(new RuntimeException(s"$stepId repeat condition not met after $maxAttempts attempts: $until"))
            else ZIO.sleep(delay) *> loop(remaining - 1)
          }

        loop(maxAttempts)

  private def checkOutcome(stepId: String, step: Map[String, Any], result: StepResult, state: ActsState): Task[Unit] =
    step.get("expect_error") match
      case Some(expect) =>
        result.error match
          case Some(error) => checkError(stepId, error, toJson(expect, state))
          case None        => ZIO.fail(new RuntimeException(s"$stepId expected error, got ${result.json.toJson}"))
      case None if expectAllowsError(step, state) =>
        result.error match
          case Some(_) => ZIO.unit
          case None    => checkExpect(stepId, step, result, state)
      case None =>
        result.error match
          case Some(error) => ZIO.fail(error)
          case None =>
            if step.contains("expect_stream") then checkStream(stepId, step, result.json, state)
            else checkExpect(stepId, step, result, state)

  private def checkError(stepId: String, error: A2AError, expected: Json): Task[Unit] =
    val fields   = expected.asObject.map(_.toMap).getOrElse(Map.empty)
    val expectedCode = fields.get("code").orElse(fields.get("error_type"))
    val ok = expectedCode.forall(matchesError(error, _))
    if ok then ZIO.unit
    else ZIO.fail(new RuntimeException(s"$stepId expected error ${expectedCode.map(_.toJson)}, got ${errorName(error.code)}: ${error.message}"))

  private def matchesError(error: A2AError, expected: Json): Boolean =
    expected match
      case Json.Str(name) => errorName(error.code) == name
      case Json.Obj(fields) =>
        fields.toMap.get("one_of").exists {
          case Json.Arr(values) => values.exists(matchesError(error, _))
          case _                => false
        }
      case _ => true

  private val InCondition = """^\s*([A-Za-z0-9_.\[\]*]+)\s+in\s+\[([^\]]+)\]\s*$""".r
  private val EqCondition = """^\s*([A-Za-z0-9_.\[\]*]+)\s*==\s*([A-Za-z0-9_]+)\s*$""".r

  private def repeatSatisfied(json: Json, condition: String): Boolean =
    condition match
      case InCondition(path, values) =>
        val expected = values.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet
        select(json, path).flatMap(jsonString).exists(expected.contains)
      case EqCondition(path, expected) =>
        select(json, path).flatMap(jsonString).contains(expected)
      case "" =>
        true
      case _ =>
        false

  private def checkExpect(stepId: String, step: Map[String, Any], result: StepResult, state: ActsState): Task[Unit] =
    step.get("expect").orElse(step.get("expect_parsed")) match
      case None => ZIO.unit
      case Some(expect) =>
        val expectJson = toJson(expect, state)
        val fields     = expectJson.asObject.map(_.toMap).getOrElse(Map.empty)
        val statusCheck = fields.get("status").flatMap(intValue) match
          case Some(expected) if result.status.exists(_ != expected) =>
            Left(s"$stepId expected HTTP status $expected, got ${result.status.get}")
          case _ => Right(())
        val bodyCheck = field(expectJson, "body") match
          case Some(body) =>
            matchJson(result.json, body)
          case None if fields.keySet.subsetOf(Set("status")) =>
            Right(())
          case None =>
            matchJson(result.json, expectJson)
        statusCheck
          .flatMap(_ => bodyCheck)
          .left
          .map(error => new RuntimeException(s"$stepId $error"))
          .fold(ZIO.fail(_), _ => ZIO.unit)

  private def checkStream(stepId: String, step: Map[String, Any], stream: Json, state: ActsState): Task[Unit] =
    step.get("expect_stream") match
      case None => ZIO.unit
      case Some(expectAny) =>
        val expect = toJson(expectAny, state)
        val fields = expect.asObject.map(_.toMap).getOrElse(Map.empty)
        val jsonEvents = field(stream, "events").flatMap(_.asArray).map(_.toList).getOrElse(Nil)
        val minCountOk = fields.get("min_count").flatMap(intValue).forall(jsonEvents.size >= _)
        if !minCountOk then ZIO.fail(new RuntimeException(s"$stepId emitted ${jsonEvents.size} events, below min_count"))
        else
          val finalCheck = fields.get("final_event") match
            case Some(expected) =>
              jsonEvents.lastOption.toRight(s"$stepId emitted no events").flatMap(matchJson(_, expected))
            case None => Right(())
          val eventChecks = fields.get("events") match
            case Some(Json.Arr(checks)) =>
              checks.toList.foldLeft[Either[String, Unit]](Right(()))((acc, check) => acc.flatMap(_ => checkEvent(stepId, jsonEvents, check)))
            case _ => Right(())
          val eachEventCheck = fields.get("each_event") match
            case Some(expected) =>
              jsonEvents.foldLeft[Either[String, Unit]](Right(()))((acc, event) => acc.flatMap(_ => matchJson(event, expected)))
            case None => Right(())
          finalCheck
            .flatMap(_ => eventChecks)
            .flatMap(_ => eachEventCheck)
            .flatMap(_ => checkMonotonicState(stepId, jsonEvents, fields.get("ordering")))
            .left
            .map(new RuntimeException(_))
            .fold(ZIO.fail(_), _ => ZIO.unit)

  private def checkAssertions(stepId: String, step: Map[String, Any], json: Json, state: ActsState): Task[Unit] =
    val assertions = step.get("assertions").collect { case values: List[?] => values }.getOrElse(Nil)
    ZIO.foreachDiscard(assertions.zipWithIndex) { case (assertion, index) =>
      checkAssertion(stepId, index, json, toJson(assertion, state)).left
        .map(new RuntimeException(_))
        .fold(ZIO.fail(_), _ => ZIO.unit)
    }

  private def checkAssertion(stepId: String, index: Int, json: Json, assertion: Json): Either[String, Unit] =
    val fields = assertion.asObject.map(_.toMap).getOrElse(Map.empty)
    fields.get("any") match
      case Some(anyJson) =>
        val anyFields = anyJson.asObject.map(_.toMap).getOrElse(Map.empty)
        val path      = anyFields.get("path").flatMap(_.asString).getOrElse("")
        val expected  = normalizeActsExpectedMatch(anyFields.getOrElse("match", Json.Obj()))
        val values    = select(json, path)
        if values.exists(value => matchJson(value, expected).isRight) then Right(())
        else Left(s"$stepId assertion $index no value at $path matched ${expected.toJson}")
      case None => Right(())

  private def normalizeActsExpectedMatch(expected: Json): Json =
    A2AClientPayloadNormalizer.normalize(expected)

  private def checkEvent(stepId: String, events: List[Json], check: Json): Either[String, Unit] =
    val fields = check.asObject.map(_.toMap).getOrElse(Map.empty)
    fields.get("index").flatMap(intValue) match
      case Some(index) =>
        events.lift(index).toRight(s"$stepId missing event index $index").flatMap(matchJson(_, check))
      case None =>
        if events.exists(event => matchJson(event, check).isRight) then Right(())
        else Left(s"$stepId no stream event matched ${check.toJson}")

  private def checkMonotonicState(stepId: String, events: List[Json], ordering: Option[Json]): Either[String, Unit] =
    if !ordering.contains(Json.Str("monotonic_state")) then Right(())
    else
      val ranks = events.flatMap(event => select(event, "statusUpdate.status.state").flatMap(_.asString).headOption).map {
        case "TASK_STATE_SUBMITTED" => 0
        case "TASK_STATE_WORKING"   => 1
        case _                      => 2
      }
      if ranks == ranks.sorted then Right(()) else Left(s"$stepId stream state order regressed: $ranks")

  private def expectAllowsError(step: Map[String, Any], state: ActsState): Boolean =
    step.get("expect").exists(expect => field(toJson(expect, state), "error").nonEmpty)

  private def capture(stepId: String, step: Map[String, Any], json: Json, state: ActsState): ActsState =
    val captures = step.get("capture").collect { case values: Map[?, ?] => values }.getOrElse(Map.empty)
    val values = captures.map { case (name, path) =>
      val value = select(json, path.toString).headOption.flatMap(jsonString).getOrElse {
        throw new RuntimeException(s"$stepId capture ${name.toString} missing path ${path.toString} in ${json.toJson}")
      }
      name.toString -> value
    }
    state.updated(stepId, values.asInstanceOf[Map[String, String]])

  private def tckExecution(prepared: A2ARequestHandler.PreparedRun, publisher: A2AEventPublisher): Task[Unit] =
    val text      = prepared.message.text
    val taskId    = prepared.task.id
    val contextId = prepared.task.contextId
    def agent(text: String): A2AMessage =
      A2AMessage.agentText(text, Some(contextId)).copy(taskId = Some(taskId))
    def status(status: TaskStatus, finalEvent: Boolean = false): UIO[Unit] =
      publisher.publish(A2AResponse.StreamEvent.TaskStatusUpdate(taskId, contextId, status, `final` = finalEvent))
    def artifact(id: String, text: String): UIO[Unit] =
      publisher.publish(A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, Artifact(id, parts = List(Part.Text(text)))))
    def artifactPart(id: String, part: Part): UIO[Unit] =
      publisher.publish(A2AResponse.StreamEvent.TaskArtifactUpdate(taskId, contextId, Artifact(id, parts = List(part))))
    def complete: UIO[Unit] =
      status(TaskStatus.completed(agent("done")), finalEvent = true)

    if text.trim == "done" then complete
    else if text.contains("tck-artifact-text") then
      artifact("text-artifact", "hello") *> complete
    else if text.contains("tck-artifact-data") then
      artifactPart("data-artifact", Part.Data(Json.Obj("structured" -> Json.Bool(true)))) *> complete
    else if text.contains("tck-artifact-file-url") then
      artifactPart(
        "file-url-artifact",
        Part.File(
          FileContent.Uri(
            "https://example.com/report.pdf",
            name = Some("report.pdf"),
            mimeType = Some("application/pdf"),
          )
        ),
      ) *> complete
    else if text.contains("tck-artifact-file") then
      artifactPart(
        "file-artifact",
        Part.File(
          FileContent.Bytes(
            "JVBERi0xLjQ=",
            name = Some("report.pdf"),
            mimeType = Some("application/pdf"),
          )
        ),
      ) *> complete
    else if text.contains("tck-auth-required") then
      status(TaskStatus.authRequired(agent("auth required")), finalEvent = true)
    else if text.contains("tck-multi-turn") || prepared.task.history.size > 1 then
      status(TaskStatus.inputRequired(agent("need input")))
    else if text.contains("tck-long-running") then
      status(TaskStatus.working(Some(agent("working")))) *>
        ZIO.sleep(150.millis) *>
        artifact("long-running-artifact", "done") *>
        complete
    else if text.contains("tck-stream-chunked") then
      status(TaskStatus.working(Some(agent("working")))) *>
        artifact("chunk-1", "one") *>
        artifact("chunk-2", "two") *>
        complete
    else if text.contains("tck-stream-basic") then
      // Emit `working` first (lets the runner read the task id), then — for the
      // concurrent scenarios — block until the secondary has resubscribed, so
      // the shared artifact/complete events reach BOTH streams and resubscribe
      // never races the task to terminal. No-op when no gate is set.
      status(TaskStatus.working(Some(agent("working")))) *>
        ZIO.suspendSucceed(concurrentStreamGate.get.fold(ZIO.unit)(_.await)) *>
        artifact("stream-artifact", "artifact") *>
        complete
    else if text.contains("tck-task-failure") then
      status(TaskStatus.failed(agent("failed")), finalEvent = true)
    else if text.contains("tck-cancel") then
      status(TaskStatus.working(Some(agent("working")))) *>
        ZIO.sleep(500.millis) *>
        complete
    else complete

  private def messageResponder(params: A2ARequest.MessageSend): Task[Option[A2AMessage]] =
    if params.message.text.contains("tck-message-response") then
      ZIO.some(A2AMessage.agentText("message response", params.message.contextId))
    else ZIO.none

  private def messageResponseActs(id: String): Boolean =
    Set("CORE-SEND-003", "STREAM-MSG-001", "DM-FMT-002", "DM-FMT-003").contains(id)

  private def concurrentStreamActs(id: String): Boolean =
    Set("STREAM-MULTI-001", "STREAM-MULTI-002").contains(id)

  private def grpcActs(id: String): Boolean =
    Set("GRPC-STATUS-001", "GRPC-STREAM-001", "GRPC-STREAM-002").contains(id)

  private def pushNotificationActs(id: String): Boolean =
    Set(
      "SEC-PUSH-001",
      "SEC-PUSH-002",
      "PUSH-CFG-001",
      "PUSH-CFG-002",
      "PUSH-CFG-003",
      "PUSH-LIST-001",
      "PUSH-ERR-001",
      "PUSH-IDEM-001",
      "PUSH-DELIV-001",
      "PUSH-DELIV-002",
      "PUSH-DELIV-003",
    ).contains(id)

  private def extendedAgentCardActs(id: String): Boolean =
    Set("CARD-EXT-001", "SEC-EXTCARD-001", "SEC-EXTCARD-002", "SEC-EXTCARD-004").contains(id)

  private def requestAuth(id: String): A2ARequestAuth =
    if authRequiredActs(id) then
      A2ARequestAuth.fromFunction((_, context) => rejectMissingOrInsufficientAuth(context.authorization))
    else A2ARequestAuth.permitAll

  private def agentCardAuth(id: String): A2AAgentCardAuth =
    if id == "SEC-AUTH-006" then A2AAgentCardAuth.requireAuthorizationHeader
    else A2AAgentCardAuth.permitAll

  private def extendedAgentCardAuth(id: String): A2AExtendedAgentCardAuth =
    if extendedAgentCardAuthRequiredActs(id) then
      A2AExtendedAgentCardAuth.fromFunction((_, authorization) => rejectMissingOrInsufficientAuth(authorization))
    else A2AExtendedAgentCardAuth.permitAll

  private def authRequiredActs(id: String): Boolean =
    Set("SEC-AUTH-001", "SEC-AUTH-002", "SEC-AUTH-003", "SEC-AUTH-004").contains(id)

  private def extendedAgentCardAuthRequiredActs(id: String): Boolean =
    Set("SEC-EXTCARD-001", "SEC-EXTCARD-002", "SEC-EXTCARD-004").contains(id)

  private def rejectMissingOrInsufficientAuth(authorization: Option[String]): Task[Unit] =
    authorization.map(_.trim).filter(_.nonEmpty) match
      case Some(value) if !value.contains("insufficient") && !value.contains("{{") => ZIO.unit
      case Some(_) => ZIO.fail(A2AError.unauthenticated("authorization token is not permitted for this ACTS fixture"))
      case None    => ZIO.fail(A2AError.unauthenticated("authorization is required for this ACTS fixture"))

  private def streamJson(events: List[A2AResponse.StreamEvent]): Json =
    Json.Obj("events" -> Json.Arr(events.map(_.toJsonAST.toOption.get)*))

  private def clientAgentCardJson(card: AgentCard): Json =
    val base = card.toJsonAST.toOption.get.asObject.get
    val capabilities = card.capabilities.toJsonAST.toOption.get.asObject.get
      .add("streaming", Json.Bool(card.capabilities.streaming))
      .add("pushNotifications", Json.Bool(card.capabilities.pushNotifications))
      .add("extendedAgentCard", Json.Bool(card.capabilities.extendedAgentCard))
    base.add("capabilities", capabilities)

  private def parseJsonOrString(body: String): Json =
    body.fromJson[Json].getOrElse(Json.Str(body))

  private def sseFrameJson(frame: String): List[Json] =
    frame.linesIterator
      .map(_.trim)
      .collect {
        case line if line.startsWith("data:") =>
          parseJsonOrString(line.stripPrefix("data:").trim)
      }
      .toList

  private def errorResult(error: Throwable): StepResult =
    val a2a = A2AError.fromThrowable(error)
    StepResult(
      Json.Obj(
        "error" -> Json.Obj(
          "code"    -> Json.Num(java.math.BigDecimal.valueOf(a2a.code.toLong)),
          "message" -> Json.Str(a2a.message),
        )
      ),
      Some(a2a),
    )

  private def errorName(code: Int): String =
    code match
      case A2AErrorCode.TaskNotFound                           => "TaskNotFoundError"
      case A2AErrorCode.TaskNotCancelable                      => "TaskNotCancelableError"
      case A2AErrorCode.PushNotificationNotSupported           => "PushNotificationNotSupportedError"
      case A2AErrorCode.UnsupportedOperation                   => "UnsupportedOperationError"
      case A2AErrorCode.ContentTypeNotSupported                => "ContentTypeNotSupportedError"
      case A2AErrorCode.InvalidAgentResponse                   => "InvalidAgentResponseError"
      case A2AErrorCode.AuthenticatedExtendedCardNotConfigured => "AuthenticatedExtendedCardNotConfiguredError"
      case A2AErrorCode.VersionNotSupported                    => "VersionNotSupportedError"
      case A2AErrorCode.Unauthenticated                        => "UnauthenticatedError"
      case A2AErrorCode.InvalidRequest                         => "InvalidRequestError"
      case A2AErrorCode.InvalidParams                          => "InvalidParamsError"
      case _                                                   => s"Error$code"

  private def agentCard: AgentCard =
    A2AServerAgentCard(
      name = "ACTS",
      description = "ACTS streaming fixture",
      capabilities = AgentCapabilities.default.copy(streaming = true),
      skills = List(AgentSkill("acts", "ACTS", "ACTS fixture", tags = List("test"))),
      baseUrl = "https://agent.example.test/a2a",
      tenant = None,
    )

  private def actsAgentCard(id: String, capabilities: AgentCapabilities): AgentCard =
    val card = agentCard.copy(capabilities = capabilities)
    if !grpcActs(id) then card
    else card.copy(supportedInterfaces = card.supportedInterfaces :+ AgentInterface.grpc("https://agent.example.test/a2a.A2AService"))

  private def decode[A: JsonDecoder](json: Json): Task[A] =
    ZIO.fromEither(json.as[A].left.map(new RuntimeException(_)))

  private def pushConfigParams(params: Json): Task[TaskPushNotificationConfig] =
    params.asObject match
      case Some(obj) =>
        val fields = obj.toMap
        val config = fields
          .get("pushNotificationConfig")
          .orElse(fields.get("push_notification_config"))
          .getOrElse(params)
        val withTaskId = config.asObject match
          case Some(configObj) if !configObj.toMap.contains("taskId") && !configObj.toMap.contains("task_id") =>
            fields.get("taskId").orElse(fields.get("task_id")) match
              case Some(taskId) => configObj.add("taskId", taskId)
              case None         => config
          case _ => config
        decode[TaskPushNotificationConfig](withTaskId)
      case None =>
        decode[TaskPushNotificationConfig](params)

  private def withGeneratedMessageId(params: Json, stepId: String): Json =
    params.asObject match
      case Some(obj) =>
        val fields = obj.toMap
        val withMessageId = fields.get("message").flatMap(_.asObject) match
          case Some(messageObj) if !messageObj.toMap.contains("messageId") && !messageObj.toMap.contains("message_id") =>
            obj.add("message", messageObj.add("messageId", Json.Str(s"acts-$stepId-${java.lang.System.nanoTime()}")))
          case _ => obj
        fields.get("params").flatMap(_.asObject) match
          case Some(paramsObj) =>
            withMessageId.add("params", withGeneratedMessageId(paramsObj, stepId))
          case None =>
            withMessageId
      case None => params

  private def matchJson(actual: Json, expected: Json): Either[String, Unit] =
    expected match
      case Json.Obj(fields) if fields.toMap.get("absent").contains(Json.Bool(true)) =>
        Left(s"expected absent, got ${actual.toJson}")
      case Json.Obj(fields) if fields.toMap.contains("type") =>
        val checks = fields.toMap.foldLeft[Either[String, Unit]](Right(())) {
          case (acc, ("type", Json.Str("string"))) =>
            acc.flatMap(_ => actual.asString.map(_ => ()).toRight(s"${actual.toJson} is not a string"))
          case (acc, ("type", Json.Str("array"))) =>
            acc.flatMap(_ => actual.asArray.map(_ => ()).toRight(s"${actual.toJson} is not an array"))
          case (acc, ("type", Json.Str("object"))) =>
            acc.flatMap(_ => actual.asObject.map(_ => ()).toRight(s"${actual.toJson} is not an object"))
          case (acc, ("type", Json.Str("number"))) =>
            acc.flatMap(_ => actual.asNumber.map(_ => ()).toRight(s"${actual.toJson} is not a number"))
          case (acc, ("type", Json.Str("null"))) =>
            acc.flatMap(_ => if actual == Json.Null then Right(()) else Left(s"${actual.toJson} is not null"))
          case (acc, ("type", _)) =>
            acc
          case (acc, ("count", value)) =>
            acc.flatMap(_ => compareCount(actual, value, _ == _))
          case (acc, ("count_gte", value)) =>
            acc.flatMap(_ => compareCount(actual, value, _ >= _))
          case (acc, ("count_lte", value)) =>
            acc.flatMap(_ => compareCount(actual, value, _ <= _))
          case (acc, ("items", Json.Arr(values))) =>
            acc.flatMap(_ => matchArrayItems(actual, values.toList))
          case (acc, ("items", value)) =>
            acc.flatMap(_ => matchArrayItems(actual, List(value)))
          case (acc, (name, value)) =>
            acc.flatMap(_ => matchJson(actual, Json.Obj(name -> value)))
        }
        checks
      case Json.Obj(fields) if fields.toMap.contains("all_of") =>
        fields.toMap.apply("all_of") match
          case Json.Arr(values) =>
            values.toList.foldLeft[Either[String, Unit]](Right(()))((acc, value) => acc.flatMap(_ => matchJson(actual, value)))
          case other => Left(s"all_of must be an array, got ${other.toJson}")
      case Json.Obj(fields) if fields.toMap.contains("one_of") =>
        fields.toMap.apply("one_of") match
          case Json.Arr(values) if values.exists(value => matchJson(actual, value).isRight) => Right(())
          case other => Left(s"${actual.toJson} matched none of ${other.toJson}")
      case Json.Obj(fields) if fields.toMap.contains("any_of") =>
        fields.toMap.apply("any_of") match
          case Json.Arr(values) if values.exists(value => matchJson(actual, value).isRight) => Right(())
          case other => Left(s"${actual.toJson} matched none of ${other.toJson}")
      case Json.Obj(fields) if fields.toMap.contains("contains") =>
        fields.toMap.apply("contains").asString match
          case Some(needle) if actual.asString.exists(_.contains(needle)) => Right(())
          case Some(needle) => Left(s"${actual.toJson} does not contain $needle")
          case None         => Left("contains must be a string")
      case Json.Obj(fields) if fields.toMap.contains("matches") =>
        fields.toMap.apply("matches").asString match
          case Some(pattern) if actual.asString.exists(pattern.r.findFirstIn(_).nonEmpty) => Right(())
          case Some(pattern) => Left(s"${actual.toJson} does not match $pattern")
          case None          => Left("matches must be a string")
      case Json.Obj(fields) if fields.toMap.contains("starts_with") =>
        fields.toMap.apply("starts_with").asString match
          case Some(prefix) if actual.asString.exists(_.startsWith(prefix)) => Right(())
          case Some(prefix) => Left(s"${actual.toJson} does not start with $prefix")
          case None         => Left("starts_with must be a string")
      case Json.Obj(fields) if fields.toMap.get("exists").contains(Json.Bool(true)) =>
        Right(())
      case Json.Obj(fields) =>
        fields.toMap.filterNot { case (name, _) => Set("description", "match", "index").contains(name) }.foldLeft[Either[String, Unit]](Right(())) {
          case (acc, ("count", value)) =>
            acc.flatMap(_ => compareCount(actual, value, _ == _))
          case (acc, ("count_gte", value)) =>
            acc.flatMap(_ => compareCount(actual, value, _ >= _))
          case (acc, ("count_lte", value)) =>
            acc.flatMap(_ => compareCount(actual, value, _ <= _))
          case (acc, ("items", Json.Arr(values))) =>
            acc.flatMap(_ => matchArrayItems(actual, values.toList))
          case (acc, ("items", value)) =>
            acc.flatMap(_ => matchArrayItems(actual, List(value)))
          case (acc, (name, value)) =>
            acc.flatMap { _ =>
              field(actual, name) match
                case Some(actualValue) => matchJson(actualValue, value)
                case None if allowsAbsent(value) => Right(())
                case None => Left(s"${actual.toJson} missing $name")
            }
        }
      case _ =>
        if actual == expected then Right(()) else Left(s"expected ${expected.toJson}, got ${actual.toJson}")

  private def compareCount(actual: Json, expected: Json, predicate: (Int, Int) => Boolean): Either[String, Unit] =
    val actualCount   = actual.asArray.map(_.size).getOrElse(-1)
    val expectedCount = intValue(expected).getOrElse(-1)
    if predicate(actualCount, expectedCount) then Right(())
    else Left(s"array count expected ${expected.toJson}, got $actualCount")

  private def matchArrayItems(actual: Json, expectedItems: List[Json]): Either[String, Unit] =
    actual.asArray.toRight(s"${actual.toJson} is not an array").flatMap { values =>
      val items = values.toList
      expectedItems.foldLeft[Either[String, Unit]](Right(())) { (acc, expected) =>
        acc.flatMap { _ =>
          if items.exists(item => matchJson(item, expected).isRight) then Right(())
          else Left(s"array had no item matching ${expected.toJson}")
        }
      }
    }

  private def allowsAbsent(expected: Json): Boolean =
    expected.asObject.exists { obj =>
      val fields = obj.toMap
      fields.get("absent").contains(Json.Bool(true)) ||
        fields.get("any_of").exists {
          case Json.Arr(values) => values.exists(allowsAbsent)
          case _                => false
        } ||
        fields.get("one_of").exists {
          case Json.Arr(values) => values.exists(allowsAbsent)
          case _                => false
        }
    }

  private def field(json: Json, name: String): Option[Json] =
    json.asObject.flatMap { obj =>
      val fields = obj.toMap
      name match
        case "status" =>
          fields.get("status").orElse(fields.get("statusUpdate").orElse(fields.get("status_update")).flatMap(field(_, "status")))
        case "status_update" =>
          fields.get("statusUpdate").orElse(fields.get("status_update"))
        case "artifact" =>
          fields.get("artifact").orElse(fields.get("artifactUpdate").orElse(fields.get("artifact_update")).flatMap(field(_, "artifact")))
        case "artifact_update" =>
          fields.get("artifactUpdate").orElse(fields.get("artifact_update"))
        case other =>
          fields.get(other)
    }

  private def select(json: Json, path: String): List[Json] =
    path.split("\\.").toList.foldLeft(List(json)) { (values, token) =>
      token match
        case "*" =>
          values.flatMap(_.asArray.toList.flatMap(_.toList))
        case arrayField if arrayField.endsWith("[*]") =>
          val name = arrayField.stripSuffix("[*]")
          values.flatMap(value => field(value, name).toList.flatMap(_.asArray.toList.flatMap(_.toList)))
        case IndexedPathToken(name, rawIndex) =>
          val index = rawIndex.toInt
          values.flatMap(value => field(value, name).toList.flatMap(_.asArray.toList.flatMap(_.lift(index))))
        case _ =>
          values.flatMap(value => field(value, token).toList)
    }

  private val IndexedPathToken = """^(.+)\[(\d+)\]$""".r

  private def intValue(json: Json): Option[Int] =
    json.asNumber.flatMap(number => scala.util.Try(number.value.intValueExact).toOption)

  private def jsonString(json: Json): Option[String] =
    json.asString.orElse(json.asNumber.map(_.value.toPlainString))

  private val Template = """\{\{([^}]+)\}\}""".r

  private def toJson(value: Any, state: ActsState): Json =
    value match
      case null                     => Json.Null
      case value: String            => Json.Str(substitute(value, state))
      case value: java.lang.Boolean => Json.Bool(value.booleanValue)
      case value: java.lang.Integer => Json.Num(java.math.BigDecimal.valueOf(value.longValue))
      case value: java.lang.Long    => Json.Num(java.math.BigDecimal.valueOf(value.longValue))
      case value: java.util.Map[?, ?] =>
        Json.Obj(value.asScala.toSeq.map { case (key, value) => key.toString -> toJson(value, state) }*)
      case value: java.util.List[?] =>
        Json.Arr(value.asScala.toSeq.map(toJson(_, state))*)
      case value: Map[?, ?] =>
        Json.Obj(value.toSeq.map { case (key, value) => key.toString -> toJson(value, state) }*)
      case value: Iterable[?] =>
        Json.Arr(value.toSeq.map(toJson(_, state))*)
      case other =>
        Json.Str(substitute(other.toString, state))

  private def substitute(value: String, state: ActsState): String =
    Template.replaceAllIn(
      value,
      m => Regex.quoteReplacement(state.captures.getOrElse(m.group(1), value)),
    )

  private def loadActsTests: List[ActsTest] =
    List(
      "auth-security.acts.yaml",
      "core-operations.acts.yaml",
      "client-parsing.acts.yaml",
      "data-types.acts.yaml",
      "discovery.acts.yaml",
      "history.acts.yaml",
      "multi-turn.acts.yaml",
      "error-handling.acts.yaml",
      "polling.acts.yaml",
      "push-notifications.acts.yaml",
      "streaming.acts.yaml",
      "transport-bindings.acts.yaml",
      "version-negotiation.acts.yaml",
      "wire-format.acts.yaml",
    ).flatMap { file =>
      val doc = parseYaml(readActsFile(file))
      val variables = stringMapField(doc, "variables")
      listField(doc, "suites").flatMap {
        case suite: Map[String, Any] @unchecked =>
          listField(suite, "tests").collect { case test: Map[String, Any] @unchecked =>
            ActsTest(
              test("id").toString,
              listField(test, "steps").collect { case step: Map[String, Any] @unchecked => step },
              listField(test, "transport").map(_.toString).toSet,
              variables,
            )
          }
        case _ => Nil
      }
    }

  private def parseYaml(text: String): Map[String, Any] =
    normalizeYaml(new Yaml().load[Any](text)).asInstanceOf[Map[String, Any]]

  private def normalizeYaml(value: Any): Any =
    value match
      case map: java.util.Map[?, ?] => map.asScala.toMap.map { case (key, value) => key.toString -> normalizeYaml(value) }
      case list: java.util.List[?]  => list.asScala.toList.map(normalizeYaml)
      case other                    => other

  private def listField(map: Map[String, Any], name: String): List[Any] =
    map.get(name).collect { case values: List[?] => values.asInstanceOf[List[Any]] }.getOrElse(Nil)

  private def stringMapField(map: Map[String, Any], name: String): Map[String, String] =
    map.get(name)
      .collect { case values: Map[?, ?] => values }
      .map(_.map { case (key, value) => key.toString -> value.toString }.asInstanceOf[Map[String, String]])
      .getOrElse(Map.empty)

  private def readActsFile(name: String): String =
    // Vendored in-repo (test/resources/acts/<name>) on the test classpath.
    Option(getClass.getResourceAsStream(s"/acts/$name"))
      .map { stream =>
        try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
        finally stream.close()
      }
      .getOrElse(throw new RuntimeException(s"vendored ACTS fixture /acts/$name missing from the test classpath"))
end A2AActsStreamingSpec
