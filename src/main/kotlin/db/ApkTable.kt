package db

import data.Apk
import data.Commit
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object ApkTable : Table("apk_table") {
  val id = long(Field.ID).autoIncrement().primaryKey()
  val groupUuid = (varchar(Field.GROUP_UUID, Commit.MAX_UUID_LENGTH)
    .references(CommitTable.groupUuid, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE))
    .index(Index.GROUP_UUID)
  val apkFullPath = varchar(Field.APK_FULL_PATH, Apk.APK_MAX_PATH_LENGTH)
  val uploadedOn = datetime(Field.UPLOADED_ON).index(Index.UPLOADED_ON)

  object Field {
    const val ID = "id"
    const val GROUP_UUID = "commit_group_uuid"
    const val APK_FULL_PATH = "apk_full_path"
    const val UPLOADED_ON = "uploaded_on"
  }

  object Index {
    const val GROUP_UUID = "commit_group_uuid_index"
    const val UPLOADED_ON = "uploaded_on_index"
  }
}