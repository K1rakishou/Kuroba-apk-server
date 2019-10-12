package data.json

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApkUuidJsonData(
  @Json(name = "apk_version") val apkVersion: Long,
  @Json(name = "commit_hash") val commitHash: String
)