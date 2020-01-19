package data.json

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ErrorReportJsonData(
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
  val logs: String?
)