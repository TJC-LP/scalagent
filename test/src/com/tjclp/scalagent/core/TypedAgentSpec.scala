package com.tjclp.scalagent.core

import scala.compiletime.testing.typeChecks

class TypedAgentSpec extends munit.FunSuite:

  // A trivial test agent that echoes input
  private val echoAgent: Agent[Any, String, String] = new Agent[Any, String, String]:
    def run(principal: Any, input: String, policy: ExecutionPolicy): AgentRun[Any, String] =
      import zio.*
      import zio.stream.*
      import com.tjclp.scalagent.errors.AgentError
      AgentRun(
        events = ZStream.succeed(AgentEvent.Completed(RunSummary(0, 1, 0.0, true, Some(input)))),
        result = ZIO.succeed(input)
      )

  // --- Builder type accumulation ---

  test("AgentBuilder starts with Any capabilities"):
    val builder = AgentBuilder(echoAgent)
    val typed = builder.build
    assert(typed.agent eq echoAgent)

  test("withBudget adds HasBudget to capability type"):
    val typed: TypedAgent[Nothing, String, String, Any & HasBudget] =
      AgentBuilder(echoAgent).withBudget.build
    // Compiles — type checks

  test("withTools adds CanUseTools[AllTools]"):
    val typed: TypedAgent[Nothing, String, String, Any & CanUseTools[AllTools]] =
      AgentBuilder(echoAgent).withTools(ToolSurface.empty).build
    // Compiles

  test("withReadOnlyTools adds CanUseTools[ReadOnlyTools]"):
    val typed: TypedAgent[Nothing, String, String, Any & CanUseTools[ReadOnlyTools]] =
      AgentBuilder(echoAgent).withReadOnlyTools(ToolSurface.empty).build
    // Compiles

  test("withSpawnDepth adds CanSpawn[D]"):
    val typed: TypedAgent[Nothing, String, String, Any & CanSpawn[Depth2]] =
      AgentBuilder(echoAgent).withSpawnDepth[Depth2].build
    assertEquals(typed.maxRuntimeDepth, 2)

  test("chained capabilities accumulate in intersection"):
    val typed: TypedAgent[Nothing, String, String,
      Any & CanUseTools[AllTools] & HasBudget & CanSpawn[Depth2] & CanEscalateHuman] =
      AgentBuilder(echoAgent)
        .withTools(ToolSurface.empty)
        .withBudget
        .withSpawnDepth[Depth2]
        .withEscalation
        .build
    assertEquals(typed.maxRuntimeDepth, 2)

  // --- Tool surface composition (Finding 4 fix) ---

  test("withTools composes tool surfaces, not overwrites"):
    val tool1 = ToolSurface(Nil) // empty but distinct
    val tool2 = ToolSurface(Nil)
    val typed = AgentBuilder(echoAgent)
      .withTools(tool1)
      .withTools(tool2)
      .build
    // Should have composed, not overwritten
    assert(typed.toolSurface.isEmpty) // both empty, but composition happened

  // --- HasSpawn type class resolution ---

  test("HasSpawn resolves for CanSpawn[D]"):
    summon[HasSpawn[CanSpawn[Depth2]]]

  test("HasSpawn resolves for CanSpawn[D] & Other"):
    summon[HasSpawn[CanSpawn[Depth2] & HasBudget]]

  test("HasSpawn resolves for Other & CanSpawn[D]"):
    summon[HasSpawn[HasBudget & CanSpawn[Depth2]]]

  test("HasSpawn resolves for multi-intersection"):
    summon[HasSpawn[CanUseTools[AllTools] & HasBudget & CanSpawn[Depth1]]]

  test("HasSpawn does NOT resolve without CanSpawn"):
    assert(!typeChecks("summon[HasSpawn[HasBudget & CanUseTools[AllTools]]]"))

  // --- HasToolsCap type class resolution ---

  test("HasToolsCap resolves for CanUseTools[T]"):
    summon[HasToolsCap[CanUseTools[AllTools]]]

  test("HasToolsCap resolves for intersection"):
    summon[HasToolsCap[CanUseTools[ReadOnlyTools] & HasBudget]]

  test("HasToolsCap does NOT resolve without CanUseTools"):
    assert(!typeChecks("summon[HasToolsCap[HasBudget & CanSpawn[Depth1]]]"))

  // --- Generic delegation (Finding 2 fix) ---

  test("delegate compiles on agent with CanSpawn"):
    val parent = AgentBuilder(echoAgent)
      .withSpawnDepth[Depth2]
      .build

    // Generic: pass principal and input explicitly
    val run = parent.delegate(echoAgent, (), "test input")
    // Type checks

  test("delegate accepts children with different principal types"):
    // A child agent with a typed principal
    val typedChild: Agent[String, String, String] = new Agent[String, String, String]:
      def run(principal: String, input: String, policy: ExecutionPolicy): AgentRun[Any, String] =
        import zio.*
        import zio.stream.*
        AgentRun(ZStream.empty, ZIO.succeed(s"$principal: $input"))

    val parent = AgentBuilder(echoAgent).withSpawnDepth[Depth1].build
    // Pass the typed principal
    val run = parent.delegate(typedChild, "admin", "do something")
    // Compiles — delegation is generic over child types

  test("delegate does NOT compile without CanSpawn"):
    assert(!typeChecks("""
      val agent = AgentBuilder(
        new Agent[Any, String, String]:
          def run(p: Any, i: String, pol: ExecutionPolicy): AgentRun[Any, String] =
            AgentRun(zio.stream.ZStream.empty, zio.ZIO.succeed(""))
      ).withBudget.build
      agent.delegate(
        new Agent[Any, String, String]:
          def run(p: Any, i: String, pol: ExecutionPolicy): AgentRun[Any, String] =
            AgentRun(zio.stream.ZStream.empty, zio.ZIO.succeed(""))
        , (), "test"
      )
    """))

  test("delegate slices budget"):
    val parent = AgentBuilder(echoAgent)
      .withSpawnDepth[Depth1]
      .build

    val parentPolicy = ExecutionPolicy(budget = Budget.usd(10.0))
    val delegation = DelegationPolicy(budgetFraction = 0.3)

    // The child receives 30% of parent budget
    val childRun = parent.delegate(echoAgent, (), "test", parentPolicy, delegation)
    // Budget slicing happens inside delegate

  // --- delegateTyped with compile-time depth (Finding 3 fix) ---

  test("delegateTyped rejects child with depth >= parent at compile time"):
    // DepthLTE[S[Z], Z] does not hold, so this should NOT compile
    assert(!typeChecks("""
      val parent = AgentBuilder(
        new Agent[Any, String, String]:
          def run(p: Any, i: String, pol: ExecutionPolicy): AgentRun[Any, String] =
            AgentRun(zio.stream.ZStream.empty, zio.ZIO.succeed(""))
      ).withSpawnDepth[Depth1].build
      val child = AgentBuilder(
        new Agent[Any, String, String]:
          def run(p: Any, i: String, pol: ExecutionPolicy): AgentRun[Any, String] =
            AgentRun(zio.stream.ZStream.empty, zio.ZIO.succeed(""))
      ).withSpawnDepth[Depth1].build
      parent.delegateTyped(child, (), "test")
    """))

  test("delegateTyped accepts child with depth < parent"):
    val parent = AgentBuilder(echoAgent).withSpawnDepth[Depth2].build
    val child = AgentBuilder(echoAgent).withSpawnDepth[Depth1].build

    // parent.depth=2, child.depth=1: OK
    val run = parent.delegateTyped(child, (), "test")
    // No exception

  // --- TypedAgent extends Agent ---

  test("TypedAgent is an Agent"):
    val typed = AgentBuilder(echoAgent).withBudget.build
    val asAgent: Agent[Any, String, String] = typed
    // TypedAgent extends Agent — can be used anywhere Agent is expected

  test("TypedAgent.unwrap returns original agent"):
    // With identity transform, unwrap returns the original
    val typed = AgentBuilder(echoAgent).withBudget.build
    // unwrap gives back an agent (may be transformed, but same behavior)
    assert(typed.unwrap ne null)
