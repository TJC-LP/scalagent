package com.tjclp.scalagent.macros

import scala.quoted.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.*
import zio.json.*
import zio.schema.Schema
import com.tjclp.scalagent.tools.*
import com.tjclp.scalagent.mcp.*
import com.tjclp.scalagent.config.McpServerConfig

/**
 * Macros for creating MCP tools from annotated methods.
 *
 * Usage:
 * {{{
 * object MyTools:
 *   @Tool("get_weather", "Get weather for a location")
 *   def getWeather(@Param("Location") location: String): Task[String] = ???
 *
 * val server = ToolMacros.createServer[MyTools.type]("my-server", runtime)
 * }}}
 *
 * Key patterns from fast-mcp-scala:
 *   - Always use `using Quotes` - not a named parameter
 *   - Always prefix with `quotes.reflect.*` - ensures consistent quote context
 *   - Use `Select().etaExpand()` - creates stable method references
 *   - Defer invocation to runtime - build Expr trees, don't invoke at macro time
 */
object ToolMacros:

  /**
   * Create an MCP server from annotated methods in type T.
   *
   * Scans the type for methods annotated with @Tool and creates a server configuration.
   *
   * @tparam T
   *   Type containing @Tool-annotated methods
   * @param name
   *   Server name
   * @param runtime
   *   ZIO runtime for executing handlers
   * @param version
   *   Server version (default "1.0.0")
   * @return
   *   MCP server configuration
   */
  inline def createServer[T](
    name: String,
    runtime: Runtime[Any],
    version: String = "1.0.0",
  ): McpServerConfig.Sdk =
    ${ createServerImpl[T]('name, 'runtime, 'version) }

  /**
   * Collect all tools from type T without creating a server.
   *
   * Useful for combining tools from multiple objects.
   */
  inline def collectTools[T](runtime: Runtime[Any]): List[ToolDef[?]] =
    ${ collectToolsImpl[T]('runtime) }

  // =========================================================================
  // MACRO IMPLEMENTATIONS
  // =========================================================================

  private def createServerImpl[T: Type](
    name: Expr[String],
    runtime: Expr[Runtime[Any]],
    version: Expr[String],
  )(using Quotes
  ): Expr[McpServerConfig.Sdk] =
    val tools = collectToolsImpl[T](runtime)
    '{ McpServer.create($name, $tools, $version, $runtime) }

  private def collectToolsImpl[T: Type](
    runtime: Expr[Runtime[Any]]
  )(using Quotes
  ): Expr[List[ToolDef[?]]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    // Find all methods with @Tool annotation
    val toolMethods = sym.declaredMethods.filter { method =>
      method.annotations.exists(a => a.tpe.typeSymbol.fullName == "com.tjclp.scalagent.macros.Tool")
    }

    if toolMethods.isEmpty then
      report.warning(s"No @Tool-annotated methods found in ${sym.fullName}")
      '{ Nil }
    else
      // Process each method INLINE (no separate function to avoid quotes context issues)
      val toolExprs = toolMethods.map { method => processToolMethodInline[T](tpe, method, runtime) }
      Expr.ofList(toolExprs)
  end collectToolsImpl

  /** Process a single @Tool method - ALL LOGIC INLINE to avoid quotes context issues */
  private def processToolMethodInline[T: Type](
    using Quotes
  )(ownerType: quotes.reflect.TypeRepr,
    method: quotes.reflect.Symbol,
    runtime: Expr[Runtime[Any]],
  ): Expr[ToolDef[?]] =
    import quotes.reflect.*

    // 1. Extract @Tool annotation
    val toolAnnotOpt = method.annotations.find(a => a.tpe.typeSymbol.fullName == "com.tjclp.scalagent.macros.Tool")

    val (toolName, toolDesc) = toolAnnotOpt match
      case Some(Apply(_, List(Literal(StringConstant(n)), Literal(StringConstant(d))))) =>
        (n, d)
      case _ =>
        report.errorAndAbort(
          s"@Tool annotation must have string literals for name and description"
        )

    // 2. Extract parameters with @Param descriptions
    // Use method's DefDef tree to get parameter types (avoids experimental Symbol.info)
    val paramSyms = method.paramSymss.headOption.getOrElse(Nil)

    // Extract parameter names and types from the method tree
    val (extractedParamNames, extractedParamTypes): (List[String], List[TypeRepr]) =
      method.tree match
        case ddef: DefDef =>
          val paramDefs: List[ValDef] = ddef.termParamss.headOption match
            case Some(clause) => clause.params
            case None         => Nil
          val names = paramDefs.map(_.name)
          val types = paramDefs.map(_.tpt.tpe)
          (names, types)
        case _ =>
          (Nil, Nil)

    // ParamInfo: (name, type, description, isOptional, enumCases: Option[List[String]])
    val paramInfos: List[(String, TypeRepr, String, Boolean, Option[List[String]])] =
      extractedParamNames.zip(extractedParamTypes).zipWithIndex.map {
        case ((name, tpe), idx) =>
          // Get @Param annotation from corresponding parameter symbol
          val paramDesc = if idx < paramSyms.length then
            paramSyms(idx).annotations.find(a => a.tpe.typeSymbol.fullName == "com.tjclp.scalagent.macros.Param") match
              case Some(Apply(_, List(Literal(StringConstant(desc))))) => desc
              case _                                                   => ""
          else ""

          val isOptional = tpe match
            case AppliedType(base, _) if base.typeSymbol.fullName == "scala.Option" => true
            case _                                                                  => false

          // Detect Scala 3 enums and extract case names
          val enumCases: Option[List[String]] =
            val actualTpe = tpe match
              case AppliedType(base, List(inner)) if base.typeSymbol.fullName == "scala.Option" =>
                inner // Unwrap Option[EnumType]
              case t => t

            if actualTpe.typeSymbol.flags.is(Flags.Enum) then
              val cases = actualTpe.typeSymbol.children.map(_.name)
              Some(cases)
            else None

          (name, tpe, paramDesc, isOptional, enumCases)
      }

    // 3. Validate return type (ToolResult/String or ZIO[Any, Throwable, ToolResult/String])
    val returnType: TypeRepr = method.tree match
      case ddef: DefDef => ddef.returnTpt.tpe
      case _            => report.errorAndAbort(s"Unable to inspect return type for ${method.name}")

    val toolResultTpe    = TypeRepr.of[ToolResult]
    val zioToolResultTpe = TypeRepr.of[ZIO[Any, Throwable, ToolResult]]
    val stringTpe        = TypeRepr.of[String]
    val zioStringTpe     = TypeRepr.of[ZIO[Any, Throwable, String]]

    val returnsToolResult    = returnType <:< toolResultTpe
    val returnsZioToolResult = returnType <:< zioToolResultTpe
    val returnsString        = returnType <:< stringTpe
    val returnsZioString     = returnType <:< zioStringTpe

    if !returnsToolResult && !returnsZioToolResult && !returnsString && !returnsZioString then
      report.errorAndAbort(
        s"@Tool methods must return ToolResult, String, Task[ToolResult], or Task[String] (ZIO[Any, Throwable, ...]). Found: ${returnType.show}"
      )

    // 4. Generate JSON schema (inline to avoid quotes context issues)
    val propsExpr: List[Expr[(String, JsonSchema)]] = paramInfos.map {
      case (name, tpe, desc, isOptional, enumCases) =>
        val baseSchemaExpr = enumCases match
          case Some(cases) =>
            // Generate enum schema with case names
            val casesExpr = Expr(cases)
            '{ JsonSchema.enumOf($casesExpr*) }
          case None =>
            typeToSchemaInline(tpe)

        val schemaExpr =
          if desc.nonEmpty then '{ JsonSchema.Described($baseSchemaExpr, ${ Expr(desc) }) } else baseSchemaExpr

        '{ (${ Expr(name) }, $schemaExpr) }
    }

    val requiredNames = paramInfos.filterNot(_._4).map(_._1) // _._4 is isOptional

    val schemaExpr: Expr[JsonSchema] = '{
      val props        = ${ Expr.ofList(propsExpr) }
      val builder      = JsonSchema.obj(props*)
      val withRequired = builder.required(${ Expr.ofList(requiredNames.map(Expr(_))) }*)
      withRequired.build
    }

    // 5. Create stable method reference using eta-expansion (like fast-mcp-scala)
    val methodRefExpr: Expr[Any] = ownerType match
      case TermRef(_, _) =>
        val companionSym = ownerType.termSymbol
        val methodSymOpt = companionSym.declaredMethod(method.name).headOption.getOrElse {
          report.errorAndAbort(
            s"Could not find method '${method.name}' in ${companionSym.fullName}"
          )
        }
        Select(Ref(companionSym), methodSymOpt).etaExpand(Symbol.spliceOwner).asExprOf[Any]
      case _ =>
        report.errorAndAbort(
          s"Expected singleton type for tool container, got ${ownerType.show}"
        )

    // 6. Build parameter extraction expressions with enum conversion
    // For each parameter, generate code that extracts from map and converts enums
    val argExtractorExprs: List[Expr[scala.collection.immutable.Map[String, Any] => Any]] =
      paramInfos.map {
        case (name, tpe, _, isOptional, enumCases) =>
          val nameExpr = Expr(name)

          // Check if this is an enum type (including Option[Enum])
          val actualTpe = tpe match
            case AppliedType(base, List(inner)) if base.typeSymbol.fullName == "scala.Option" =>
              inner
            case t => t

          if actualTpe.typeSymbol.flags.is(Flags.Enum) then
            // Generate enum conversion code - call valueOf on the enum companion
            // We generate a reference to EnumType.valueOf(string) at macro time
            val enumCompanion = Ref(actualTpe.typeSymbol.companionModule)
            val valueOfMethod = actualTpe.typeSymbol.companionModule.methodMember("valueOf").head

            actualTpe.asType match
              case '[e] =>
                if isOptional then
                  '{ (args: scala.collection.immutable.Map[String, Any]) =>
                    args.get($nameExpr) match
                      case Some(s: String) =>
                        // Call valueOf at runtime - generated at macro time
                        Some(${
                          Apply(
                            Select(enumCompanion, valueOfMethod),
                            List('{ s }.asTerm),
                          ).asExprOf[e]
                        })
                      case Some(null) | None => None
                      case Some(other)       => Some(other.asInstanceOf[e])
                  }
                else
                  '{ (args: scala.collection.immutable.Map[String, Any]) =>
                    args.get($nameExpr) match
                      case Some(s: String) =>
                        ${
                          Apply(
                            Select(enumCompanion, valueOfMethod),
                            List('{ s }.asTerm),
                          ).asExprOf[e]
                        }
                      case Some(other) => other.asInstanceOf[e]
                      case None        => null.asInstanceOf[e]
                  }
            end match
          else
            // Non-enum: just extract from map
            if isOptional then '{ (args: scala.collection.immutable.Map[String, Any]) => args.get($nameExpr) }
            else '{ (args: scala.collection.immutable.Map[String, Any]) => args.getOrElse($nameExpr, null) }
          end if
      }

    val argExtractorsExpr = Expr.ofList(argExtractorExprs)

    // 7. Generate handler that extracts args at RUNTIME with enum conversion
    val handlerExpr: Expr[scala.collection.immutable.Map[String, Any] => Task[ToolResult]] =
      if returnsZioToolResult then
        '{ (args: scala.collection.immutable.Map[String, Any]) =>
          ZIO
            .attempt {
              val extractors          = $argExtractorsExpr
              val argsList: List[Any] = extractors.map(extractor => extractor(args))
              RuntimeInvoker
                .invoke($methodRefExpr, argsList)
                .asInstanceOf[ZIO[Any, Throwable, ToolResult]]
            }
            .flatMap(identity)
        }
      else if returnsZioString then
        '{ (args: scala.collection.immutable.Map[String, Any]) =>
          ZIO
            .attempt {
              val extractors          = $argExtractorsExpr
              val argsList: List[Any] = extractors.map(extractor => extractor(args))
              RuntimeInvoker
                .invoke($methodRefExpr, argsList)
                .asInstanceOf[ZIO[Any, Throwable, String]]
            }
            .flatMap(_.map(ToolResult.text))
        }
      else if returnsToolResult then
        '{ (args: scala.collection.immutable.Map[String, Any]) =>
          ZIO
            .attempt {
              val extractors          = $argExtractorsExpr
              val argsList: List[Any] = extractors.map(extractor => extractor(args))
              RuntimeInvoker
                .invoke($methodRefExpr, argsList)
                .asInstanceOf[ToolResult]
            }
        }
      else
        '{ (args: scala.collection.immutable.Map[String, Any]) =>
          ZIO
            .attempt {
              val extractors          = $argExtractorsExpr
              val argsList: List[Any] = extractors.map(extractor => extractor(args))
              RuntimeInvoker
                .invoke($methodRefExpr, argsList)
                .asInstanceOf[String]
            }
            .map(ToolResult.text)
        }

    // 8. Create ToolDef
    '{
      ToolDef[scala.collection.immutable.Map[String, Any]](
        name = ${ Expr(toolName) },
        description = ${ Expr(toolDesc) },
        inputSchema = $schemaExpr,
        handler = $handlerExpr,
      )(using mapAnyDecoder)
    }
  end processToolMethodInline

  /** Convert TypeRepr to JsonSchema inline - must be called from within the same quotes context */
  private def typeToSchemaInline(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[JsonSchema] =
    import quotes.reflect.*

    tpe match
      case t if t =:= TypeRepr.of[String] =>
        '{ JsonSchema.string }

      case t if t =:= TypeRepr.of[Int] || t =:= TypeRepr.of[Long] =>
        '{ JsonSchema.int }

      case t if t =:= TypeRepr.of[Double] || t =:= TypeRepr.of[Float] =>
        '{ JsonSchema.number }

      case t if t =:= TypeRepr.of[Boolean] =>
        '{ JsonSchema.boolean }

      case AppliedType(base, List(inner)) if base.typeSymbol.fullName == "scala.Option" =>
        typeToSchemaInline(inner)

      case AppliedType(base, List(inner)) if base.typeSymbol.fullName == "scala.collection.immutable.List" =>
        val innerSchema = typeToSchemaInline(inner)
        '{ JsonSchema.array($innerSchema) }

      case _ =>
        // Fallback to object
        '{ JsonSchema.obj().build }
    end match
  end typeToSchemaInline

  // =========================================================================
  // RUNTIME SUPPORT (not macro code)
  // =========================================================================

  /** Decoder for Map[String, Any] - converts JSON to map for handler */
  private given mapAnyDecoder: JsonDecoder[scala.collection.immutable.Map[String, Any]] =
    JsonDecoder[zio.json.ast.Json].map { json => jsonToMap(json) }

  private def jsonToMap(json: zio.json.ast.Json): scala.collection.immutable.Map[String, Any] =
    json match
      case zio.json.ast.Json.Obj(fields) =>
        fields.toList.map { case (k, v) => k -> jsonToAny(v) }.toMap
      case _ => scala.collection.immutable.Map.empty

  private def jsonToAny(json: zio.json.ast.Json): Any =
    json match
      case zio.json.ast.Json.Str(s)   => s
      case zio.json.ast.Json.Num(n)   => n.doubleValue
      case zio.json.ast.Json.Bool(b)  => b
      case zio.json.ast.Json.Null     => null
      case zio.json.ast.Json.Arr(a)   => a.map(jsonToAny).toList
      case obj: zio.json.ast.Json.Obj => jsonToMap(obj)
end ToolMacros

/**
 * Runtime invoker - delegates to pattern matching on FunctionN types.
 *
 * Similar to fast-mcp-scala's RefResolver but simplified for our use case. Uses asInstanceOf casts
 * to invoke functions with the correct arity at runtime.
 */
object RuntimeInvoker:
  def invoke(fn: Any, args: List[Any]): Any =
    (args.length, fn) match
      case (0, f: Function0[?]) =>
        f()
      case (1, f: Function1[?, ?]) =>
        f.asInstanceOf[Function1[Any, Any]](args(0))
      case (2, f: Function2[?, ?, ?]) =>
        f.asInstanceOf[Function2[Any, Any, Any]](args(0), args(1))
      case (3, f: Function3[?, ?, ?, ?]) =>
        f.asInstanceOf[Function3[Any, Any, Any, Any]](args(0), args(1), args(2))
      case (4, f: Function4[?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function4[Any, Any, Any, Any, Any]](args(0), args(1), args(2), args(3))
      case (5, f: Function5[?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function5[Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
        )
      case (6, f: Function6[?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function6[Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
        )
      case (7, f: Function7[?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function7[Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
        )
      case (8, f: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function8[Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
        )
      case (9, f: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function9[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
        )
      case (10, f: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
        )
      case (11, f: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
        )
      case (12, f: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
        )
      case (13, f: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
        )
      case (14, f: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
        )
      case (15, f: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
        )
      case (16, f: Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
        )
      case (17, f: Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[
          Function17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
        )
      case (18, f: Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[
          Function18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
        )
      case (19, f: Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[
          Function19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
        ](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
        )
      case (20, f: Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function20[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19),
        )
      case (21, f: Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function21[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19),
          args(20),
        )
      case (22, f: Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]) =>
        f.asInstanceOf[Function22[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
        ]](
          args(0),
          args(1),
          args(2),
          args(3),
          args(4),
          args(5),
          args(6),
          args(7),
          args(8),
          args(9),
          args(10),
          args(11),
          args(12),
          args(13),
          args(14),
          args(15),
          args(16),
          args(17),
          args(18),
          args(19),
          args(20),
          args(21),
        )
      case _ =>
        throw new IllegalArgumentException(s"Unsupported arity: ${args.length} (max is 22)")
end RuntimeInvoker
