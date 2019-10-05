package db

import data.Commit
import org.jetbrains.exposed.sql.Table

object CommitTable : Table("commit_table") {
  val uuid = varchar(Field.UUID, Commit.MAX_UUID_LENGTH).primaryKey()
  val groupUuid = varchar(Field.GROUP_UUID, Commit.MAX_UUID_LENGTH).index(Index.GROUP_UUID)
  val hash = varchar(Field.HASH, Commit.MAX_HASH_LENGTH).index(Index.HASH)
  val apkVersion = long(Field.APK_VERSION).index(Index.APK_VERSION)
  val committedAt = datetime(Field.COMMITTED_AT).index(Index.COMMITTED_AT)
  val description = varchar(Field.COMMIT_DESCRIPTION, Commit.MAX_DESCRIPTION_LENGTH)
  val head = bool(Field.HEAD)

  object Field {
    const val UUID = "commit_uuid"
    const val GROUP_UUID = "commit_group_uuid"
    const val HASH = "commit_hash"
    const val APK_VERSION = "apk_version"
    const val COMMITTED_AT = "committed_at"
    const val COMMIT_DESCRIPTION = "commit_description"
    const val HEAD = "head"
  }

  object Index {
    const val GROUP_UUID = "commit_group_uuid_index"
    const val HASH = "commit_hash_index"
    const val APK_VERSION = "apk_version_index"
    const val COMMITTED_AT = "committed_at_index"
  }
}