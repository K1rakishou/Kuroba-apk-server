package data

import org.joda.time.DateTime
import java.util.regex.Pattern

data class ApkFileName private constructor(
  val apkVersion: ApkVersion,
  val commitHash: CommitHash,
  val committedAt: DateTime
) {

  fun getUuid(): String {
    return Companion.getUuid(apkVersion, commitHash)
  }

  override fun toString(): String {
    return getUuid()
  }

  companion object {
    // %d - apk version
    // %s - commit hash
    const val APK_UUID_FORMAT = "%d_%s"

    // first %d - apk version
    // %s - commit hash
    // second %d - time of the last commit in the commits group
    const val APK_FILE_FORMAT = "%d_%s_%d"
    val APK_FILE_NAME_PATTERN = Pattern.compile("(\\d+)_([0-9a-f]{5,40})_(\\d+)\\.apk")
    val APK_FILE_NAME_NO_TIME_PATTERN = Pattern.compile("(\\d+)_([0-9a-f]{5,40})\\.apk")
    private val COMMIT_HASH_PATTERN = Pattern.compile("[0-9a-f]{5,40}")

    fun formatFileName(apkVersion: ApkVersion, commitHash: CommitHash, committedAt: DateTime): String {
      return String.format(
        APK_FILE_FORMAT,
        apkVersion.version,
        commitHash.hash,
        committedAt.millis
      )
    }

    fun fromString(fullName: String): ApkFileName? {
      val matcher = APK_FILE_NAME_PATTERN.matcher(fullName)
      if (!matcher.find()) {
        return null
      }

      return try {
        ApkFileName(
          ApkVersion(matcher.group(1).toLong()),
          CommitHash(matcher.group(2)),
          DateTime(matcher.group(3).toLong())
        )
      } catch (error: Throwable) {
        null
      }
    }

    fun getUuid(apkVersion: ApkVersion, commitHash: CommitHash): String {
      return String.format(
        APK_UUID_FORMAT,
        apkVersion.version,
        commitHash.hash
      )
    }

    fun tryGetUuid(apkNameString: String): String? {
      val matcher = APK_FILE_NAME_NO_TIME_PATTERN.matcher(apkNameString)
      if (!matcher.find()) {
        return null
      }

      val versionCode = matcher.group(1).toLongOrNull()
      if (versionCode == null) {
        return null
      }

      val commitHash = matcher.group(2)
      if (!COMMIT_HASH_PATTERN.matcher(commitHash).matches()) {
        return null
      }

      return String.format(APK_UUID_FORMAT, versionCode, commitHash)
    }
  }

}