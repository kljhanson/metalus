package com.acxiom.metalus.context

import com.acxiom.metalus._
import com.acxiom.metalus.audits.ExecutionAudit
import org.slf4j.LoggerFactory

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.sql.{DriverManager, ResultSet}
import java.util
import java.util.{Date, Properties, UUID}
import scala.language.postfixOps

trait SessionContext extends Context {
  def existingSessionId: Option[String]

  def sessionStorage: Option[SessionStorage]

  def sessionConvertors: Option[List[SessionConvertor]]

  def credentialProvider: Option[CredentialProvider]

  /**
   * The unique session id being used to track state.
   *
   * @return The session id
   */
  def sessionId: UUID

  /**
   * The unique id for this run of the session.
   * @return The id of the run.
   */
  def runId: Int

  /**
   * Called when application execution begins.
   * @return true if the event is logged.
   */
  def startSession(): Boolean

  /**
   * Called when an application completes.
   * @param status The status of the application. Will be either: COMPLETE, ERROR, PAUSE
   * @return true if the application can be completed.
   */
  def completeSession(status: String): Boolean

  /**
   * Returns the history for this session. None will be returned for new sessions.
   * @return An option list of session history or None.
   */
  def sessionHistory: Option[List[SessionInformation]]

  /**
   * Saves the result of the step.
   *
   * @param key    The step key.
   * @param result The result to save.
   * @return true if the step could be saved.
   */
  def saveStepResult(key: PipelineStateKey, result: PipelineStepResponse): Boolean

  /**
   * Loads the step results for this session or None if this is a new session or has no recorded results.
   *
   * @return An optional list of step results.
   */
  def loadStepResults(): Option[Map[String, PipelineStepResponse]]

  /**
   * Saves an audit for the provided key.
   *
   * @param key   The key
   * @param audit The audit to store
   * @return True if the audit was saved.
   */
  def saveAudit(key: PipelineStateKey, audit: ExecutionAudit): Boolean

  /**
   * Loads the audits for this session or None if this is a new session or has no recorded audits.
   *
   * @return An optional list of audits
   */
  def loadAudits(): Option[List[ExecutionAudit]]

  /**
   * Stores the globals for this session.
   *
   * @param key     The pipeline key for these globals
   * @param globals The globals to store.
   * @return True if the globals could be stored.
   */
  def saveGlobals(key: PipelineStateKey, globals: Map[String, Any]): Boolean

  /**
   * Loads the globals for this session or None if none were recorded.
   *
   * @param key The pipeline key for these globals
   * @return An optional globals map.
   */
  def loadGlobals(key: PipelineStateKey): Option[Map[String, Any]]

  /**
   * Saves the status identified by the provided key. Valid status codes are:
   * RUNNING
   * COMPLETE
   * ERROR
   *
   * @param key       The unique key for the status
   * @param status    The status code
   * @param nextSteps An optional list of steps being called by this step. Should only be supplied with ERROR or COMPLETE status.
   * @return True if the status could be saved.
   */
  def saveStepStatus(key: PipelineStateKey, status: String, nextSteps: Option[List[String]]): Boolean

  /**
   * Returns all recorded status for this session or None.
   *
   * @return An optional list of recorded status.
   */
  def loadStepStatus(): Option[List[StepStatus]]
}

case class SessionInformation(sessionId: UUID, runId: Int, status: String, startDate: Date, endDate: Date, durations: Long)
case class StepStatus(stepKey: String, runId: Int, status: String, nextSteps: Option[List[String]])

/**
 * The session context is used to manage state of a running flow.
 *
 * @param existingSessionId  An optional sessionId used during recovery or restarts.
 * @param sessionStorage     The information needed to load a SessionStorage object.
 * @param sessionConvertors  The information needed to load additional state convertors.
 * @param credentialProvider Optional credential provider to assist with authentication
 */
case class DefaultSessionContext(override val existingSessionId: Option[String],
                                 override val sessionStorage: Option[SessionStorage],
                                 override val sessionConvertors: Option[List[SessionConvertor]],
                                 override val credentialProvider: Option[CredentialProvider] = None) extends SessionContext {
  private val logger = LoggerFactory.getLogger(DefaultSessionContext.getClass)

  private val defaultConvertor = DefaultSessionConvertor()

  private val primaryKey = "primaryKey"

  private lazy val storage: SessionStorage = sessionStorage.getOrElse(NoopSessionStorage())

  private lazy val convertors = sessionConvertors.getOrElse(List()) :+ defaultConvertor

  /**
   * The unique session id being used to track state.
   *
   * @return The session id
   */
  val sessionId: UUID = {
    if (existingSessionId.isDefined) {
      UUID.fromString(existingSessionId.get)
    } else {
      UUID.randomUUID()
    }
  }

  lazy val runId: Int = {
    val history = this.sessionHistory
    if (history.isEmpty) {
      1
    } else {
      history.get.head.runId + 1
    }
  }

  override def startSession(): Boolean =
    storage.startSession(sessionId, runId, System.currentTimeMillis(), "RUNNING")

  override def completeSession(status: String): Boolean =
    storage.completeSession(sessionId, System.currentTimeMillis(), status.toUpperCase)

  override def sessionHistory: Option[List[SessionInformation]] = storage.getSessionHistory(sessionId)

  /**
   * Saves the result of the step.
   *
   * @param key    The step key.
   * @param result The result to save.
   * @return true if the step could be saved.
   */
  def saveStepResult(key: PipelineStateKey, result: PipelineStepResponse): Boolean = {
    val saved = saveStepResult(result.primaryReturn.getOrElse(""), key.key, primaryKey)
    if (result.namedReturns.isDefined) {
      result.namedReturns.get.foldLeft(saved)((s, r) => {
        if (saveStepResult(r._2, key.key, r._1) && s) {
          true
        } else {
          false
        }
      })
    } else {
      saved
    }
  }

  /**
   * Loads the step results for this session or None if this is a new session or has no recorded results.
   *
   * @return An optional list of step results.
   */
  def loadStepResults(): Option[Map[String, PipelineStepResponse]] = {
    val results = storage.loadStepResults(sessionId)
    if (results.isDefined && results.get.nonEmpty) {
      val resultGroups = results.get.groupBy(record => (record.resultKey, record.name)).map(group => {
        if (group._2.length == 1) {
          group._2.head
        } else {
          group._2.maxBy(_.runId)
        }
      })
      val resultsMap = resultGroups.foldLeft(Map[String, PipelineStepResponse]())((responseMap, result) => {
        val response = if (responseMap.contains(result.resultKey)) {
          responseMap(result.resultKey)
        } else {
          PipelineStepResponse(None, None)
        }
        val convertor: SessionConvertor = findNamedConvertor(result.convertor)
        val updatedResponse = if (result.name == primaryKey) {
          response.copy(primaryReturn = Some(convertor.deserialize(result.state)))
        } else { // Secondary return
          val map = if (response.namedReturns.isDefined) {
            response.namedReturns.get
          } else {
            Map[String, Any]()
          }
          response.copy(namedReturns = Some(map + (result.name -> convertor.deserialize(result.state))))
        }
        responseMap + (result.resultKey -> updatedResponse)
      })
      Some(resultsMap)
    } else {
      None
    }
  }

  /**
   * Saves an audit for the provided key.
   *
   * @param key   The key
   * @param audit The audit to store
   * @return True if the audit was saved.
   */
  def saveAudit(key: PipelineStateKey, audit: ExecutionAudit): Boolean = {
    val convertor = convertors.find(_.canConvert(audit))
    if (convertor.isDefined) {
      storage.saveAudit(AuditSessionRecord(sessionId, new Date(), runId, convertor.get.serialize(audit),
        convertor.get.name, key.key, audit.start, audit.end.getOrElse(-1L), audit.durationMs.getOrElse(-1L)))
    } else {
      logger.warn(s"Unable to serialize object for key: ${key.key}")
      false
    }
  }

  /**
   * Loads the audits for this session or None if this is a new session or has no recorded audits.
   *
   * @return An optional list of audits
   */
  def loadAudits(): Option[List[ExecutionAudit]] = {
    val audits = storage.loadAudits(sessionId)
    if (audits.isDefined) {
      Some(audits.get.groupBy(_.auditKey).map(group => {
        val record = if (group._2.length == 1) {
          group._2.head
        } else {
          group._2.maxBy(_.runId)
        }
        val convertor: SessionConvertor = findNamedConvertor(record.convertor)
        convertor.deserialize(record.state).asInstanceOf[ExecutionAudit]
      }).toList)
    } else {
      None
    }
  }

  /**
   * Stores the globals for this session.
   *
   * @param globals The globals to store.
   * @return True if the globals could be stored.
   */
  def saveGlobals(key: PipelineStateKey, globals: Map[String, Any]): Boolean = {
    globals.forall(global => {
      val convertor = convertors.find(_.canConvert(global._2))
      if (convertor.isDefined) {
        storage.saveGlobal(GlobalSessionRecord(sessionId, new Date(), runId, convertor.get.serialize(global._2),
          convertor.get.name, key.key, global._1))
      } else {
        false
      }
    })
  }

  /**
   * Loads the globals for this session or None if none were recorded.
   *
   * @return An optional globals map.
   */
  def loadGlobals(key: PipelineStateKey): Option[Map[String, Any]] = {
    val globalRecords = storage.loadGlobals(sessionId)
    if (globalRecords.isDefined) {
      Some(globalRecords.get.foldLeft(Map[String, Any]())((mapResponse, global) => {
        val convertor = findNamedConvertor(global.convertor)
        mapResponse + (global.globalName -> convertor.deserialize(global.state))
      }))
    } else {
      None
    }
  }

  /**
   * Saves the status identified by the provided key. Valid status codes are:
   * RUNNING
   * COMPLETE
   * ERROR
   *
   * @param key    The unique key for the status
   * @param status The status code
   * @param nextSteps An optional list of steps being called by this step. Should only be supplied with ERROR or COMPLETE status.
   * @return True if the status could be saved.
   */
  def saveStepStatus(key: PipelineStateKey, status: String, nextSteps: Option[List[String]]): Boolean = {
    val convertedStatus = status.toUpperCase match {
      case "RUNNING" => "RUNNING"
      case "COMPLETE" => "COMPLETE"
      case "ERROR" => "ERROR"
      case _ => "UNKNOWN"
    }
    storage.setStatus(StatusSessionRecord(sessionId, new Date(), runId, key.key, convertedStatus, nextSteps))
  }

  /**
   * Returns all recorded status for this session or None.
   *
   * @return An optional list of recorded status.
   */
  override def loadStepStatus(): Option[List[StepStatus]] = {
    val statusRecords = storage.loadStepStatus(sessionId)
    if (statusRecords.isDefined && statusRecords.get.nonEmpty) {
      Some(statusRecords.get.groupBy(_.resultKey).map(group => {
        val record = if (group._2.length == 1) {
          group._2.head
        } else {
          group._2.maxBy(_.runId)
        }
        StepStatus(record.resultKey, record.runId, record.status, record.nextSteps)
      }).toList)
    } else {
      None
    }
  }

  private def findNamedConvertor(convertorName: String) = {
    val convertorOpt = convertors.find(_.name == convertorName)
    val convertor = if (convertorOpt.isDefined) {
      convertorOpt.get
    } else {
      defaultConvertor
    }
    convertor
  }

  private def saveStepResult(obj: Any, key: String, name: String): Boolean = {
    val convertor = convertors.find(_.canConvert(obj))
    if (convertor.isDefined) {
      storage.saveStepResult(StepResultSessionRecord(sessionId, new Date(), runId,
        convertor.get.serialize(obj), convertor.get.name, key, name))
    } else {
      logger.warn(s"Unable to serialize object for key: $key")
      false
    }
  }
}

/**
 * The SessionConvertor provides the methods used by the SessionContext to serialize/deserialize objects.
 */
trait SessionConvertor {
  /**
   * Provides a unique name that is used to locate this convertor from the list ov convertors.
   *
   * @return The unique name of this convertor.
   */
  def name: String

  /**
   * This method will inspect the object and determine if it should handle the serialization/deserialization. This
   * is used by custom convertors to indicate that this convertor should be used.
   *
   * @param obj The object to serialize/deserialize
   * @return true if this convertor should be used for serialization/deserialization.
   */
  def canConvert(obj: Any): Boolean

  /**
   * Serializes the provided object for storage. Implementations should override this for objects that cannot
   * be serialize as is and may need to be modified in a way to enable restoration later.
   *
   * @param obj The object to serialize.
   * @return A byte array that can be stored.
   */
  def serialize(obj: Any): Array[Byte]

  /**
   * Deserializes the provided object from storage. Implementations should override this for objects that cannot
   * be deserialized into the original object.
   *
   * @param obj The byte array to deserialize.
   * @return A byte array that can be deserialized.
   */
  def deserialize(obj: Array[Byte]): Any
}

/**
 * The default implementation of the SessionConvertor. This implementation uses Java serialization
 * to serialize/deserialize objects.
 */
case class DefaultSessionConvertor() extends SessionConvertor {
  override def name: String = "DefaultConvertor"

  override def canConvert(obj: Any): Boolean = obj.isInstanceOf[java.io.Serializable]

  override def serialize(obj: Any): Array[Byte] = {
    val o = new ByteArrayOutputStream()
    val out = new ObjectOutputStream(o)
    out.writeObject(obj)
    out.flush()
    val result = o.toByteArray
    out.close()
    result
  }

  override def deserialize(obj: Array[Byte]): Any = {
    val input = new ByteArrayInputStream(obj)
    val stream = new ObjectInputStream(input)
    val newObj = stream.readObject()
    stream.close()
    newObj
  }
}

/**
 * Defines the minimum properties required to store state information from the SessionContext.
 */
trait SessionRecord {
  /**
   * The unique id for this session.
   *
   * @return A unique session id.
   */
  def sessionId: UUID

  /**
   * The date this record is to be stored.
   *
   * @return The date this recored was stored.
   */
  def date: Date

  /**
   * An id indicating the run of this session for the data. This is useful for restarts and recovery.
   *
   * @return An id indicating the version within this session for the data
   */
  def runId: Int
}

/**
 * This trait extends the SessionRecord and adds the name of the convertor that is used for
 * serialization/deserialization and the serialized data to be stored.
 */
trait SerializedSessionRecord extends SessionRecord {
  /**
   * The name of the convertor to use for serialization/deserialization.
   *
   * @return The name of the convertor to use for serialization/deserialization.
   */
  def convertor: String


  /**
   * The serialized state data.
   *
   * @return The serialized state data.
   */
  def state: Array[Byte]
}

case class StatusSessionRecord(override val sessionId: UUID,
                               override val date: Date,
                               override val runId: Int,
                               resultKey: String,
                               status: String,
                               nextSteps: Option[List[String]]) extends SessionRecord

case class AuditSessionRecord(override val sessionId: UUID,
                              override val date: Date,
                              override val runId: Int,
                              override val state: Array[Byte],
                              override val convertor: String,
                              auditKey: String,
                              start: Long,
                              end: Long,
                              duration: Long) extends SerializedSessionRecord

case class StepResultSessionRecord(override val sessionId: UUID,
                                   override val date: Date,
                                   override val runId: Int,
                                   override val state: Array[Byte],
                                   override val convertor: String,
                                   resultKey: String,
                                   name: String) extends SerializedSessionRecord

case class GlobalSessionRecord(override val sessionId: UUID,
                               override val date: Date,
                               override val runId: Int,
                               override val state: Array[Byte],
                               override val convertor: String,
                               resultKey: String,
                               globalName: String) extends SerializedSessionRecord

trait SessionStorage {
  /**
   * Creates a new entry for this session.
   *
   * @param sessionId The session id.
   * @param runId     The id of this run of a session.
   * @param startDate The date this session was started.
   * @param status    The status to log. Should be RUNNING.
   * @return true if the data was stored.
   */
  def startSession(sessionId: UUID, runId: Int, startDate: Long, status: String): Boolean

  /**
   * Updates the session data to include the provided end date and status. Status should be either COMPLETE, ERROR or PAUSED.
   *
   * @param sessionId The session id.
   * @param endDate   The date this session ended.
   * @param status    The status to log. Should be COMPLETE, ERROR or PAUSED.
   * @return true if the data was stored
   */
  def completeSession(sessionId: UUID, endDate: Long, status: String): Boolean

  /**
   * Returns an history for this session.
   * @param sessionId The unique session id.
   * @return An optional list of history information.
   */
  def getSessionHistory(sessionId: UUID): Option[List[SessionInformation]]

  /**
   * Sets the current status for the provided key.
   *
   * @param sessionRecord The record containing the status data.
   * @return true if the status can be saved.
   */
  def setStatus(sessionRecord: StatusSessionRecord): Boolean

  /**
   * Loads the recorded status for the provided sessionId.
   * @param sessionId The unique session id.
   * @return A list of status or None.
   */
  def loadStepStatus(sessionId: UUID): Option[List[StatusSessionRecord]]

  /**
   * Stores audit data.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if the data can be stored.
   */
  def saveAudit(sessionRecord: AuditSessionRecord): Boolean

  /**
   * Loads the most recent version of the audits for this session.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  def loadAudits(sessionId: UUID): Option[List[AuditSessionRecord]]

  /**
   * Saves a step result.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if it is saved.
   */
  def saveStepResult(sessionRecord: StepResultSessionRecord): Boolean

  /**
   * Loads a list of step result records.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  def loadStepResults(sessionId: UUID): Option[List[StepResultSessionRecord]]

  /**
   * Saves an element of the globals.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if it is saved.
   */
  def saveGlobal(sessionRecord: GlobalSessionRecord): Boolean

  /**
   * Loads a list of global items.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  def loadGlobals(sessionId: UUID): Option[List[GlobalSessionRecord]]
}

case class NoopSessionStorage() extends SessionStorage {

  /**
   * Sets the current status for the provided key.
   *
   * @param sessionRecord The record containing the status data.
   * @return true if the status can be saved.
   */
  override def setStatus(sessionRecord: StatusSessionRecord): Boolean = true

  /**
   * Loads the recorded status for the provided sessionId.
   *
   * @param sessionId The unique session id.
   * @return A list of status or None.
   */
  override def loadStepStatus(sessionId: UUID): Option[List[StatusSessionRecord]] = None

  /**
   * Saves a step result.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if it is saved.
   */
  override def saveStepResult(sessionRecord: StepResultSessionRecord): Boolean = true

  /**
   * Loads a list of step result records.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  override def loadStepResults(sessionId: UUID): Option[List[StepResultSessionRecord]] = None

  /**
   * Saves an element of the globals.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if it is saved.
   */
  override def saveGlobal(sessionRecord: GlobalSessionRecord): Boolean = true

  /**
   * Loads a list of global items.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  override def loadGlobals(sessionId: UUID): Option[List[GlobalSessionRecord]] = None

  /**
   * Stores audit data.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if the data can be stored.
   */
  override def saveAudit(sessionRecord: AuditSessionRecord): Boolean = true

  /**
   * Loads the most recent version of the audits for this session.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  override def loadAudits(sessionId: UUID): Option[List[AuditSessionRecord]] = None

  /**
   * Creates a new entry for this session.
   *
   * @param sessionId The session id.
   * @param runId     The id of this run of a session.
   * @param startDate The date this session was started.
   * @param status    The status to log. Should be RUNNING.
   * @return true if the data was stored.
   */
  override def startSession(sessionId: UUID, runId: Int, startDate: Long, status: String): Boolean = true

  /**
   * Updates the session data to include the provided end date and status. Status should be either COMPLETE, ERROR or PAUSED.
   *
   * @param sessionId The session id.
   * @param endDate   The date this session ended.
   * @param status    The status to log. Should be COMPLETE, ERROR or PAUSED.
   * @return true if the data was stored
   */
  override def completeSession(sessionId: UUID, endDate: Long, status: String): Boolean = true

  /**
   * Returns an history for this session.
   *
   * @param sessionId The unique session id.
   * @return An optional list of history information.
   */
  override def getSessionHistory(sessionId: UUID): Option[List[SessionInformation]] = None
}

// noinspection SqlNoDataSourceInspection,SqlDialectInspection
case class JDBCSessionStorage(connectionString: String,
                              connectionProperties: Map[String, String],
                              credentialName: Option[String] = None,
                              credentialProvider: Option[CredentialProvider] = None) extends SessionStorage {
  private val properties = new Properties()
  connectionProperties.foreach(entry => properties.put(entry._1, entry._2))

  private val CONN_PROPERTIES = if (credentialName.isDefined && credentialProvider.isDefined) {
    val cred = credentialProvider.get.getNamedCredential(credentialName.get)
    if (cred.isDefined) {
      properties.setProperty("user", cred.get.asInstanceOf[UserNameCredential].username)
      properties.setProperty("password", cred.get.asInstanceOf[UserNameCredential].password)
    }
    properties
  } else {
    properties
  }

  private val connection = DriverManager.getConnection(connectionString, CONN_PROPERTIES)

  /**
   * Creates a new entry for this session.
   *
   * @param sessionId The session id.
   * @param runId     The id of this run of a session.
   * @param startDate The date this session was started.
   * @param status    The status to log. Should be RUNNING.
   * @return true if the data was stored.
   */
  override def startSession(sessionId: UUID, runId: Int, startDate: Long, status: String): Boolean = {
    // Table --> |SESSION_ID|RUN_ID|STATUS|START_TIME|END_TIME|DURATION
    val results = connection.prepareStatement(s"select * from SESSIONS where SESSION_ID = '${sessionId.toString}'").executeQuery()
    if (results.next()) {
      connection.prepareStatement(
        s"""INSERT INTO SESSION_HISTORY
           |VALUES('${sessionId.toString}',
           |$runId,
           |'${results.getString("STATUS")}',
           |${results.getLong("START_TIME")},
           |${results.getLong("END_TIME")},
           |${results.getLong("DURATION")})""".stripMargin)
        .executeUpdate()
      connection.prepareStatement(s"DELETE FROM SESSIONS WHERE SESSION_ID = '${sessionId.toString}'").executeUpdate()
    }
    connection.prepareStatement(s"INSERT INTO SESSIONS VALUES('${sessionId.toString}', $runId, '$status', $startDate, -1, -1)")
      .executeUpdate() == 1
  }

  /**
   * Updates the session data to include the provided end date and status. Status should be either COMPLETE, ERROR or PAUSED.
   *
   * @param sessionId The session id.
   * @param endDate   The date this session ended.
   * @param status    The status to log. Should be COMPLETE, ERROR or PAUSED.
   * @return true if the data was stored
   */
  override def completeSession(sessionId: UUID, endDate: Long, status: String): Boolean = {
    // Table --> |SESSION_ID|RUN_ID|STATUS|START_TIME|END_TIME|DURATION
    val results = connection.prepareStatement(s"select * from SESSIONS where SESSION_ID = '${sessionId.toString}'").executeQuery()
    if (results.next()) {
      val startTime = results.getLong("START_TIME")
      connection.prepareStatement(
        s"""UPDATE SESSIONS SET END_TIME = $endDate, DURATION = ${endDate - startTime}, STATUS = '$status'
            WHERE SESSION_ID = '${sessionId.toString}'""").executeUpdate() == 1
    } else {
      false
    }
  }

  /**
   * Returns an history for this session.
   *
   * @param sessionId The unique session id.
   * @return An optional list of history information.
   */
  override def getSessionHistory(sessionId: UUID): Option[List[SessionInformation]] = {
    // Tables --> |SESSION_ID|RUN_ID|STATUS|START_TIME|END_TIME|DURATION
    val sessionResult = connection.prepareStatement(s"select * from SESSIONS where SESSION_ID = '${sessionId.toString}'").executeQuery()
    val mainRecord = if (sessionResult.next()) {
      Some(List(createSessionInformation(sessionResult)))
    } else {
      None
    }
    val historyResults = connection.prepareStatement(s"select * from SESSION_HISTORY where SESSION_ID = '${sessionId.toString}'").executeQuery()
    val historyRecords = Iterator.from(0).takeWhile(_ => historyResults.next()).map(_ => createSessionInformation(historyResults))
    val finalList = if (mainRecord.isDefined) {
      historyRecords.foldLeft(mainRecord.get)((list, history) => list :+ history)
    } else {
      historyRecords
    }

    if (finalList.nonEmpty) {
      Some(finalList.toList)
    } else {
      None
    }
  }

  private def createSessionInformation(sessionResult: ResultSet) = {
    SessionInformation(UUID.fromString(sessionResult.getString("SESSION_ID")),
      sessionResult.getInt("RUN_ID"),
      sessionResult.getString("STATUS"),
      new Date(sessionResult.getLong("START_TIME")),
      new Date(sessionResult.getLong("END_TIME")),
      sessionResult.getLong("DURATION"))
  }

  /**
   * Sets the current status for the provided key.
   *
   * @param sessionRecord The record containing the status data.
   * @return true if the status can be saved.
   */
  override def setStatus(sessionRecord: StatusSessionRecord): Boolean = {
    // Status Table --> |SESSION_ID|DATE|RUN_ID|RESULT_KEY|STATUS
    // Steps Table  --> |SESSION_ID|RUN_ID|RESULT_KEY|STEP_ID
    val sharedWhere = s"where SESSION_ID = '${sessionRecord.sessionId}' AND RESULT_KEY = '${sessionRecord.resultKey}'"
    val results = connection.prepareStatement(s"select * from STEP_STATUS $sharedWhere").executeQuery()
    // Insert the next steps
    if (sessionRecord.nextSteps.isDefined && sessionRecord.nextSteps.get.nonEmpty) {
      sessionRecord.nextSteps.get.foreach(step => {
        connection.prepareStatement(
          s"""INSERT INTO STEP_STATUS_STEPS VALUES('${sessionRecord.sessionId}', ${sessionRecord.runId},
                                     '${sessionRecord.resultKey}', '$step')""").executeUpdate()
      })
    }
    if (results.next()) {
      val count =
        connection.prepareStatement(s"update STEP_STATUS set STATUS = '${sessionRecord.status}', RUN_ID = ${sessionRecord.runId} $sharedWhere ")
          .executeUpdate()
      count == 1
    } else {
      val count = connection.prepareStatement(
        s"""INSERT INTO STEP_STATUS
      VALUES('${sessionRecord.sessionId}', ${sessionRecord.date.getTime},
      ${sessionRecord.runId}, '${sessionRecord.resultKey}',
      '${sessionRecord.status}')""").executeUpdate()
      count == 1
    }
  }

  /**
   * Loads the recorded status for the provided sessionId.
   *
   * @param sessionId The unique session id.
   * @return A list of status or None.
   */
  override def loadStepStatus(sessionId: UUID): Option[List[StatusSessionRecord]] = {
    // Status Table --> |SESSION_ID|DATE|RUN_ID|RESULT_KEY|STATUS
    // Steps Table  --> |SESSION_ID|RUN_ID|RESULT_KEY|STEP_ID
    val sharedWhere = s"WHERE SESSION_ID = '${sessionId.toString}'"
    val stepResults = connection.prepareStatement(s"SELECT * FROM STEP_STATUS_STEPS $sharedWhere").executeQuery()
    val stepsList = Iterator.from(0).takeWhile(_ => stepResults.next()).map(_ => {
      (stepResults.getInt("RUN_ID"),
        stepResults.getString("RESULT_KEY"),
        stepResults.getString("STEP_ID"))
    })
    val stepMap = stepsList.foldLeft(Map[String, List[String]]())((map, step) => {
      val key = s"${step._1}_${step._2}"
      if (map.contains(key)) {
        map + (key -> (map(key) :+ step._3))
      } else {
        map + (key -> List(step._3))
      }
    })
    val results = connection.prepareStatement(s"SELECT * FROM STEP_STATUS $sharedWhere").executeQuery()
    val list = Iterator.from(0).takeWhile(_ => results.next()).map(_ => {
      val key = s"${results.getInt("RUN_ID")}_${results.getString("RESULT_KEY")}"
      StatusSessionRecord(UUID.fromString(results.getString("SESSION_ID")),
        new Date(results.getLong("DATE")),
        results.getInt("RUN_ID"),
        results.getString("RESULT_KEY"),
        results.getString("STATUS"),
        stepMap.get(key))
    }).toList
    if (list.nonEmpty) {
      Some(list)
    } else {
      None
    }
  }

  /**
   * Stores audit data.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if the data can be stored.
   */
  override def saveAudit(sessionRecord: AuditSessionRecord): Boolean = {
    // Table --> |SESSION_ID|DATE|RUN_ID|CONVERTOR|AUDIT_KEY|START_TIME|END_TIME|DURATION|STATE
    val sharedWhere = s"WHERE SESSION_ID = '${sessionRecord.sessionId}' AND AUDIT_KEY = '${sessionRecord.auditKey}'"
    val results = connection.prepareStatement(s"SELECT * FROM AUDITS $sharedWhere").executeQuery()
    val statement = if (results.next()) {
      val setClause =
        s"""RUN_ID = ${sessionRecord.runId}, STATE = ?, END_TIME = ${sessionRecord.end},
           |START_TIME = ${sessionRecord.start}, DURATION = ${sessionRecord.duration},
           |DATE = ${sessionRecord.date.getTime}""".stripMargin
      connection.prepareStatement(s"update AUDITS set $setClause $sharedWhere")
    } else {
      val valuesClause =
        s"""'${sessionRecord.sessionId}', ${sessionRecord.date.getTime},
           |${sessionRecord.runId}, '${sessionRecord.convertor}',
           |'${sessionRecord.auditKey}', ${sessionRecord.start}, ${sessionRecord.end},
           |${sessionRecord.duration}, ?""".stripMargin
      connection.prepareStatement(s"INSERT INTO AUDITS VALUES($valuesClause)")
    }
    statement.setBlob(1, new ByteArrayInputStream(sessionRecord.state))
    statement.executeUpdate() == 1
  }

  /**
   * Loads the most recent version of the audits for this session.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  override def loadAudits(sessionId: UUID): Option[List[AuditSessionRecord]] = {
    // Table --> |SESSION_ID|DATE|RUN_ID|CONVERTOR|AUDIT_KEY|START_TIME|END_TIME|DURATION|STATE
    val sharedWhere = s"WHERE SESSION_ID = '${sessionId.toString}'"
    val results = connection.prepareStatement(s"SELECT * FROM AUDITS $sharedWhere").executeQuery()
    val list = Iterator.from(0).takeWhile(_ => results.next()).map(_ => {
      AuditSessionRecord(UUID.fromString(results.getString("SESSION_ID")),
        new Date(results.getLong("DATE")),
        results.getInt("RUN_ID"),
        readBlobData(results),
        results.getString("CONVERTOR"),
        results.getString("AUDIT_KEY"),
        results.getLong("START_TIME"),
        results.getLong("END_TIME"),
        results.getLong("DURATION"))
    }).toList
    if (list.nonEmpty) {
      Some(list)
    } else {
      None
    }
  }

  /**
   * Saves a step result.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if it is saved.
   */
  override def saveStepResult(sessionRecord: StepResultSessionRecord): Boolean = {
    // Table --> |SESSION_ID|DATE|RUN_ID|CONVERTOR|RESULT_KEY|NAME|STATE
    val sharedWhere =
      s"""WHERE SESSION_ID = '${sessionRecord.sessionId}'
         |AND RESULT_KEY = '${sessionRecord.resultKey}'
         |AND NAME = '${sessionRecord.name}'""".stripMargin
    val results = connection.prepareStatement(s"SELECT * FROM STEP_RESULTS $sharedWhere").executeQuery()
    val statement = if (results.next()) {
      val setClause = s"RUN_ID = ${sessionRecord.runId}, STATE = ?, DATE = ${sessionRecord.date.getTime}"
      connection.prepareStatement(s"update STEP_RESULTS set $setClause $sharedWhere ")
    } else {
      val valuesClause =
        s"""'${sessionRecord.sessionId}', ${sessionRecord.date.getTime},
           |${sessionRecord.runId}, '${sessionRecord.convertor}',
           |'${sessionRecord.resultKey}', '${sessionRecord.name}', ?""".stripMargin
      connection.prepareStatement(s"INSERT INTO STEP_RESULTS VALUES($valuesClause)")
    }
    statement.setBlob(1, new ByteArrayInputStream(sessionRecord.state), sessionRecord.state.length)
    statement.executeUpdate() == 1
  }

  /**
   * Loads a list of step result records.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  override def loadStepResults(sessionId: UUID): Option[List[StepResultSessionRecord]] = {
    // Table --> |SESSION_ID|DATE|RUN_ID|CONVERTOR|RESULT_KEY|NAME|STATE
    val sharedWhere = s"WHERE SESSION_ID = '${sessionId.toString}'"
    val results = connection.prepareStatement(s"SELECT * FROM STEP_RESULTS $sharedWhere").executeQuery()
    val list = Iterator.from(0).takeWhile(_ => results.next()).map(_ => {
      StepResultSessionRecord(UUID.fromString(results.getString("SESSION_ID")),
        new Date(results.getLong("DATE")),
        results.getInt("RUN_ID"),
        readBlobData(results),
        results.getString("CONVERTOR"),
        results.getString("RESULT_KEY"),
        results.getString("NAME"))
    }).toList
    if (list.nonEmpty) {
      Some(list)
    } else {
      None
    }
  }

  /**
   * Saves an element of the globals.
   *
   * @param sessionRecord The record containing the state data.
   * @return true if it is saved.
   */
  override def saveGlobal(sessionRecord: GlobalSessionRecord): Boolean = {
    // Table --> |SESSION_ID|DATE|RUN_ID|CONVERTOR|RESULT_KEY|NAME|STATE
    val sharedWhere =
      s"""WHERE SESSION_ID = '${sessionRecord.sessionId}'
         |AND RESULT_KEY = '${sessionRecord.resultKey}'
         |AND NAME = '${sessionRecord.globalName}'""".stripMargin
    val results = connection.prepareStatement(s"SELECT * FROM GLOBALS $sharedWhere").executeQuery()
    val existingRecord = results.next()
    if (existingRecord && util.Arrays.equals(readBlobData(results), sessionRecord.state)) {
      true
    } else {
      val statement = if (existingRecord) {
        val setClause = s"RUN_ID = ${sessionRecord.runId}, STATE = ?, DATE = ${sessionRecord.date.getTime}"
        connection.prepareStatement(s"update GLOBALS set $setClause $sharedWhere ")
      } else {
        val valuesClause =
          s"""'${sessionRecord.sessionId}', ${sessionRecord.date.getTime},
             |${sessionRecord.runId}, '${sessionRecord.convertor}',
             |'${sessionRecord.resultKey}', '${sessionRecord.globalName}', ?""".stripMargin
        connection.prepareStatement(s"INSERT INTO GLOBALS VALUES($valuesClause)")
      }
      statement.setBlob(1, new ByteArrayInputStream(sessionRecord.state))
      statement.executeUpdate() == 1
    }
  }

  /**
   * Loads a list of global items.
   *
   * @param sessionId The unique session id.
   * @return An optional list of session records.
   */
  override def loadGlobals(sessionId: UUID): Option[List[GlobalSessionRecord]] = {
    // Table --> |SESSION_ID|DATE|RUN_ID|CONVERTOR|RESULT_KEY|NAME|STATE
    val sharedWhere = s"WHERE SESSION_ID = '${sessionId.toString}'"
    val results = connection.prepareStatement(s"SELECT * FROM GLOBALS $sharedWhere").executeQuery()
    val list = Iterator.from(0).takeWhile(_ => results.next()).map(_ => {
      GlobalSessionRecord(UUID.fromString(results.getString("SESSION_ID")),
        new Date(results.getLong("DATE")),
        results.getInt("RUN_ID"),
        readBlobData(results),
        results.getString("CONVERTOR"),
        results.getString("RESULT_KEY"),
        results.getString("NAME"))
    }).toList
    if (list.nonEmpty) {
      Some(list)
    } else {
      None
    }
  }

  private def readBlobData(results: ResultSet) = {
    val blob = results.getBlob("STATE")
    if (Option(blob).isDefined) {
      blob.getBytes(1, blob.length().toInt)
    } else {
      None.orNull
    }
  }
}
