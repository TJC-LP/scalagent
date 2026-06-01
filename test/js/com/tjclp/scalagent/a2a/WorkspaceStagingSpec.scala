package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.config.AgentOptions
import zio.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import munit.FunSuite

class WorkspaceStagingSpec extends FunSuite:
  private val runtime  = Runtime.default
  private val nodeCrypto = js.Dynamic.global.require("node:crypto")
  private val nodeFs   = js.Dynamic.global.require("node:fs")
  private val nodeOs   = js.Dynamic.global.require("node:os")
  private val nodePath = js.Dynamic.global.require("node:path")

  private def runTask[A](task: Task[A]): Future[A] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(task)
    }

  private def base64(text: String): String =
    js.Dynamic.global.Buffer
      .from(text, "utf8")
      .applyDynamic("toString")("base64")
      .asInstanceOf[String]

  private def sha256Hex(text: String): String =
    nodeCrypto.createHash("sha256").update(text, "utf8").digest("hex").asInstanceOf[String]

  private def basename(path: String): String =
    nodePath.basename(path).asInstanceOf[String]

  private def join(a: String, b: String): String =
    nodePath.join(a, b).asInstanceOf[String]

  private def tmpDir(prefix: String): String =
    nodeFs.mkdtempSync(join(nodeOs.tmpdir().asInstanceOf[String], prefix)).asInstanceOf[String]

  private def mkdir(path: String): Unit =
    nodeFs.mkdirSync(path, js.Dynamic.literal(recursive = true))

  private def rmrf(path: String): Unit =
    nodeFs.rmSync(path, js.Dynamic.literal(recursive = true, force = true))

  private def readdir(path: String): List[String] =
    nodeFs.readdirSync(path).asInstanceOf[js.Array[String]].toList

  private def writeUtf8(path: String, content: String): Unit =
    nodeFs.writeFileSync(path, content)

  private def readUtf8(path: String): String =
    nodeFs.readFileSync(path, "utf8").asInstanceOf[String]

  test("toInvocationContext grants access to staged workspace root"):
    val staged = WorkspaceStaging.stageFromMessage(A2AMessage.userText("hello"), TaskId("options"))

    runTask {
      ZIO
        .attempt {
          val invocation = staged.toInvocationContext
          val base       = AgentOptions.default.copy(additionalDirectories = List("/existing"))
          val modified   = invocation.optionsModifier(base)

          assertEquals(modified.additionalDirectories, List("/existing", staged.workspace.rootDir))
        }
        .ensuring(staged.cleanup.ignore)
    }

  test("workspace staging uses filesystem-safe task id prefixes"):
    val staged = WorkspaceStaging.stageFromMessage(A2AMessage.userText("hello"), TaskId("../task/with spaces"))
    val spaced = WorkspaceStaging.stageFromMessage(A2AMessage.userText("hello"), TaskId(" task/with spaces "))

    runTask {
      ZIO
        .attempt {
          val rootName = basename(staged.workspace.rootDir)
          val spacedName = basename(spaced.workspace.rootDir)

          assert(rootName.startsWith("tjc-a2a-"))
          assert(!rootName.contains("/"))
          assert(!rootName.contains(".."))
          assert(!spacedName.contains("/"))
          assert(!spacedName.contains(".."))
          assert(rootName != spacedName)
        }
        .ensuring(staged.cleanup.ignore *> spaced.cleanup.ignore)
    }

  test("session logger writes unsafe task ids inside the configured log directory"):
    val dir = tmpDir("a2a-logs-")
    val unsafeTaskId = "../task/with \"quotes\"\nand\ttabs"
    val spacedTaskId = s" $unsafeTaskId "
    val unsafeEvent  = "prompt\"start\nstatus"
    val unsafeData   = "hello \"world\"\n\tbackslash \\ slash /\r done"

    try
      SessionLogger.configure(Some(dir))
      SessionLogger.logEvent(unsafeTaskId, unsafeEvent, unsafeData)
      SessionLogger.logEvent(spacedTaskId, unsafeEvent, unsafeData)
      val files = readdir(dir)

      assertEquals(files.length, 2)
      assertEquals(files.toSet.size, 2)
      assert(files.forall(_.endsWith(".jsonl")))
      assert(files.exists(_.contains(sha256Hex(unsafeTaskId))))
      assert(files.exists(_.contains(sha256Hex(spacedTaskId))))
      assert(files.forall(file => !file.contains("/")))
      assert(files.forall(file => !file.contains("..")))
      assert(files.forall(file => !file.contains("\n")))
      assert(files.forall(file => !file.contains("\t")))

      val rawFile = files.find(_.contains(sha256Hex(unsafeTaskId))).getOrElse(fail("missing unsafe task log"))
      val raw    = readUtf8(join(dir, rawFile))
      val parsed = js.JSON.parse(raw.trim).asInstanceOf[js.Dynamic]

      assertEquals(raw.count(_ == '\n'), 1)
      assertEquals(parsed.selectDynamic("taskId").asInstanceOf[String], unsafeTaskId)
      assertEquals(parsed.selectDynamic("event").asInstanceOf[String], unsafeEvent)
      assertEquals(parsed.selectDynamic("data").asInstanceOf[String], unsafeData)
    finally
      SessionLogger.configure(None)
      rmrf(dir)

  test("stageFromMessage preserves duplicate uploaded filenames"):
    val message = A2AMessage.multi(
      A2ARole.User,
      Part.File(FileContent.Bytes(base64("first"), name = Some("contract.docx"))),
      Part.File(FileContent.Bytes(base64("second"), name = Some("contract.docx"))),
    )
    val staged = WorkspaceStaging.stageFromMessage(message, TaskId("duplicate-upload"))

    runTask {
      ZIO
        .attempt {
          val paths = staged.workspace.inputs.map(_.path)

          assertEquals(paths.map(basename), List("contract.docx", "contract-1.docx"))
          assertEquals(paths.distinct.length, 2)
          assertEquals(readUtf8(paths(0)), "first")
          assertEquals(readUtf8(paths(1)), "second")
        }
        .ensuring(staged.cleanup.ignore)
    }

  test("collectArtifacts uses relative output paths as artifact ids"):
    val staged = WorkspaceStaging.stageFromMessage(A2AMessage.userText("done"), TaskId("nested-artifacts"))
    val enDir  = join(staged.workspace.outputDir, "en")
    val frDir  = join(staged.workspace.outputDir, "fr")

    runTask {
      (for
        _ <- ZIO.attempt {
          mkdir(enDir)
          mkdir(frDir)
          writeUtf8(join(enDir, "report.md"), "English")
          writeUtf8(join(frDir, "report.md"), "French")
        }
        artifacts <- staged.collectArtifacts
      yield
        assertEquals(artifacts.map(_.artifactId), List("en/report.md", "fr/report.md"))
        assertEquals(artifacts.map(_.artifactId).distinct.length, artifacts.length)
      ).ensuring(staged.cleanup.ignore)
    }

  test("collectArtifacts uses shared spreadsheet and csv MIME detection"):
    val staged = WorkspaceStaging.stageFromMessage(A2AMessage.userText("done"), TaskId("artifact-mimes"))

    runTask {
      (for
        _ <- ZIO.attempt {
          writeUtf8(join(staged.workspace.outputDir, "analysis.xlsx"), "xlsx")
          writeUtf8(join(staged.workspace.outputDir, "summary.csv"), "csv")
        }
        artifacts <- staged.collectArtifacts
      yield
        val byId = artifacts.map(artifact => artifact.artifactId -> artifact).toMap
        def mime(id: String): Option[String] =
          byId(id).parts.collectFirst {
            case Part.File(FileContent.Bytes(_, _, mimeType), _) => mimeType
          }.flatten

        assertEquals(
          mime("analysis.xlsx"),
          Some("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
        assertEquals(mime("summary.csv"), Some("text/csv"))
      ).ensuring(staged.cleanup.ignore)
    }
end WorkspaceStagingSpec
