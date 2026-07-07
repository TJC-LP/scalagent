package com.tjclp.scalagent.a2a

import zio.*

object A2AServerDefaults:
  val JsHost: String  = "localhost"
  val JvmHost: String = "0.0.0.0"
  val Port: Int       = 3000

  val EventReplayLimit: Int             = 1000
  val EventStoreAppendTimeout: Duration = 2.seconds
  val EventStoreLoadTimeout: Duration   = 5.seconds
  // A2A messages carry file uploads as base64 inside the JSON-RPC body
  // (~33% inflation), so this must comfortably exceed the largest raw
  // upload a client allows. 64 MiB covers rtalk's 32 MB raw cap encoded.
  val MaxRequestBodyBytes: Int                 = 64 * 1024 * 1024
  val PushNotificationPostTimeout: Duration    = 20.seconds
  val PushNotificationMaxRetries: Int          = 2
  val PushNotificationRetryBaseDelay: Duration = 250.millis
  val PushUrlPolicy: PushNotificationUrlPolicy =
    PushNotificationUrlPolicy.externalOnly

  def url(host: String, port: Int): String =
    url(host, port, "http")

  def url(
    host: String,
    port: Int,
    scheme: String,
  ): String =
    s"$scheme://$host:$port"

  def publicUrl(
    host: String,
    configuredPort: Int,
    advertisedUrl: Option[String],
    boundPort: Option[Int] = None,
    scheme: String = "http",
  ): String =
    advertisedUrl.getOrElse(url(host, boundPort.getOrElse(configuredPort), scheme))
end A2AServerDefaults
