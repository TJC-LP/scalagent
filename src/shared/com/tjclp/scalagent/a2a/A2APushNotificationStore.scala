package com.tjclp.scalagent.a2a

import scala.collection.mutable
import zio.*

/** Persistence interface for v1 task push notification configs. */
trait A2APushNotificationStore:
  def save(
    taskId: TaskId,
    tenant: Option[String],
    config: TaskPushNotificationConfig,
  ): UIO[TaskPushNotificationConfig]

  def load(taskId: TaskId, tenant: Option[String]): UIO[List[TaskPushNotificationConfig]]

  def delete(
    taskId: TaskId,
    tenant: Option[String],
    configId: String,
  ): UIO[Unit]
end A2APushNotificationStore

object A2APushNotificationStore:
  def inMemory: A2APushNotificationStore =
    InMemoryA2APushNotificationStore()
end A2APushNotificationStore

private final class InMemoryA2APushNotificationStore extends A2APushNotificationStore:
  private val configs = mutable.Map.empty[(String, String), List[TaskPushNotificationConfig]]
  private val lock    = new AnyRef

  private def key(taskId: TaskId, tenant: Option[String]): (String, String) =
    (tenant.getOrElse(""), taskId.value)

  override def save(
    taskId: TaskId,
    tenant: Option[String],
    config: TaskPushNotificationConfig,
  ): UIO[TaskPushNotificationConfig] =
    ZIO.succeed(lock.synchronized {
      val normalized = config.copy(
        tenant = tenant.orElse(config.tenant),
        taskId = Some(taskId),
        id = config.id.filter(_.nonEmpty).orElse(Some(TaskId.generate.value)),
      )
      val k        = key(taskId, tenant)
      val existing = configs.getOrElse(k, Nil).filterNot(_.id == normalized.id)
      configs.update(k, existing :+ normalized)
      normalized
    })

  override def load(taskId: TaskId, tenant: Option[String]): UIO[List[TaskPushNotificationConfig]] =
    ZIO.succeed(lock.synchronized(configs.getOrElse(key(taskId, tenant), Nil)))

  override def delete(
    taskId: TaskId,
    tenant: Option[String],
    configId: String,
  ): UIO[Unit] =
    ZIO.succeed(lock.synchronized {
      val k = key(taskId, tenant)
      configs.update(k, configs.getOrElse(k, Nil).filterNot(_.id.contains(configId)))
    })
end InMemoryA2APushNotificationStore
