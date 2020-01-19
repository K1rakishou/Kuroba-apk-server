package data.json

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import data.ErrorReport
import org.joda.time.DateTime

@JsonClass(generateAdapter = true)
data class SerializedErrorReport(
  @Json(name = "build_flavor")
  val buildFlavor: String,
  @Json(name = "version_name")
  val versionName: String,
  @Json(name = "os_info")
  val osInfo: String,
  @Json(name = "report_title")
  val title: String,
  @Json(name = "report_description")
  val description: String,
  @Json(name = "report_logs")
  val logs: String?,
  @Json(name = "reported_at")
  val reportedAt: DateTime
) {
  companion object {
    fun fromErrorReport(errorReport: ErrorReport): SerializedErrorReport {
      return SerializedErrorReport(
        errorReport.buildFlavor,
        errorReport.versionName,
        errorReport.osInfo,
        errorReport.title,
        errorReport.description,
        errorReport.logs,
        errorReport.reportedAt
      )
    }
  }
}