package db

import data.ErrorReport
import org.jetbrains.exposed.sql.Table

object ReportTable : Table("report_table") {
  val uuid = varchar(Field.UUID, 128).primaryKey()
  val buildFlavor = varchar(Field.BUILD_FLAVOR, ErrorReport.MAX_BUILD_FLAVOR_LENGTH)
  val versionName = varchar(Field.VERSION_NAME, ErrorReport.MAX_VERSION_NAME_LENGTH)
  val title = varchar(Field.TITLE, ErrorReport.MAX_TITLE_LENGTH)
  val description = varchar(Field.DESCRIPTION, ErrorReport.MAX_DESCRIPTION_LENGTH)
  val logs = varchar(Field.LOGS, ErrorReport.MAX_LOGS_LENGTH).nullable()
  val reportedAt = datetime(Field.REPORTED_AT).index(Index.REPORTED_AT)

  object Field {
    const val UUID = "report_uuid"
    const val BUILD_FLAVOR = "report_build_flavor"
    const val VERSION_NAME = "report_version_name"
    const val TITLE = "report_title"
    const val DESCRIPTION = "report_description"
    const val LOGS = "report_logs"
    const val REPORTED_AT = "report_reported_at"
  }

  object Index {
    const val REPORTED_AT = "report_reported_at_index"
  }
}