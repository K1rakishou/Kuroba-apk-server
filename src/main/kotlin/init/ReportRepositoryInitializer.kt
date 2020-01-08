package init

import data.ErrorReport
import fs.FileSystem
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.ReportRepository
import server.ServerSettings
import service.JsonConverter

class ReportRepositoryInitializer : Initializer, KoinComponent {
  private val logger = LoggerFactory.getLogger(ReportRepositoryInitializer::class.java)

  private val serverSettings by inject<ServerSettings>()
  private val fileSystem by inject<FileSystem>()
  private val jsonConverter by inject<JsonConverter>()
  private val reportRepository by inject<ReportRepository>()

  override suspend fun init(): Result<Unit> {
    logger.info("Initializing ReportRepository")
    val result = restoreReports()
    logger.info("done")

    return result
  }

  private suspend fun restoreReports(): Result<Unit> {
    val enumerateFilesResult = fileSystem.enumerateFilesAsync(serverSettings.reportsDir.absolutePath)
    if (enumerateFilesResult.isFailure) {
      logger.error("Couldn't enumerate files in the reports directory")
      return Result.failure(enumerateFilesResult.exceptionOrNull()!!)
    }

    val result = enumerateFilesResult.getOrNull()
    if (result == null) {
      // No reports
      return Result.success(Unit)
    }

    val reports = mutableListOf<ErrorReport>()

    for (filePath in result) {
      val readFileResult = fileSystem.readFileAsStringAsync(filePath)
      if (readFileResult.isFailure) {
        logger.error("Couldn't read report file $filePath")
        continue
      }

      val reportFileJson = readFileResult.getOrNull()
      if (reportFileJson.isNullOrEmpty()) {
        logger.error("File $filePath is empty")
        continue
      }

      reports += try {
        ErrorReport.fromSerializedErrorReport(
          jsonConverter.fromJson(reportFileJson)
        )
      } catch (error: Throwable) {
        logger.error("Couldn't convert SerializedErrorReport to ErrorReport", error)
        continue
      }
    }

    val initResult = reportRepository.init(reports)
    if (initResult.isFailure) {
      return initResult
    }

    logger.info("Restored ${reports.size} reports")
    return Result.success(Unit)
  }
}