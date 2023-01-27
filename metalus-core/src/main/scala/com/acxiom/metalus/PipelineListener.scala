package com.acxiom.metalus

import com.acxiom.metalus.audits.{AuditType, ExecutionAudit}
import com.acxiom.metalus.flow.SplitStepException
import org.json4s.ext.EnumNameSerializer
import org.json4s.native.{JsonParser, Serialization}
import org.json4s.{DefaultFormats, Formats}
import org.slf4j.LoggerFactory

import java.util.Date

object PipelineListener {
  def apply(): PipelineListener = DefaultPipelineListener()
}

case class DefaultPipelineListener() extends PipelineListener

trait PipelineListener {
  implicit val formats: Formats = DefaultFormats +
    new EnumNameSerializer(AuditType)
  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * Called when the main application pipeline is started.
    * @param pipelineKey The unique key of the pipeline. This should always be the application pipeline.
    * @param pipelineContext The PipelineContext. This is used to fetch the pipeline template.
    * @return A modified PipelineContext or None if no changes were made.
    */
  def pipelineStarted(pipelineKey: PipelineStateInfo, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    val pipeline = getPipeline(pipelineKey, pipelineContext)
    if (pipeline.isDefined) {
      logger.info(s"Starting pipeline ${pipeline.get.name.getOrElse(pipeline.get.id.getOrElse(""))}")
    } else {
      logger.info(s"Starting unknown pipeline: ${pipelineKey.key}")
    }
    None
  }

  /**
    * Called when the main application pipeline is finished.
    * @param pipelineKey The unique key of the pipeline. This should always be the application pipeline.
    * @param pipelineContext The PipelineContext. This is used to fetch the pipeline template.
    * @return A modified PipelineContext or None if no changes were made.
    */
  def pipelineFinished(pipelineKey: PipelineStateInfo, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    val pipeline = getPipeline(pipelineKey, pipelineContext)
    if (pipeline.isDefined) {
      logger.info(s"Finished pipeline ${pipeline.get.name.getOrElse(pipeline.get.id.getOrElse(""))}")
    } else {
      logger.info(s"Finished unknown pipeline: ${pipelineKey.key}")
    }
    None
  }

  /**
    * Called when a step is being started.
    * @param pipelineKey The unique key of the step.
    * @param pipelineContext The PipelineContext. This is used to fetch the pipeline template.
    * @return A modified PipelineContext or None if no changes were made.
    */
  def pipelineStepStarted(pipelineKey: PipelineStateInfo, pipelineContext: PipelineContext): Option[PipelineContext] = {
    val pipeline = getPipeline(pipelineKey, pipelineContext)
    if (pipeline.isDefined) {
      val step = getStep(pipelineKey, pipeline.get)
      if (step.isDefined) {
        logger.info(s"Starting step ${step.get.displayName.getOrElse(step.get.id.getOrElse(""))} of pipeline ${pipeline.get.name.getOrElse(pipeline.get.id.getOrElse(""))}")
      } else {
        logger.info(s"Starting step (${pipelineKey.stepId.getOrElse("UNKNOWN")}) of pipeline ${pipeline.get.name.getOrElse(pipeline.get.id.getOrElse(""))}")
      }
    } else {
      logger.info(s"Starting step (${pipelineKey.stepId.getOrElse("UNKNOWN")}) in unknown pipeline!")
    }
    None
  }

  /**
    * Called when a step has finished.
    * @param pipelineKey The unique key of the step.
    * @param pipelineContext The PipelineContext. This is used to fetch the pipeline template.
    * @return A modified PipelineContext or None if no changes were made.
    */
  def pipelineStepFinished(pipelineKey: PipelineStateInfo, pipelineContext: PipelineContext): Option[PipelineContext] = {
    val pipeline = getPipeline(pipelineKey, pipelineContext)
    if (pipeline.isDefined) {
      val step = getStep(pipelineKey, pipeline.get)
      if (step.isDefined) {
        logger.info(s"Finished step ${step.get.displayName.getOrElse(step.get.id.getOrElse(""))} of pipeline ${pipeline.get.name.getOrElse(pipeline.get.id.getOrElse(""))}")
      } else {
        logger.info(s"Finished step (${pipelineKey.stepId.getOrElse("UNKNOWN")}) of pipeline ${pipeline.get.name.getOrElse(pipeline.get.id.getOrElse(""))}")
      }
    } else {
      logger.info(s"Finished step (${pipelineKey.stepId.getOrElse("UNKNOWN")}) in unknown pipeline!")
    }
    None
  }

  /**
    * Called when an exception has been encountered that is not being handled by the step of retry logic.
    * @param pipelineKey The unique key of the step.
    * @param exception The exception.
    * @param pipelineContext The Current PipelineContext.
    */
  def registerStepException(pipelineKey: PipelineStateInfo, exception: PipelineStepException, pipelineContext: PipelineContext): Unit = {
    // Base implementation does nothing
  }

  /**
    * Simple method to get the pipeline for this state info.
    * @param pipelineKey The unique key containing the pipeline id.
    * @param pipelineContext The PipelineContext.
    * @return The pipeline template if is found.
    */
  protected def getPipeline(pipelineKey: PipelineStateInfo, pipelineContext: PipelineContext): Option[Pipeline] =
    pipelineContext.pipelineManager.getPipeline(pipelineKey.pipelineId)

  /**
    * Finds the step specified in the state info using th provided pipeline.
    * @param pipelineKey The unique key containing the step id.
    * @param pipeline The pipeline that contains the step.
    * @return The step if it is found.
    */
  protected def getStep(pipelineKey: PipelineStateInfo, pipeline: Pipeline): Option[Step] =
    pipeline.steps.get.find(_.id.getOrElse("NONE") == pipelineKey.stepId.getOrElse("BUG"))
}


trait EventBasedPipelineListener extends PipelineListener {
  def key: String
  def credentialName: String
  def credentialProvider: CredentialProvider

  def generateExecutionMessage(event: String, pipelines: List[Pipeline]): String = {
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "pipelines" -> pipelines.map(pipeline => EventPipelineRecord(pipeline.id.getOrElse(""), pipeline.name.getOrElse("")))
    ))
  }

  def generateAuditMessage(event: String, audit: ExecutionAudit): String = {
    // Must cast to Long or it won't compile
    val duration = audit.end.getOrElse(Constants.ZERO).asInstanceOf[Long] - audit.start
    val auditString = Serialization.write(audit)
    val auditMap = JsonParser.parse(auditString).extract[Map[String, Any]]
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "duration" -> duration,
      "audit" -> auditMap))
  }

  def generatePipelineMessage(event: String, pipeline: Pipeline): String = {
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "pipeline" -> EventPipelineRecord(pipeline.id.getOrElse(""), pipeline.name.getOrElse(""))
    ))
  }

  def generatePipelineStepMessage(event: String, pipeline: Pipeline, step: FlowStep, pipelineContext: PipelineContext): String = {
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "pipeline" -> EventPipelineRecord(pipeline.id.getOrElse(""), pipeline.name.getOrElse("")),
      "step" -> EventPipelineStepRecord(step.id.getOrElse(""), step.stepTemplateId.getOrElse(""),
        pipelineContext.currentStateInfo.get.forkData.getOrElse(ForkData("", -1, None)).id)
    ))
  }

  def generateExceptionMessage(event: String, exception: PipelineStepException, pipelineContext: PipelineContext): String = {
    val executionInfo = pipelineContext.currentStateInfo.get
    val messageList: MessageLists = exception match {
      case fe: ForkedPipelineStepException => fe.exceptions
        .foldLeft(MessageLists(List[String](), List[Array[StackTraceElement]]()))((t, e) =>
          MessageLists(t.messages :+ e._2.getMessage, t.stacks :+ e._2.getStackTrace))
      case se: SplitStepException => se.exceptions
        .foldLeft(MessageLists(List[String](), List[Array[StackTraceElement]]()))((t, e) =>
          MessageLists(t.messages :+ e._2.getMessage, t.stacks :+ e._2.getStackTrace))
      case p: PauseException => MessageLists(List(s"Paused: ${p.getMessage}"), List())
      case _ => MessageLists(List(exception.getMessage), List(exception.getStackTrace))
    }
    Serialization.write(Map[String, Any](
      "key" -> key,
      "event" -> event,
      "eventTime" -> new Date().getTime,
      "pipelineId" -> executionInfo.pipelineId,
      "stepId" -> executionInfo.stepId.getOrElse(""),
      "groupId" -> executionInfo.forkData.getOrElse(ForkData("", -1, None)).id,
      "messages" -> messageList.messages,
      "stacks" -> messageList.stacks
    ))
  }
}


//case class SessionVariables(executionComplete: Boolean = false, currentPipeline: Option[Pipeline] = None, currentStep: Option[PipelineStep] = None)
case class EventPipelineRecord(id: String, name: String)
case class EventPipelineStepRecord(id: String, stepId: String, group: String)
case class MessageLists(messages: List[String], stacks: List[Array[StackTraceElement]])
case class CombinedPipelineListener(listeners: List[PipelineListener]) extends PipelineListener {
  override def pipelineStarted(key: PipelineStateInfo, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineStarted(key, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def pipelineFinished(key: PipelineStateInfo, pipelineContext: PipelineContext):  Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineFinished(key, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def pipelineStepStarted(key: PipelineStateInfo, pipelineContext: PipelineContext): Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineStepStarted(key, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def pipelineStepFinished(key: PipelineStateInfo, pipelineContext: PipelineContext): Option[PipelineContext] = {
    Some(listeners.foldLeft(pipelineContext)((ctx, listener) => {
      val updatedCtx = listener.pipelineStepFinished(key, ctx)
      handleContext(updatedCtx, pipelineContext)
    }))
  }

  override def registerStepException(key: PipelineStateInfo, exception: PipelineStepException, pipelineContext: PipelineContext): Unit = {
    listeners.foreach(_.registerStepException(key, exception, pipelineContext))
  }

  private def handleContext(updatedCtx: Option[PipelineContext], pipelineContext: PipelineContext): PipelineContext = {
    if (updatedCtx.isDefined) {
      updatedCtx.get
    } else {
      pipelineContext
    }
  }
}