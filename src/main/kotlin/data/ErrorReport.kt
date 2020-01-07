package data

import data.json.ErrorReportJsonData
import data.json.SerializedErrorReport
import db.ReportTable
import extensions.trimEndIfLongerThan
import okio.ByteString
import org.jetbrains.exposed.sql.ResultRow
import org.joda.time.DateTime

data class ErrorReport(
  val buildFlavor: String,
  val versionName: String,
  val title: String,
  val description: String,
  val logs: String?,
  val reportedAt: DateTime
) {

  fun getHash(): String {
    val str = buildString {
      append(buildFlavor)
      append(versionName)
      append(title)
      append(description)

      if (logs != null) {
        append(logs)
      }
    }

    return ByteString.encodeUtf8(str).md5().hex()
  }

  companion object {
    const val MAX_BUILD_FLAVOR_LENGTH = 32
    const val MAX_VERSION_NAME_LENGTH = 128
    const val MAX_TITLE_LENGTH = 512
    const val MAX_DESCRIPTION_LENGTH = 8192
    const val MAX_LOGS_LENGTH = 65535

    fun fromJsonData(errorReportJsonData: ErrorReportJsonData, time: DateTime): ErrorReport {
      return ErrorReport(
        errorReportJsonData.buildFlavor.trimEndIfLongerThan(MAX_BUILD_FLAVOR_LENGTH),
        errorReportJsonData.versionName.trimEndIfLongerThan(MAX_VERSION_NAME_LENGTH),
        errorReportJsonData.title.trimEndIfLongerThan(MAX_TITLE_LENGTH),
        errorReportJsonData.description.trimEndIfLongerThan(MAX_DESCRIPTION_LENGTH),
        errorReportJsonData.logs?.trimEndIfLongerThan(MAX_LOGS_LENGTH),
        time
      )
    }

    fun fromResultRow(resultRow: ResultRow): ErrorReport {
      return ErrorReport(
        resultRow[ReportTable.buildFlavor],
        resultRow[ReportTable.versionName],
        resultRow[ReportTable.title],
        resultRow[ReportTable.description],
        resultRow[ReportTable.logs],
        resultRow[ReportTable.reportedAt]
      )
    }

    fun fromSerializedErrorReport(serializedErrorReport: SerializedErrorReport): ErrorReport {
      return ErrorReport(
        serializedErrorReport.buildFlavor,
        serializedErrorReport.versionName,
        serializedErrorReport.title,
        serializedErrorReport.description,
        serializedErrorReport.logs,
        serializedErrorReport.reportedAt
      )
    }
  }

}