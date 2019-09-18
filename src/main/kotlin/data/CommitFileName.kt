package data

import java.util.regex.Pattern

data class CommitFileName(
  val apkVersion: ApkVersion,
  val commitHash: CommitHash
) {

  fun getUuid(): String {
    return String.format(
      COMMITS_UUID_FORMAT,
      apkVersion.version,
      commitHash.hash
    )
  }

  fun formatFileName(): String = formatFileName(apkVersion, commitHash) + ".txt"

  override fun toString(): String {
    return getUuid()
  }

  companion object {
    // %d - apk version
    // %s - commit hash
    const val COMMITS_UUID_FORMAT = "%d_%s"
    const val COMMITS_FILE_FORMAT = "%d_%s_commits"
    val COMMITS_FILE_NAME_PATTERN = Pattern.compile("(\\d+)_([0-9a-f]{5,40})_commits\\.txt$")

    fun formatFileName(apkVersion: ApkVersion, commitHash: CommitHash): String {
      return String.format(
        COMMITS_FILE_FORMAT,
        apkVersion.version,
        commitHash.hash
      )
    }

    fun fromString(fullName: String): CommitFileName? {
      val matcher = COMMITS_FILE_NAME_PATTERN.matcher(fullName)
      if (!matcher.find()) {
        return null
      }

      return try {
        CommitFileName(
          ApkVersion(matcher.group(1).toLong()),
          CommitHash(matcher.group(2))
        )
      } catch (error: Throwable) {
        null
      }
    }
  }

}