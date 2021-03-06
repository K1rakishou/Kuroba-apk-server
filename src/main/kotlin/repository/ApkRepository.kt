package repository

import data.Apk
import db.ApkTable
import dispatchers.DispatcherProvider
import extensions.selectFilterDuplicates
import org.jetbrains.exposed.sql.*
import org.slf4j.LoggerFactory

open class ApkRepository(
  dispatcherProvider: DispatcherProvider
) : BaseRepository(dispatcherProvider) {
  private val logger = LoggerFactory.getLogger(ApkRepository::class.java)

  open suspend fun insertApks(apks: List<Apk>): Result<Unit> {
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
            this[ApkTable.apkVersion] = apk.apkVersion
            this[ApkTable.apkFullPath] = apk.apkFullPath
            this[ApkTable.uploadedOn] = apk.uploadedOn
            this[ApkTable.downloadedTimes] = apk.downloadedTimes
          }
        }

      return@dbWrite
    }
  }

  suspend fun getApkListPaged(from: Int, count: Int): Result<List<Apk>> {
    return dbRead {
      ApkTable.selectAll()
        .orderBy(ApkTable.uploadedOn, SortOrder.DESC)
        .limit(count, from)
        .map { resultRow -> Apk.fromResultRow(resultRow) }
    }
  }

  suspend fun getAllApks(): Result<List<Apk>> {
    return dbRead {
      ApkTable.selectAll()
        .map { resultRow -> Apk.fromResultRow(resultRow) }
    }
  }

  suspend fun getTotalApksCount(): Result<Int> {
    return dbRead { ApkTable.selectAll().count() }
  }

  suspend fun getLatestApk(): Result<Apk?> {
    return dbRead {
      ApkTable.selectAll()
        .orderBy(ApkTable.uploadedOn, SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.let { resultRow -> Apk.fromResultRow(resultRow) }
    }
  }

  suspend fun getOldestApks(count: Int): Result<List<Apk>> {
    return dbRead {
      ApkTable.selectAll()
        .orderBy(ApkTable.uploadedOn, SortOrder.ASC)
        .limit(count)
        .map { resultRow -> Apk.fromResultRow(resultRow) }
    }
  }

  suspend fun getApkDownloadTimes(apkUuidList: List<String>): Result<List<Pair<String, Int>>> {
    return dbRead {
      ApkTable.select { ApkTable.groupUuid.inList(apkUuidList) }
        .map { resultRow ->
          return@map Pair(
            resultRow[ApkTable.groupUuid],
            resultRow[ApkTable.downloadedTimes]
          )
        }
    }
  }

  suspend fun increaseDownloadCountForApk(apkUuid: String): Result<Unit> {
    return dbWrite {
      val storedApk = ApkTable.select { ApkTable.groupUuid.eq(apkUuid) }
        .firstOrNull()
        ?.let { resultRow -> Apk.fromResultRow(resultRow) }

      if (storedApk == null) {
        throw ApkNotFoundException(apkUuid)
      }

      ApkTable.update({ ApkTable.groupUuid.eq(apkUuid) }) {
        it[downloadedTimes] = storedApk.downloadedTimes + 1
      }

      return@dbWrite Unit
    }
  }

  open suspend fun removeApks(apks: List<Apk>): Result<Unit> {
    require(apks.isNotEmpty()) { "removeApks() apks must not be empty" }

    return dbWrite {
      val groupUuidSet = apks.map { apk -> apk.apkUuid }.toSet()
      val apkPathSet = apks.map { apk -> apk.apkFullPath }.toSet()

      if (groupUuidSet.size != apks.size) {
        logger.warn(
          "groupUuidSet.size (${groupUuidSet.size}) != apkPathSet.size (${apkPathSet.size})! " +
            "Something may go wrong after deletion!"
        )
      }

      ApkTable.deleteWhere {
        ApkTable.groupUuid.inList(groupUuidSet) and
          ApkTable.apkFullPath.inList(apkPathSet)
      }

      return@dbWrite
    }
  }

  suspend fun testGetAll(): Result<List<Apk>> {
    return dbRead {
      ApkTable.selectAll().map { resultRow -> Apk.fromResultRow(resultRow) }
    }
  }

  class ApkNotFoundException(apkUuid: String) :
    Exception("Apk with uuid $apkUuid was not found")

  companion object {
    private const val CHUNK_SIZE = 500
  }
}