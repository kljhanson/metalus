package com.acxiom.pipeline.steps

import com.acxiom.pipeline._
import org.apache.log4j.{Level, Logger}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.{BeforeAndAfterAll, GivenWhenThen}

import java.io.File
import java.nio.file.{FileSystems, Files, Path, StandardCopyOption}
import java.util
import scala.jdk.CollectionConverters.asScalaBufferConverter

class JavascriptStepsTests extends AnyFunSpec with BeforeAndAfterAll with GivenWhenThen {
  var pipelineContext: PipelineContext = _
  val sparkLocalDir: Path = Files.createTempDirectory("sparkLocal")

  override def beforeAll(): Unit = {
    Logger.getLogger("com.acxiom.pipeline").setLevel(Level.DEBUG)

    pipelineContext = PipelineContext(Some(Map[String, Any]()),
      List(PipelineParameter(PipelineStateInfo("0"), Map[String, Any]()),
        PipelineParameter(PipelineStateInfo("1"), Map[String, Any]())),
      Some(List("com.acxiom.pipeline.steps")),
      PipelineStepMapper(),
      Some(DefaultPipelineListener()),
      contextManager = new ContextManager(Map(), Map()))
  }

  override def afterAll(): Unit = {
    Logger.getRootLogger.setLevel(Level.INFO)
  }

  describe("JavascriptSteps - Basic scripting") {
    // Copy file
    val tempFile = File.createTempFile("testFile", ".csv")
    tempFile.deleteOnExit()
    Files.copy(getClass.getResourceAsStream("/MOCK_DATA.csv"),
      FileSystems.getDefault.getPath(tempFile.getAbsolutePath),
      StandardCopyOption.REPLACE_EXISTING)

    val script =
      """
         |var File = Java.type('java.io.File');
         |var Files = Java.type('java.nio.file.Files');
         |var Charset = Java.type('java.nio.charset.Charset');
         |
         |Files.readAllLines(new File($path).toPath(), Charset.defaultCharset())
         """.stripMargin

    it("Should load a file using JS") {
      val updatedScript = script.replaceAll("\\$path", s"'${tempFile.getAbsolutePath}'")
      val result = JavascriptSteps.processScript(updatedScript, pipelineContext)
      assert(result.primaryReturn.isDefined)
      val df = result.primaryReturn.get.asInstanceOf[util.ArrayList[String]].asScala
      assert(df.length == 1001)
    }

    it ("Should load a file using JS and a provide user value") {
      val updatedScript = script.replaceAll("\\$path", "userValue")
      val result = JavascriptSteps.processScriptWithValue(updatedScript, tempFile.getAbsolutePath, pipelineContext)
      assert(result.primaryReturn.isDefined)
      val df = result.primaryReturn.get.asInstanceOf[util.ArrayList[String]].asScala
      assert(df.length == 1001)
    }

    it("Should handle multiple values") {
      val scriptWithDerivedTypes =
        """
          |if (v2) {
          |   v1 + v3
          |} else {
          |   -1
          |}
          |""".stripMargin
      val mappings: Map[String, Any] = Map(
        "v1" -> 1,
        "v2" -> true,
        "v3" -> 3
      )
      val result = JavascriptSteps.processScriptWithValues(scriptWithDerivedTypes, mappings, None, pipelineContext)
      assert(result.primaryReturn.isDefined)
      val res = result.primaryReturn.get.asInstanceOf[Double]
      assert(res == 4)
    }

    it("Should unwrap options") {
      val script =
        """
          |if (value) {
          |   value + ' rule!'
          |} else {
          |   'chickens rule!'
          |}
          |""".stripMargin
      val mappings: Map[String, Any] = Map("value" -> None)
      val result1 = JavascriptSteps.processScriptWithValues(script, mappings, None, pipelineContext)
      assert(result1.primaryReturn.isDefined)
      assert(result1.primaryReturn.get.asInstanceOf[String] == "chickens rule!")
      val betterMappings: Map[String, Any] = Map("value" -> Some("silkies"))
      val result2 = JavascriptSteps.processScriptWithValues(script, betterMappings, None, pipelineContext)
      assert(result2.primaryReturn.isDefined)
      assert(result2.primaryReturn.get.asInstanceOf[String] == "silkies rule!")
    }
  }
}
