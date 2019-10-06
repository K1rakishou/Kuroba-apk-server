package data

import db.ApkTable
import org.jetbrains.exposed.sql.ResultRow
import org.joda.time.DateTime

data class Apk(
  val apkUuid: String,
  val apkVersion: Long,
  val apkFullPath: String,
  val uploadedOn: DateTime
) {

  companion object {
    const val APK_MAX_PATH_LENGTH = 1024

    fun fromResultRow(resultRow: ResultRow): Apk {
      return Apk(
        resultRow[ApkTable.groupUuid],
        resultRow[ApkTable.apkVersion],
        resultRow[ApkTable.apkFullPath],
        resultRow[ApkTable.uploadedOn]
      )
    }
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

    other as Apk

    return other.apkUuid == this.apkUuid
      && other.apkFullPath == this.apkFullPath
  }

  override fun hashCode(): Int {
    return 31 * apkUuid.hashCode() +
      31 * apkVersion.hashCode() +
      31 * apkFullPath.hashCode()
  }

  override fun toString(): String {
    return String.format(
      "uuid = %s, apkVersion = %d, path = %s, uploadedOn = %s",
      apkUuid,
      apkVersion,
      apkFullPath,
      ApkFileName.APK_UPLOADED_ON_PRINTER.print(uploadedOn)
    )
  }
}