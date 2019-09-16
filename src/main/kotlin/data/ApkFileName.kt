package data

import java.util.regex.Pattern

data class ApkFileName private constructor(
  val apkVersion: ApkVersion,
  val commitHash: CommitHash
) {

  fun getUuid(): String {
    return String.format(
      APK_UUID_FORMAT,
      apkVersion.version,
      commitHash.hash
    )
  }

  companion object {
    // first %d - apk version
    // %s - commit hash
    const val APK_UUID_FORMAT = "%d_%s"
    val APK_FILE_NAME_PATTERN = Pattern.compile("(\\d+)_([0-9a-f]{5,40})\\.apk")

    fun formatUuid(apkVersion: ApkVersion, commitHash: CommitHash): String {
      return String.format(
        APK_UUID_FORMAT,
        apkVersion.version,
        commitHash.hash
      )
    }

    fun fromString(fullName: String): ApkFileName? {
      val matcher = APK_FILE_NAME_PATTERN.matcher(fullName)
      if (!matcher.matches()) {
        return null
      }

      return try {
        ApkFileName(
          ApkVersion(matcher.group(1).toLong()),
          CommitHash(matcher.group(2))
        )
      } catch (error: Throwable) {
        null
      }
    }
  }

}