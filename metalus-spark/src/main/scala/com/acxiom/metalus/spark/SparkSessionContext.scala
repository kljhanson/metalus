package com.acxiom.metalus.spark

import com.acxiom.pipeline.Context
import com.acxiom.pipeline.applications.ApplicationUtils.logger
import com.acxiom.pipeline.utils.DriverUtils
import org.apache.log4j.Logger
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.util.CollectionAccumulator

import scala.jdk.CollectionConverters.asScalaBufferConverter

class SparkSessionContext(sparkConfOptions: Map[String, Any]) extends Context {
  private val logger = Logger.getLogger(getClass)
  // Configure the SparkConf
  private val kryoClasses: Array[Class[_]] = if (sparkConfOptions.contains("kryoClasses")) {
    sparkConfOptions("kryoClasses").asInstanceOf[List[String]].map(c => Class.forName(c)).toArray
  } else {
    DriverUtils.DEFAULT_KRYO_CLASSES
  }
  private val initialSparkConf: SparkConf = DriverUtils.createSparkConf(kryoClasses)
  val sparkConf: SparkConf = if (sparkConfOptions.contains("setOptions")) {
    sparkConfOptions("setOptions").asInstanceOf[List[Map[String, String]]].foldLeft(initialSparkConf)((conf, map) => {
      conf.set(map("name"), map("value"))
    })
  } else {
    initialSparkConf.set("spark.hadoop.io.compression.codecs",
      "org.apache.hadoop.io.compress.BZip2Codec,org.apache.hadoop.io.compress.DeflateCodec," +
        "org.apache.hadoop.io.compress.GzipCodec,org.apache." +
        "hadoop.io.compress.Lz4Codec,org.apache.hadoop.io.compress.SnappyCodec")
  }

  val sparkSession: SparkSession = if (sparkConfOptions.contains("enableHiveSupport")) { // Create the SparkSession
    SparkSession.builder().config(sparkConf).enableHiveSupport().getOrCreate()
  } else {
    SparkSession.builder().config(sparkConf).getOrCreate()
  }
  logger.info(s"Setting parquet dictionary enabled to ${sparkConfOptions.getOrElse("parquetDictionaryEnabled", "false")}")
  sparkSession.sparkContext.hadoopConfiguration.set("parquet.enable.dictionary", sparkConfOptions.getOrElse("parquetDictionaryEnabled", "false").toString)

  val stepMessages: Option[CollectionAccumulator[PipelineStepMessage]] =
    Some(sparkSession.sparkContext.collectionAccumulator[PipelineStepMessage]("stepMessages"))

  /**
    * Adds a new PipelineStepMessage to the context
    *
    * @param message The message to add.
    */
  def addStepMessage(message: PipelineStepMessage): Unit = {
    if (stepMessages.isDefined) stepMessages.get.add(message)
  }

  /**
    * Returns a list of PipelineStepMessages.
    *
    * @return a list of PipelineStepMessages
    */
  def getStepMessages: Option[List[PipelineStepMessage]] = {
    if (stepMessages.isDefined) {
      Some(stepMessages.get.value.asScala.toList)
    } else {
      None
    }
  }

  /*
  private def addSparkListener(pipelineListener: Option[PipelineListener], sparkSession: SparkSession): Unit = {
    if (pipelineListener.isDefined &&
      pipelineListener.get.isInstanceOf[SparkListener]) {
      sparkSession.sparkContext.addSparkListener(pipelineListener.get.asInstanceOf[SparkListener])
    }
    if (pipelineListener.isDefined &&
      pipelineListener.get.isInstanceOf[CombinedPipelineListener] &&
      pipelineListener.get.asInstanceOf[CombinedPipelineListener].listeners.exists(_.isInstanceOf[SparkListener])) {
      pipelineListener.get.asInstanceOf[CombinedPipelineListener].listeners.filter(_.isInstanceOf[SparkListener])
        .foreach(listener => {
          sparkSession.sparkContext.addSparkListener(listener.asInstanceOf[SparkListener])
        })
    }
  }
   */

  /*
  private def registerSparkUDFs(globals: Option[Map[String, Any]],
                                sparkSession: SparkSession,
                                sparkUDFs: Option[List[ClassInfo]],
                                validateArgumentTypes: Boolean,
                                credentialProvider: Option[CredentialProvider])(implicit formats: Formats): Unit = {
    if (sparkUDFs.isDefined && sparkUDFs.get.nonEmpty) {
      sparkUDFs.get.flatMap { info =>
        if (info.className.isDefined) {
          Some(ReflectionUtils.loadClass(info.className.get, Some(parseParameters(info, credentialProvider)), validateArgumentTypes).asInstanceOf[PipelineUDF])
        } else {
          None
        }
      }.foreach(udf => udf.register(sparkSession, globals.getOrElse(Map())))
    }
  }
   */

  /*
  private def generateSparkListeners(sparkListenerInfos: Option[List[ClassInfo]],
                                     validateArgumentTypes: Boolean,
                                     credentialProvider: Option[CredentialProvider])(implicit formats: Formats): Option[List[SparkListener]] = {
    if (sparkListenerInfos.isDefined && sparkListenerInfos.get.nonEmpty) {
      Some(sparkListenerInfos.get.flatMap { info =>
        if (info.className.isDefined) {
          Some(ReflectionUtils.loadClass(info.className.get,
            Some(parseParameters(info, credentialProvider)),
            validateArgumentTypes).asInstanceOf[SparkListener])
        } else {
          None
        }
      })
    } else {
      None
    }
  }
   */
}