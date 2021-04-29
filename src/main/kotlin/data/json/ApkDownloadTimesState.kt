package data.json

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApkDownloadTimesState(
  @Json(name = "file_version") val fileVersion: Int = 1,
  @Json(name = "apk_download_times_list") val apkDownloadTimesList: List<ApkDownloadTimes>
)

@JsonClass(generateAdapter = true)
data class ApkDownloadTimes(
  @Json(name = "apkUuid") val apkUuid: String,
  @Json(name = "downloadedTimes") val downloadedTimes: Int
)