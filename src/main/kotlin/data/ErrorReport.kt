package data

import data.json.ErrorReportJsonData
import data.json.SerializedErrorReport
import db.ReportTable
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.exposed.sql.ResultRow
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat

data class ErrorReport(
  val buildFlavor: String,
  val versionName: String,
  val osInfo: String,
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

    return str.encodeUtf8().md5().hex()
  }

  override fun toString(): String {
    return "ErrorReport(buildFlavor='$buildFlavor', versionName='$versionName', title='$title', description='$description', logs=$logs, reportedAt=$reportedAt)"
  }

  companion object {
    const val MAX_BUILD_FLAVOR_LENGTH = 32
    const val MAX_VERSION_NAME_LENGTH = 128
    const val MAX_OS_INFO_LENGTH = 128
    const val MAX_TITLE_LENGTH = 512
    const val MAX_DESCRIPTION_LENGTH = 8192
    const val MAX_LOGS_LENGTH = 128 * 1024

    val REPORT_DATE_TIME_PRINTER = DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral(' ')
      .append(ISODateTimeFormat.hourMinuteSecond())
      .appendLiteral(" UTC")
      .toFormatter()
      .withZoneUTC()

    fun fromJsonData(errorReportJsonData: ErrorReportJsonData, time: DateTime): ErrorReport {
      return ErrorReport(
        errorReportJsonData.buildFlavor.takeLast(MAX_BUILD_FLAVOR_LENGTH),
        errorReportJsonData.versionName.takeLast(MAX_VERSION_NAME_LENGTH),
        errorReportJsonData.osInfo.takeLast(MAX_OS_INFO_LENGTH),
        errorReportJsonData.title.takeLast(MAX_TITLE_LENGTH),
        errorReportJsonData.description.takeLast(MAX_DESCRIPTION_LENGTH),
        errorReportJsonData.logs?.takeLast(MAX_LOGS_LENGTH),
        time
      )
    }

    fun fromResultRow(resultRow: ResultRow): ErrorReport {
      return ErrorReport(
        resultRow[ReportTable.buildFlavor],
        resultRow[ReportTable.versionName],
        resultRow[ReportTable.osInfo],
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
        serializedErrorReport.osInfo,
        serializedErrorReport.title,
        serializedErrorReport.description,
        serializedErrorReport.logs,
        serializedErrorReport.reportedAt
      )
    }
  }

}