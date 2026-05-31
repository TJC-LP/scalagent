package com.tjclp.scalagent.a2a

import zio.*

private[a2a] trait A2AServerCoreConfig:
  def capabilities: AgentCapabilities
  def extendedAgentCard: Option[AgentCard]
  def pushNotificationStore: Option[A2APushNotificationStore]
  def taskStore: Option[A2ATaskStore]
  def eventStore: Option[A2AEventStore]
  def replayProvider: Option[A2AReplayProvider]
  def eventReplayLimit: Int
  def eventStoreAppendTimeout: Duration
  def eventStoreLoadTimeout: Duration
  def pushNotificationUrlPolicy: PushNotificationUrlPolicy

private[a2a] trait A2AServerLiveConfig extends A2AServerCoreConfig:
  def name: String
  def description: String
  def host: String
  def port: Int
  def taskTimeout: Option[Duration]
  def skills: List[AgentSkill]
  def executionOverride: Option[(A2AMessage, TaskId, ContextId, A2AEventPublisher) => Task[Unit]]
  def tenant: Option[String]

  def url: String =
    A2AServerDefaults.url(host, port)

  def toAgentCard: AgentCard =
    toAgentCardAt(url)

  def toAgentCardAt(baseUrl: String): AgentCard =
    A2AServerAgentCard(
      name,
      description,
      baseUrl,
      capabilities,
      skills,
      tenant,
    )

  def runExecutionOverride(
    prepared: A2ARequestHandler.PreparedRun,
    publisher: A2AEventPublisher,
  ): Option[Task[Unit]] =
    executionOverride.map { overrideRun =>
      A2ATaskTimeout(
        prepared.task.id,
        taskTimeout,
        overrideRun(prepared.message, prepared.task.id, prepared.task.contextId, publisher),
      )
    }
end A2AServerLiveConfig

private[a2a] final case class A2AServerCore(
  taskStore: A2ATaskStore,
  pushStore: A2APushNotificationStore,
  requestHandler: A2ARequestHandler)

private[a2a] object A2AServerCore:
  def make(
    config: A2AServerCoreConfig,
    runtime: Runtime[Any],
    runtimeRegistry: A2ARuntimeRegistry,
    pushPoster: A2APushNotificationPoster,
    agentCardProvider: () => AgentCard,
    executeRun: A2ARequestHandler.ExecuteRun,
  ): A2AServerCore =
    val taskStore     = config.taskStore.getOrElse(A2ATaskStore.inMemory)
    val pushStore     = config.pushNotificationStore.getOrElse(A2APushNotificationStore.inMemory)
    val pushSender    = A2APushNotificationSender.live(pushStore, config.pushNotificationUrlPolicy, pushPoster)
    val requestConfig = A2ARequestHandler.Config(
      capabilities = config.capabilities,
      eventStore = config.eventStore,
      replayProvider = config.replayProvider,
      eventReplayLimit = config.eventReplayLimit,
      eventStoreAppendTimeout = config.eventStoreAppendTimeout,
      eventStoreLoadTimeout = config.eventStoreLoadTimeout,
      pushNotificationUrlPolicy = config.pushNotificationUrlPolicy,
    )
    val requestHandler = A2ARequestHandler(
      requestConfig,
      runtime,
      taskStore,
      pushStore,
      runtimeRegistry,
      pushSender,
      agentCardProvider,
      () => config.extendedAgentCard,
      executeRun,
    )
    A2AServerCore(taskStore, pushStore, requestHandler)
  end make
end A2AServerCore
