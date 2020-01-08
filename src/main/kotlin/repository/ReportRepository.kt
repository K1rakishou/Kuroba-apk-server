package repository

import data.ErrorReport
import db.ReportTable
import dispatchers.DispatcherProvider
import extensions.selectFilterDuplicates
import org.jetbrains.exposed.sql.*
import util.TimeUtils

class ReportRepository(
  private val timeUtils: TimeUtils,
  dispatcherProvider: DispatcherProvider
) : BaseRepository(dispatcherProvider) {

  suspend fun init(reports: List<ErrorReport>): Result<Unit> {
    return dbWrite {
      reports.chunked(CHUNK_SIZE)
        .map { chunk ->
          val hashes = getHashes(chunk)

          return@map ReportTable.selectFilterDuplicates(chunk, {
            ReportTable.uuid.inList(hashes)
          }, { resultRow ->
            ErrorReport.fromResultRow(resultRow)
          }) to hashes
        }
        .forEach { (chunk, hashes) ->
          ReportTable.batchInsert(chunk.zip(hashes)) { (report, hash) ->
            this[ReportTable.uuid] = hash
            this[ReportTable.buildFlavor] = report.buildFlavor
            this[ReportTable.versionName] = report.versionName
            this[ReportTable.title] = report.title
            this[ReportTable.description] = report.description
            this[ReportTable.logs] = report.logs
            this[ReportTable.reportedAt] = report.reportedAt
          }
        }
    }
  }

  suspend fun storeReport(hash: String, report: ErrorReport): Result<Unit> {
    return dbWrite {
      val prev = ReportTable.select {
        ReportTable.uuid.eq(hash)
      }

      if (prev.count() > 0) {
        // Already inserted
        return@dbWrite
      }

      ReportTable.insert {
        it[uuid] = hash
        it[buildFlavor] = report.buildFlavor
        it[versionName] = report.versionName
        it[title] = report.title
        it[description] = report.description
        it[logs] = report.logs
        it[reportedAt] = report.reportedAt
      }
    }
  }

  suspend fun getAllReports(): Result<List<ErrorReport>> {
    return dbRead {
      ReportTable.selectAll()
        .orderBy(ReportTable.reportedAt, SortOrder.DESC)
        .map { resultRow -> ErrorReport.fromResultRow(resultRow) }
    }
  }

  suspend fun deleteReport(hash: String): Result<Int> {
    return dbWrite {
      ReportTable.deleteWhere {
        ReportTable.uuid.eq(hash)
      }
    }
  }

  suspend fun deleteReports(reports: List<ErrorReport>): Result<Int> {
    return dbWrite {
      val hash = reports.map { report -> report.getHash() }

      ReportTable.deleteWhere {
        ReportTable.uuid.inList(hash)
      }
    }
  }

  private fun getHashes(reports: List<ErrorReport>): List<String> {
    return reports.map { report -> report.getHash() }
  }

  companion object {
    private const val CHUNK_SIZE = 500
  }
}