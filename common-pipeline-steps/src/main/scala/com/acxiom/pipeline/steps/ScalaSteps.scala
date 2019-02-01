package com.acxiom.pipeline.steps

import com.acxiom.pipeline.annotations.{StepFunction, StepObject}
import com.acxiom.pipeline.utils.ScalaScriptEngine
import com.acxiom.pipeline.{PipelineContext, PipelineStepResponse}

@StepObject
object ScalaSteps {

  @StepFunction("a7e17c9d-6956-4be0-a602-5b5db4d1c08b",
    "Scala script Step",
    "Executes a Scala script and returns the result",
    "Pipeline")
  def processScript(script: String, pipelineContext: PipelineContext): PipelineStepResponse = {
    val engine = new ScalaScriptEngine
    val result = engine.executeScript(script, pipelineContext)
    handleResult(result)
  }

  @StepFunction("8bf8cef6-cf32-4d85-99f4-e4687a142f84",
    "Scala script Step with additional object provided",
    "Executes a Scala script and returns the result",
    "Pipeline")
  def processScriptWithValue(script: String, value: Any, pipelineContext: PipelineContext): PipelineStepResponse = {
    val engine = new ScalaScriptEngine
    val result = engine.executeScriptWithObject(script, value, pipelineContext)
    handleResult(result)
  }

  @StepFunction("d86b7154-76bb-4e24-8488-23cb0456a0af",
    "Scala script Step with additional object provided",
    "Executes a Scala script and returns the result",
    "Pipeline")
  def processScriptWithTypedValue(script: String, value: Any, `type`: String, pipelineContext: PipelineContext): PipelineStepResponse = {
    val engine = new ScalaScriptEngine
    engine.setBinding("userValue", value, `type`)
    val result = engine.executeScript(script, pipelineContext)
    handleResult(result)
  }

  /**
    * This function will take the provided result value and wrap it in a PipelineStepResponse. If the result is already
    * wrapped in an Option, it will be used as is otherwise it will be wrapped in an Option.
    *
    * @param result The result value to wrap.
    * @return A PipelineStepResponse containing the result as the primary value.
    */
  private def handleResult(result: Any): PipelineStepResponse = {
    result match {
      case response: PipelineStepResponse => response
      case r: Option[_] => PipelineStepResponse(r.asInstanceOf[Option[Any]], None)
      case _ => PipelineStepResponse(Some(result), None)
    }
  }
}
