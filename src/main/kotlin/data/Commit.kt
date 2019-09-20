package data

import db.table.CommitTable
import org.jetbrains.exposed.sql.ResultRow
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat


data class Commit(
  val apkVersion: Long,
  val commitHash: String,
  val committedAt: DateTime,
  val description: String
) {
  val apkUuid = String.format("%d_%s", apkVersion, commitHash)

  fun asString(): String {
    return String.format("%s; %s; %s", commitHash, COMMIT_DATE_TIME_PRINTER.print(committedAt), description)
  }

  fun validate(): Boolean {
    if (commitHash.isBlank() || commitHash.length > MAX_HASH_LENGTH) {
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

    return other.apkVersion == this.apkVersion
      && other.commitHash == this.commitHash
  }

  override fun hashCode(): Int {
    return 31 * apkVersion.hashCode() +
      commitHash.hashCode()
  }

  companion object {
    const val MAX_DESCRIPTION_LENGTH = 1024
    const val MAX_UUID_LENGTH = 96
    const val MAX_HASH_LENGTH = 64

    val COMMIT_DATE_TIME_PARSER = ISODateTimeFormat.dateTimeParser()
    val COMMIT_DATE_TIME_PRINTER = DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral('T')
      .append(ISODateTimeFormat.hourMinuteSecond())
      .appendTimeZoneOffset(null, true, 2, 2)
      .toFormatter()

    fun fromResultRow(resultRow: ResultRow): Commit {
      return Commit(
        resultRow[CommitTable.apkVersion],
        resultRow[CommitTable.hash],
        resultRow[CommitTable.committedAt],
        resultRow[CommitTable.description]
      )
    }
  }

}