package com.tjclp.claude.agent.config

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import zio.json._

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

object McpServerConfig:
  given JsonDecoder[McpServerConfig] = DeriveJsonDecoder.gen[McpServerConfig]
  given JsonEncoder[McpServerConfig] = DeriveJsonEncoder.gen[McpServerConfig]

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
