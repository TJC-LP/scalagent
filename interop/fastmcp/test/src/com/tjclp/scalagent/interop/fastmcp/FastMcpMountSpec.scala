package com.tjclp.scalagent.interop.fastmcp

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{ToolDefinition, ToolInputSchema}
import com.tjclp.fastmcp.server.McpServer
import com.tjclp.fastmcp.server.McpServer.given
import com.tjclp.fastmcp.server.TransportRunner.given
import com.tjclp.fastmcp.server.manager.ToolRegistrationOptions
import com.tjclp.scalagent.tools.{ToolContent, ToolResult}

import munit.FunSuite
import sttp.tapir.generic.auto.*
import zio.*
import zio.json.*
import zio.json.ast.Json

class FastMcpMountSpec extends FunSuite:

  import FastMcpMountSpec.*

  test("mounts fast-mcp typed contracts as scalagent tool definitions") {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .runToFuture(
          for
            surface <- FastMcpMount.toolSurfaceFromApp("demo", DemoServer)
            result <- surface.tools.head
              .asInstanceOf[com.tjclp.scalagent.tools.ToolDef[Json]]
              .handler(Json.Obj("a" -> Json.Num(2), "b" -> Json.Num(3)))
          yield
            assertEquals(surface.serverName, "demo")
            assertEquals(surface.names, List("add"))
            result match
              case ToolResult.Multi(List(ToolContent.Text(text))) =>
                assertEquals(text, """{"sum":5}""")
              case other =>
                fail(s"unexpected result: $other")
        )
    }
  }

  test("toolSurfaceTyped discharges a typed env at the handler boundary") {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .runToFuture(
          for
            counter <- Ref.make(0)
            server = McpServer.typed[Ref[Int]]("counter")
            schema = ToolInputSchema.default
            _ <- server.tool(
              ToolDefinition(name = "read", description = Some("Read the ref"), inputSchema = schema),
              handler = (_, _) =>
                ZIO.serviceWithZIO[Ref[Int]](_.updateAndGet(_ + 1)).map(n => s"count=$n"),
              options = ToolRegistrationOptions(),
            )
            surface <- ZIO.fromEither(
              FastMcpMount.toolSurfaceTyped("counter", server, ZEnvironment(counter))
            )
            readTool = surface.tools.find(_.name == "read").get.asInstanceOf[com.tjclp.scalagent.tools.ToolDef[Json]]
            r1         <- readTool.handler(Json.Obj())
            r2         <- readTool.handler(Json.Obj())
            finalCount <- counter.get
          yield
            assertEquals(surface.serverName, "counter")
            assert(surface.names.contains("read"))
            assertEquals(finalCount, 2)
            r1 match
              case ToolResult.Success(text) => assertEquals(text, "count=1")
              case other                    => fail(s"unexpected r1: $other")
            r2 match
              case ToolResult.Success(text) => assertEquals(text, "count=2")
              case other                    => fail(s"unexpected r2: $other")
        )
    }
  }
end FastMcpMountSpec

object FastMcpMountSpec:
  final case class AddArgs(a: Int, b: Int) derives JsonDecoder
  final case class AddResult(sum: Int) derives JsonEncoder

  object DemoServer extends McpServerApp[Stdio, DemoServer.type]:
    override val tools: List[McpTool[?, ?]] = List(
      McpTool[AddArgs, AddResult](
        name = "add",
        description = Some("Add two integers"),
      )(args => AddResult(args.a + args.b))
    )
end FastMcpMountSpec
