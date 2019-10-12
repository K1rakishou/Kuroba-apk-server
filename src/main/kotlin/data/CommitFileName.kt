package data

import java.util.regex.Pattern

data class CommitFileName(
  val apkVersion: Long,
  val commitHash: String
) {

  fun getUuid(): String {
    return String.format(
      COMMITS_UUID_FORMAT,
      apkVersion,
      commitHash
    )
  }

  fun formatFileName(): String = formatFileName(apkVersion, commitHash) + COMMIT_EXTENSION

  override fun toString(): String {
    return getUuid()
  }

  companion object {
    const val COMMIT_EXTENSION = ".txt"

    // %d - apk version
    // %s - commit hash
    const val COMMITS_UUID_FORMAT = "%d_%s"
    const val COMMITS_FILE_FORMAT = "%d_%s_commits"
    val COMMITS_FILE_NAME_PATTERN = Pattern.compile("(\\d+)_([0-9a-f]{5,40})_commits\\$COMMIT_EXTENSION$")
    private val COMMIT_HASH_PATTERN = Pattern.compile("[0-9a-f]{5,40}")

    fun formatFileName(apkVersion: Long, commitHash: String): String {
      return String.format(
        COMMITS_FILE_FORMAT,
        apkVersion,
        commitHash
      )
    }

    fun fromString(fullName: String): CommitFileName? {
      val matcher = COMMITS_FILE_NAME_PATTERN.matcher(fullName)
      if (!matcher.find()) {
        return null
      }

      return try {
        CommitFileName(
          matcher.group(1).toLong(),
          matcher.group(2)
        )
      } catch (error: Throwable) {
        null
      }
    }

    fun tryGetUuid(commitFileNameString: String): String? {
      val matcher = COMMITS_FILE_NAME_PATTERN.matcher(commitFileNameString)
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

      return String.format(COMMITS_UUID_FORMAT, versionCode, commitHash)
    }
  }

}