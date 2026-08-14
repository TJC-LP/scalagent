package com.tjclp.scalagent.a2a

import munit.FunSuite

class A2APathRoutingSpec extends FunSuite:
  import A2APathRouting.RestRoute

  test("REST route classifier preserves tenant prefix and path semantics"):
    assertEquals(A2APathRouting.route("POST", "/tenant-a/message:send"), A2APathRouting.RoutedRest(Some("tenant-a"), Some(RestRoute.MessageSend)))
    assertEquals(A2APathRouting.route("POST", "/message:stream"), A2APathRouting.RoutedRest(None, Some(RestRoute.MessageStream)))
    assertEquals(
      A2APathRouting.route("GET", "/tasks/task-1/pushNotificationConfigs/push-1"),
      A2APathRouting.RoutedRest(None, Some(RestRoute.PushConfigGet("task-1", "push-1"))),
    )
    assertEquals(A2APathRouting.route("DELETE", "/tasks/task-1:cancel").route, None)
    assertEquals(A2APathRouting.route("GET", "/tasks//events").route, Some(RestRoute.MissingTaskId))

  test("query parser shares aliases and scalar validation"):
    val query = A2APathRouting.query(
      Map(
        "PAGE_SIZE"         -> "25",
        "include_artifacts" -> "true",
        "status"            -> "TASK_STATE_COMPLETED",
      ).get
    )

    assertEquals(query.string("pageSize", "page_size"), Some("25"))
    assertEquals(query.int("pageSize", "page_size"), Right(Some(25)))
    assertEquals(query.bool("includeArtifacts", "include_artifacts"), Right(Some(true)))
    assertEquals(query.taskStatus("status"), Right(Some(TaskState.Completed)))
    assert(query.int("missing").contains(None))
    assert(query.int("status").left.exists(_.message.contains("status must be a valid integer")))

  test("tenant resolver rejects conflicting REST tenant sources"):
    assertEquals(A2APathRouting.resolveTenant(Some("tenant-a"), Some("tenant-a"), None), Right(Some("tenant-a")))
    assertEquals(A2APathRouting.resolveTenant(None, None, Some("tenant-body")), Right(Some("tenant-body")))
    assert(A2APathRouting.resolveTenant(Some("tenant-a"), Some("tenant-b"), None).left.exists(_.message.contains("Conflicting tenant values")))

  test("REST path parameters are percent-decoded in shared builders"):
    val query = A2APathRouting.query(Map.empty[String, String].get)

    assertEquals(
      A2APathRouting.resolveTenant(Some("tenant%20a"), None, None),
      Right(Some("tenant a")),
    )
    assertEquals(
      A2APathRouting.tasksGet("task%201", query, Some("tenant a")).map(_.id),
      Right(TaskId("task 1")),
    )
    assertEquals(
      A2APathRouting.tasksCancel("task%2Fdeep:cancel", Some(A2ARequest.TasksCancelRestBody(id = Some(TaskId("task/deep")))), None)
        .map(_.id),
      Right(TaskId("task/deep")),
    )
    assertEquals(
      A2APathRouting.pushConfigGet("task%201", "push%2Fencoded", None).map(value => value.taskId -> value.id),
      Right(TaskId("task 1") -> "push/encoded"),
    )
    assertEquals(A2APathRouting.taskId("task+plus"), Right(TaskId("task+plus")))
    assert(A2APathRouting.taskId("bad%2").left.exists(_.message.contains("Invalid percent-encoding")))
    assert(A2APathRouting.taskId("bad%C3%28").left.exists(_.message.contains("Invalid UTF-8 percent-encoding")))
    assert(A2APathRouting.taskId("bad%C0%AF").left.exists(_.message.contains("Invalid UTF-8 percent-encoding")))
    assert(A2APathRouting.taskId("bad%ED%A0%80").left.exists(_.message.contains("Invalid UTF-8 percent-encoding")))
    assertEquals(A2APathRouting.taskId("emoji%F0%9F%9A%80").map(_.value), Right("emoji\uD83D\uDE80"))

  test("REST request builders translate query and path values once for both platforms"):
    val query = A2APathRouting.query(
      Map(
        "context_id"             -> "ctx-1",
        "status"                 -> "TASK_STATE_COMPLETED",
        "page_size"              -> "25",
        "page_token"             -> "next",
        "history_length"         -> "3",
        "status_timestamp_after" -> "2026-01-01T00:00:00Z",
        "include_artifacts"      -> "false",
        "tenant"                 -> "tenant-a",
        "a2a-version"            -> "1.0",
      ).get
    )

    assertEquals(A2APathRouting.queryTenant(query), Some("tenant-a"))
    assertEquals(A2APathRouting.requestedVersion(query), Some("1.0"))
    assertEquals(
      A2APathRouting.tasksList(query, Some("tenant-a")),
      Right(
        A2ARequest.TasksList(
          contextId = Some(ContextId("ctx-1")),
          status = Some(TaskState.Completed),
          pageSize = Some(25),
          pageToken = Some("next"),
          historyLength = Some(3),
          statusTimestampAfter = Some("2026-01-01T00:00:00Z"),
          includeArtifacts = Some(false),
          tenant = Some("tenant-a"),
        )
      ),
    )
    assertEquals(
      A2APathRouting.tasksGet("task-1", query, Some("tenant-a")),
      Right(
        A2ARequest.TasksGet(TaskId("task-1"), Some(3), includeArtifacts = Some(false), tenant = Some("tenant-a"))
      ),
    )
    assertEquals(
      A2APathRouting.pushConfigList("task-1", query, Some("tenant-a")),
      Right(A2ARequest.PushNotificationConfigList(TaskId("task-1"), Some(25), Some("next"), Some("tenant-a"))),
    )

  test("REST request builders centralize path/body mismatch checks"):
    val cancelBody = Some(A2ARequest.TasksCancelRestBody(id = Some(TaskId("other"))))
    val pushConfig = TaskPushNotificationConfig("https://example.test/hook", taskId = Some(TaskId("other")))

    assert(A2APathRouting.tasksCancel("task-1:cancel", cancelBody, None).left.exists(_.message.contains("does not match path id")))
    assert(A2APathRouting.pushConfigCreate("task-1", pushConfig, None).left.exists(_.message.contains("does not match path taskId")))
    assertEquals(
      A2APathRouting.tasksResubscribe("task-1:subscribe", Some("tenant-a")),
      Right(A2ARequest.TasksResubscribe(TaskId("task-1"), Some("tenant-a"))),
    )
    assertEquals(
      A2APathRouting.pushConfigGet("task-1", "push-1", Some("tenant-a")),
      Right(A2ARequest.PushNotificationConfigGet(TaskId("task-1"), "push-1", Some("tenant-a"))),
    )
    assertEquals(
      A2APathRouting.pushConfigDelete("task-1", "push-1", Some("tenant-a")),
      Right(A2ARequest.PushNotificationConfigDelete(TaskId("task-1"), "push-1", Some("tenant-a"))),
    )

  test("path identifiers reject empty task and push config ids"):
    assertEquals(A2APathRouting.taskId("task-1"), Right(TaskId("task-1")))
    assert(A2APathRouting.taskId("").left.exists(_.message.contains("Missing task ID")))
    assertEquals(A2APathRouting.pushConfigId("push-1"), Right("push-1"))
    assert(A2APathRouting.pushConfigId("").left.exists(_.message.contains("Missing push notification config ID")))
