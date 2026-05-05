# scalagent A2A v1 native implementation plan

## Summary

Ship A2A v1.0 as scalagent's default A2A surface in one wide branch.

Working branch: `a2a-v1-native-experimental`.

The branch should stop relying on `@a2a-js/sdk` for v1 server behavior and
instead own the v1 wire model, server request handling, JSON-RPC/SSE transport,
REST transport, push notification lifecycle, and native JSON-RPC client in Scala.

Source references:

- `/Users/rcaputo3/git/A2A/specification/a2a.proto` is the source of truth.
- `/Users/rcaputo3/git/A2A/docs/specification.md` is supporting documentation.
- `/Users/rcaputo3/git/a2a-js` at `origin/epic/1.0_breaking_changes` is a
  behavioral reference, not a dependency target.

Where the docs and proto disagree, follow `specification/a2a.proto`.

## Current state

The current scalagent A2A package is built around the A2A 0.3 SDK:

- `build.mill` depends on `@a2a-js/sdk@^0.3.13`.
- `A2AServer` delegates request handling and transport routing through JS SDK
  facades.
- `A2AClient` wraps the JS SDK client.
- package-level exports expose the 0.3 model directly as `A2AClient`,
  `A2AServer`, `AgentCard`, `A2AMessage`, `A2ATask`, `TaskState`, etc.
- recent local work added better live progress and SSE behavior for the 0.3
  path, but that work should be folded into the native v1 implementation rather
  than kept as an SDK workaround.

The v1 shape is materially different from 0.3:

- JSON-RPC method names are PascalCase, e.g. `SendMessage`,
  `SendStreamingMessage`, `GetTask`, `ListTasks`, `CancelTask`,
  `SubscribeToTask`, `CreateTaskPushNotificationConfig`.
- `AgentCard` uses `supportedInterfaces` instead of top-level `url`,
  `preferredTransport`, and `additionalInterfaces`.
- `TaskState` and `Role` use proto enum values such as
  `TASK_STATE_WORKING` and `ROLE_USER`.
- `SendMessageConfiguration` uses `returnImmediately` and
  `taskPushNotificationConfig`.
- push notification config is flattened as `TaskPushNotificationConfig` with
  `tenant`, `id`, `taskId`, `url`, `token`, and `authentication`.
- streaming and push callbacks carry `StreamResponse`.

The a2a-js v1 branch is useful reference code, but its event queue still stops
on a `message` event. The scalagent native handler must not inherit that
behavior for task lifecycle streams.

## Public API

Make A2A v1 the default public API:

- `A2AClient` is a native v1 JSON-RPC/SSE client.
- `A2AServer` and `A2AServerApp` run the native v1 server.
- `AgentCard`, `AgentCapabilities`, `AgentInterface`, `AgentSkill`,
  `A2AMessage`, `A2ATask`, `TaskStatus`, `TaskState`, `Part`, `Artifact`,
  `MessageSendConfiguration`, `TaskPushNotificationConfig`, and
  `AuthenticationInfo` are v1 models.
- `A2ARequest`, `A2AResponse`, and `JsonRpc` expose v1 request/response
  shapes and method names.

Keep compatibility for the current implementation under explicit legacy names:

- `A2AClientV03`
- `A2AServerV03`
- `A2AServerAppV03`
- v0.3 model aliases where needed for downstream migration

Do not make the package-level default exports point at v0.3 after this branch.
Callers importing `com.tjclp.scalagent.*` should get v1 A2A by default.

## Wire model and codecs

Implement native Scala v1 models and zio-json codecs from
`specification/a2a.proto`.

Required model groups:

- core identifiers and JSON-RPC envelope
- `Task`, `TaskStatus`, `TaskState`
- `Message`, `Role`, `Part`, `Artifact`
- `TaskStatusUpdateEvent`, `TaskArtifactUpdateEvent`
- `SendMessageRequest`, `SendMessageConfiguration`, `SendMessageResponse`
- `StreamResponse`
- `GetTaskRequest`, `ListTasksRequest`, `ListTasksResponse`,
  `CancelTaskRequest`, `SubscribeToTaskRequest`
- `TaskPushNotificationConfig`, push get/list/delete request and response
  models, `AuthenticationInfo`
- `AgentCard`, `AgentInterface`, `AgentProvider`, `AgentCapabilities`,
  `AgentExtension`, `AgentSkill`, `AgentCardSignature`
- OpenAPI-style security requirement and security scheme models

Encoding rules:

- Use ProtoJSON-style camelCase output for JSON.
- Accept snake_case aliases where practical for interop and REST input.
- Represent `Part` oneof content as v1 fields: `text`, `raw`, `url`, or
  `data`, plus `metadata`, `filename`, and `mediaType`.
- Represent `SendMessageResponse` as an object with either `task` or
  `message`.
- Represent `StreamResponse` as an object with one of `task`, `message`,
  `statusUpdate`, or `artifactUpdate`.
- Timestamps are ISO 8601 UTC strings.

## Native server behavior

Build a native v1 request handler in Scala.js. It should not delegate v1
behavior to `@a2a-js/sdk`.

Core components:

- `TaskStoreV1`: load, save, and list tasks with tenant scoping.
- `ExecutionEventBusV1`: per-task event publishing and live subscription.
- `ExecutionEventQueueV1`: buffers events for stream consumers without
  closing merely because a nonterminal message appears.
- `ResultManagerV1`: applies task, status update, artifact update, and
  message events to the task store.
- `PushNotificationStoreV1`: per-task push config persistence with in-memory
  default.
- `PushNotificationSenderV1`: POSTs `StreamResponse` payloads to configured
  URLs.
- `A2ARequestHandlerV1`: implements v1 service operations.

Required service operations:

- `SendMessage`
- `SendStreamingMessage`
- `GetTask`
- `ListTasks`
- `CancelTask`
- `SubscribeToTask`
- `CreateTaskPushNotificationConfig`
- `GetTaskPushNotificationConfig`
- `ListTaskPushNotificationConfigs`
- `DeleteTaskPushNotificationConfig`
- `GetExtendedAgentCard`

Preserve existing scalagent server features:

- ClaudeAgent execution through `ClaudeAgent.queryComplete`
- `InvocationContext` and workspace staging
- artifact publication
- session logging
- per-task active run tracking and cancellation
- live progress status updates and structured tool-call visibility from the
  recent observability work

Execution semantics:

- `returnImmediately = false` is the default. `SendMessage` waits until the
  task reaches a terminal or interrupted state before returning.
- `returnImmediately = true` returns the first task or message result while
  execution continues.
- `SendStreamingMessage` starts execution and yields `StreamResponse` events as
  they are produced.
- task lifecycle streams begin with a task snapshot, followed by status and
  artifact updates, and close on terminal or interrupted task state.
- message-only streams may yield a single message and close.
- intermediate messages inside a task lifecycle stream must not close the
  queue unless the lifecycle is explicitly complete.
- artifacts are applied before the terminal status update so polling clients see
  them on the terminal task snapshot.

Validation and errors:

- missing or malformed request bodies return invalid params/request errors.
- unsupported streaming returns `UnsupportedOperationError`.
- unsupported push notification operations return
  `PushNotificationNotSupportedError`.
- unknown tasks return `TaskNotFoundError`.
- terminal task continuation or subscription returns the v1-specified error.
- version mismatch returns `VersionNotSupportedError`.
- required extension mismatch returns `ExtensionSupportRequiredError`.

## Transports

Run one Bun server that exposes the agent card, JSON-RPC, and REST.

Agent card:

- `GET /.well-known/agent-card.json`
- v1 `AgentCard` with `supportedInterfaces`
- default interface order: `JSONRPC` first, `HTTP+JSON` second
- both interfaces use `protocolVersion = "1.0"`
- capability flags come from `A2AServer.Config`

JSON-RPC:

- endpoint stays compatible with the current server default, unless a config
  override is added
- request content type: `application/json`
- streaming response content type: `text/event-stream`
- method names are v1 PascalCase
- streaming SSE events contain JSON-RPC response envelopes:
  `{"jsonrpc":"2.0","id":...,"result":{StreamResponse}}`

REST:

- request and response content type: `application/a2a+json` where practical
- implement:
  - `POST /message:send`
  - `POST /message:stream`
  - `GET /tasks/{id}`
  - `GET /tasks`
  - `POST /tasks/{id}:cancel`
  - `POST /tasks/{id}:subscribe`
  - `POST /tasks/{id}/pushNotificationConfigs`
  - `GET /tasks/{id}/pushNotificationConfigs`
  - `GET /tasks/{id}/pushNotificationConfigs/{configId}`
  - `DELETE /tasks/{id}/pushNotificationConfigs/{configId}`
  - `GET /extendedAgentCard`
- also support tenant-prefixed variants, e.g. `/{tenant}/message:send`.
- REST streaming SSE events contain raw `StreamResponse`, not JSON-RPC
  wrappers.
- REST errors use google.rpc-style JSON bodies with `error.code`,
  `error.status`, `error.message`, and `error.details`.

Service parameters:

- clients send `A2A-Version: 1.0`.
- parse extension service parameters from HTTP headers.
- validate requested version against the selected agent interface.

## Push notifications

Push notifications are in scope and must be v1 conformant.

Config behavior:

- inline config is supplied at
  `SendMessageRequest.configuration.taskPushNotificationConfig`.
- out-of-band lifecycle is managed by create/get/list/delete operations.
- configs are scoped by tenant and task id.
- the default store is in-memory, with a pluggable interface for persistence.

Delivery behavior:

- push notification callbacks POST a `StreamResponse` JSON body.
- content type is `application/a2a+json`.
- if `authentication.scheme` and `authentication.credentials` are set, send
  `Authorization: <scheme> <credentials>`.
- otherwise, if `token` is set, send the legacy token header used by the
  a2a-js reference implementation.
- delivery is fire-and-forget relative to client response/stream progress.
- send notifications sequentially per task to preserve order.
- delivery failures are logged but do not fail the original client request.

## Native client

Implement a native v1 `A2AClient` for JSON-RPC/SSE.

Client creation:

- `A2AClient.discover(baseUrl, headers)` fetches
  `/.well-known/agent-card.json`.
- `A2AClient.fromCard(card, headers)` selects a `JSONRPC` interface.
- if a selected `AgentInterface` declares a tenant, apply it to requests.
- fail clearly when no JSON-RPC interface exists. REST client support is out of
  scope for this branch.

Client operations:

- `agentCard` / `getAgentCard`
- `send`
- `submit`
- `sendAndPoll`
- `stream`
- `getTask`
- `listTasks`
- `cancelTask`
- `resubscribe`
- `createTaskPushNotificationConfig`
- `getTaskPushNotificationConfig`
- `listTaskPushNotificationConfigs`
- `deleteTaskPushNotificationConfig`

Client behavior:

- send `A2A-Version: 1.0` on every request.
- use v1 method names and v1 request/response envelopes.
- parse JSON-RPC SSE events and enforce response id matching.
- map JSON-RPC A2A errors back to typed Scala errors where existing error
  types support it.
- keep convenience extension methods such as `sendText`, `submitText`,
  `sendAndPollText`, `streamText`, and `askText`, updated for v1 models.

## Implementation sequence

1. Move current v0.3 server/client/model code behind explicit legacy names.
   Keep tests compiling before adding v1 defaults.
2. Add v1 model files and codecs, plus focused golden codec tests.
3. Add native stores, event bus, result manager, and request handler.
4. Adapt ClaudeAgent execution into the v1 event model, including live progress,
   structured tool-call data, artifacts, cancellation, and session logging.
5. Implement JSON-RPC transport and Bun routing for v1.
6. Implement REST transport and route matching.
7. Implement push notification store and sender.
8. Implement native v1 JSON-RPC client and discovery.
9. Update package-level exports, examples, and README/API docs.
10. Remove `@a2a-js/sdk` from the v1 path. Keep it only if the legacy v0.3
    compatibility layer still requires it.

## Test plan

Codec tests:

- agent card with `supportedInterfaces`
- security schemes and requirements
- text/raw/url/data parts
- messages with context, task, extensions, and references
- task status and task state enum values
- artifacts and artifact update events
- send message request/config/response
- stream response variants
- task list request/response
- push config request/response

Request handler tests:

- blocking `SendMessage` returns terminal task/message.
- nonblocking `SendMessage` returns first result and leaves execution running.
- `SendStreamingMessage` yields task snapshot, progress, artifacts, terminal
  status, and then closes.
- intermediate task messages do not close task lifecycle streams.
- `GetTask` applies `historyLength`.
- `ListTasks` filters by context, state, timestamp, page size, and page token.
- `CancelTask` interrupts an active run and persists canceled status.
- `SubscribeToTask` starts with current task snapshot and rejects terminal tasks.
- push create/get/list/delete works and respects capability flags.
- tenant scoping isolates tasks and push configs.
- required extension and version checks fail correctly.

Transport tests:

- JSON-RPC method routing uses v1 PascalCase names.
- JSON-RPC streaming SSE wraps each event in a JSON-RPC response object.
- REST routes match both normal and tenant-prefixed paths.
- REST streaming emits raw `StreamResponse` SSE data.
- REST errors use google.rpc-style error bodies.
- agent card advertises JSON-RPC and REST v1 interfaces.

Client tests:

- discovery reads the well-known agent card.
- JSON-RPC interface selection honors agent card order and tenant.
- every request includes `A2A-Version: 1.0`.
- send, stream, get, list, cancel, subscribe, and push config operations use v1
  wire shapes.
- JSON-RPC error responses map to client failures.
- SSE response id mismatch fails.

Interop and regression:

- keep existing A2A tests passing after renaming legacy code.
- add an in-process v1 server/client smoke test.
- run against the local a2a-js v1 branch/TCK where practical.
- run:
  - `./mill --no-server agent.compile`
  - `./mill --no-server agent.test`
  - `git diff --check`

## Acceptance criteria

The branch is shippable when:

- package-level A2A exports are v1 by default.
- a native scalagent v1 server handles JSON-RPC, SSE, REST, push configs, and
  push callbacks without depending on a2a-js request handling.
- a native scalagent v1 client can discover and talk to the server over
  JSON-RPC/SSE.
- v0.3 compatibility is still available under explicit legacy names.
- live progress and structured tool-call visibility work in v1 streams.
- tests cover the protocol, transport, client, and push behavior above.

## Out of scope

- Native REST client support.
- gRPC server or client support.
- durable production push config storage beyond the pluggable store interface.
- broad refactors unrelated to A2A v1.
