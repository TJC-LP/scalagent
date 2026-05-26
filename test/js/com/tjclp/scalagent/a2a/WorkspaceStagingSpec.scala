package com.tjclp.scalagent.a2a

import com.tjclp.scalagent.config.AgentOptions
import zio.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import munit.FunSuite

class WorkspaceStagingSpec extends FunSuite:
  private val runtime  = Runtime.default
  private val nodeFs   = js.Dynamic.global.require("node:fs")
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

  private def basename(path: String): String =
    nodePath.basename(path).asInstanceOf[String]

  private def join(a: String, b: String): String =
    nodePath.join(a, b).asInstanceOf[String]

  private def mkdir(path: String): Unit =
    nodeFs.mkdirSync(path, js.Dynamic.literal(recursive = true))

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
end WorkspaceStagingSpec
