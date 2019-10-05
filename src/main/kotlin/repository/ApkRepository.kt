package repository

import data.Apk
import db.ApkTable
import extensions.selectFilterDuplicates
import io.vertx.core.logging.LoggerFactory
import org.jetbrains.exposed.sql.*

open class ApkRepository : BaseRepository() {
  private val logger = LoggerFactory.getLogger(ApkRepository::class.java)

  suspend fun insertApks(apks: List<Apk>): Result<Unit> {
    if (apks.isEmpty()) {
      return Result.success(Unit)
    }

    return dbWrite {
      apks.chunked(CHUNK_SIZE)
        .map { chunk ->
          val groupUuidList = chunk
            .map { apk -> apk.apkUuid }
            .distinct()
          val apkPathList = chunk.map { apk -> apk.apkFullPath }

          return@map ApkTable.selectFilterDuplicates(chunk, {
            ApkTable.groupUuid.inList(groupUuidList) and
              ApkTable.apkFullPath.inList(apkPathList)
          }, { resultRow ->
            Apk.fromResultRow(resultRow)
          })
        }
        .forEach { chunk ->
          ApkTable.batchInsert(chunk) { apk ->
            this[ApkTable.groupUuid] = apk.apkUuid
            this[ApkTable.apkFullPath] = apk.apkFullPath
            this[ApkTable.uploadedOn] = apk.uploadedOn
          }
        }

      return@dbWrite
    }
  }

  suspend fun getApkListPaged(from: Int, count: Int): Result<List<Apk>> {
    return dbRead {
      ApkTable.selectAll()
        .orderBy(ApkTable.id, SortOrder.DESC)
        .limit(count, from)
        .map { resultRow -> Apk.fromResultRow(resultRow) }
    }
  }

  suspend fun getTotalApksCount(): Result<Int> {
    return dbRead { ApkTable.selectAll().count() }
  }

  suspend fun removeApk(apk: Apk): Result<Unit> {
    return dbWrite {
      ApkTable.deleteWhere {
        ApkTable.groupUuid.eq(apk.apkUuid) and
          ApkTable.apkFullPath.eq(apk.apkFullPath)
      }

      return@dbWrite
    }
  }

  companion object {
    private const val CHUNK_SIZE = 500
  }
}