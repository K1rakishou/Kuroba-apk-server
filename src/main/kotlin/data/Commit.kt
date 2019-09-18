package data

import db.table.CommitTable
import org.jetbrains.exposed.sql.ResultRow
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

data class Commit(
  val apkVersion: ApkVersion,
  val commitHash: CommitHash,
  val committedAt: DateTime,
  val description: String
) {
  val apkUuid = String.format("%d_%s", apkVersion.version, commitHash.hash)

  fun asString(): String {
    return String.format("%s; %s; %s", commitHash.hash, COMMIT_DATE_TIME_FORMAT.print(committedAt), description)
  }

  fun validate(): Boolean {
    if (commitHash.hash.isBlank() || commitHash.hash.length > MAX_HASH_LENGTH) {
      return false
    }

    if (description.isBlank() || description.length > MAX_DESCRIPTION_LENGTH) {
      return false
    }

    return true
  }

  override fun equals(other: Any?): Boolean {
    if (other == null) {
      return false
    }

    if (other === this) {
      return true
    }

    if (other.javaClass != this.javaClass) {
      return false
    }

    other as Commit

    return other.apkVersion.version == this.apkVersion.version
      && other.commitHash.hash == this.commitHash.hash
  }

  override fun hashCode(): Int {
    return 31 * apkVersion.hashCode() +
      commitHash.hashCode()
  }

  companion object {
    const val MAX_DESCRIPTION_LENGTH = 1024
    const val MAX_UUID_LENGTH = 96
    const val MAX_HASH_LENGTH = 64
    val COMMIT_DATE_TIME_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")

    fun fromResultRow(resultRow: ResultRow): Commit {
      return Commit(
        ApkVersion(resultRow[CommitTable.apkVersion]),
        CommitHash(resultRow[CommitTable.hash]),
        resultRow[CommitTable.committedAt],
        resultRow[CommitTable.description]
      )
    }
  }

}

inline class ApkVersion(val version: Long)

inline class CommitHash(val hash: String)