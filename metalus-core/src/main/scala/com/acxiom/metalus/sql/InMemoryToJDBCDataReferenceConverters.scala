package com.acxiom.metalus.sql

import com.acxiom.metalus.connectors.jdbc.JDBCDataConnector
import com.acxiom.metalus.sql.jdbc.{BasicJDBCDataReference, JDBCDataReference}

class InMemoryToJDBCDataReferenceConverters extends DataReferenceConverters {
  private def inMemoryToJDBC(imdr: InMemoryDataReference, saveOperator: Save): DataReference[_] = {
    println(saveOperator)
    println(saveOperator.connector)
    val jdbcConn = saveOperator.connector.map(_.asInstanceOf[JDBCDataConnector])
    val updatedSaveOptions = saveOperator.options.map(_.mapValues(_.toString).toMap)
      .getOrElse(Map.empty[String, String]) + ("dbTable" -> saveOperator.destination)
    jdbcConn.get.getTable(() => {
      // create my table
      //imdr.execute.write.format("jdbc").options(updatedSaveOptions).save(jdbcConn.url)
      saveOperator.destination
    }, Some(updatedSaveOptions), imdr.pipelineContext)
  }
  override def getConverters: DataReferenceConverter = {
    case(imdr: InMemoryDataReference, save: Save) => inMemoryToJDBC(imdr, save)
  }
}
