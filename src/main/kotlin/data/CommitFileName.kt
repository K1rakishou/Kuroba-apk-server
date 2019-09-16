package data

import java.util.regex.Pattern

data class CommitFileName(
  val apkVersion: ApkVersion,
  val commitHash: CommitHash
) {

  fun getUuid(): String {
    return String.format(
      COMMITS_FILE_FORMAT,
      apkVersion.version,
      commitHash.hash
    )
  }

  companion object {
    // first %d - apk version
    // %s - commit hash
    const val COMMITS_FILE_FORMAT = "%d_%s_commits"
    val COMMITS_FILE_NAME_PATTERN = Pattern.compile("(\\d+)_([0-9a-f]{5,40})_commits\\.txt")

    fun formatUuid(apkVersion: ApkVersion, commitHash: CommitHash): String {
      return String.format(
        COMMITS_FILE_FORMAT,
        apkVersion.version,
        commitHash.hash
      )
    }

    fun fromString(fullName: String): CommitFileName? {
      val matcher = COMMITS_FILE_NAME_PATTERN.matcher(fullName)
      if (!matcher.matches()) {
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