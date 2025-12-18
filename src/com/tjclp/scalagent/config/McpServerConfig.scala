package com.tjclp.scalagent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import zio.json.*

/** MCP (Model Context Protocol) server configuration.
  *
  * Supports stdio, SSE, and HTTP transport types.
  */
enum McpServerConfig:
  /** Stdio-based MCP server (subprocess) */
  case Stdio(
      command: String,
      args: List[String] = Nil,
      env: Map[String, String] = Map.empty
  )

  /** Server-Sent Events transport */
  case SSE(
      url: String,
      headers: Map[String, String] = Map.empty
  )

  /** HTTP transport */
  case HTTP(
      url: String,
      headers: Map[String, String] = Map.empty
  )

  /** In-process SDK MCP server (created via McpServer.create) */
  case Sdk(
      name: String,
      version: String = "1.0.0",
      rawServerConfig: js.Object
  )

  /** Convert to raw JavaScript object for SDK */
  def toRaw: js.Object = this match
    case Stdio(cmd, args, env) =>
      val obj = js.Dynamic.literal(command = cmd)
      if args.nonEmpty then obj.args = args.toJSArray
      if env.nonEmpty then obj.env = js.Dictionary(env.toSeq*)
      obj.asInstanceOf[js.Object]

    case SSE(url, headers) =>
      val obj = js.Dynamic.literal(`type` = "sse", url = url)
      if headers.nonEmpty then obj.headers = js.Dictionary(headers.toSeq*)
      obj.asInstanceOf[js.Object]

    case HTTP(url, headers) =>
      val obj = js.Dynamic.literal(`type` = "http", url = url)
      if headers.nonEmpty then obj.headers = js.Dictionary(headers.toSeq*)
      obj.asInstanceOf[js.Object]

    case Sdk(_, _, rawConfig) =>
      // SDK server config is already in raw format
      rawConfig

object McpServerConfig:
  // Note: Sdk variant cannot be serialized to/from JSON because it contains js.Object
  // JSON codecs are provided only for Stdio, SSE, and HTTP variants

  given stdioDecoder: JsonDecoder[McpServerConfig.Stdio] = DeriveJsonDecoder.gen[McpServerConfig.Stdio]
  given stdioEncoder: JsonEncoder[McpServerConfig.Stdio] = DeriveJsonEncoder.gen[McpServerConfig.Stdio]
  given sseDecoder: JsonDecoder[McpServerConfig.SSE] = DeriveJsonDecoder.gen[McpServerConfig.SSE]
  given sseEncoder: JsonEncoder[McpServerConfig.SSE] = DeriveJsonEncoder.gen[McpServerConfig.SSE]
  given httpDecoder: JsonDecoder[McpServerConfig.HTTP] = DeriveJsonDecoder.gen[McpServerConfig.HTTP]
  given httpEncoder: JsonEncoder[McpServerConfig.HTTP] = DeriveJsonEncoder.gen[McpServerConfig.HTTP]

  /** Create a stdio MCP server config */
  def stdio(command: String, args: String*): McpServerConfig =
    Stdio(command, args.toList)

  /** Create a stdio MCP server config with environment */
  def stdioWithEnv(
      command: String,
      args: List[String],
      env: Map[String, String]
  ): McpServerConfig =
    Stdio(command, args, env)

  /** Create an SSE MCP server config */
  def sse(url: String): McpServerConfig = SSE(url)

  /** Create an SSE MCP server config with headers */
  def sseWithHeaders(url: String, headers: Map[String, String]): McpServerConfig =
    SSE(url, headers)

  /** Create an HTTP MCP server config */
  def http(url: String): McpServerConfig = HTTP(url)

  /** Create an HTTP MCP server config with headers */
  def httpWithHeaders(url: String, headers: Map[String, String]): McpServerConfig =
    HTTP(url, headers)
