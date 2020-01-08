package persister

import data.ErrorReport
import data.json.SerializedErrorReport
import fs.FileSystem
import io.vertx.core.buffer.Buffer
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.ReportRepository
import server.ServerSettings
import service.JsonConverter
import java.nio.file.Paths

class ReportPersister : KoinComponent {
  private val logger = LoggerFactory.getLogger(ReportPersister::class.java)
  private val reportRepository by inject<ReportRepository>()
  private val serverSettings by inject<ServerSettings>()
  private val fileSystem by inject<FileSystem>()
  private val jsonConverter by inject<JsonConverter>()

  suspend fun storeReport(report: ErrorReport): Result<Unit> {
    val reportHash = report.getHash()

    val fullPath = Paths.get(
      serverSettings.reportsDir.absolutePath,
      "${reportHash}.txt"
    ).toFile().absolutePath

    val oldFileExistsResult = fileSystem.fileExistsAsync(fullPath)
    if (oldFileExistsResult.isFailure) {
      return Result.failure(oldFileExistsResult.exceptionOrNull()!!)
    }

    if (oldFileExistsResult.getOrNull()!!) {
      // Already exists
      return Result.success(Unit)
    }

    val reportResult = reportRepository.storeReport(reportHash, report)
    if (reportResult.isFailure) {
      return Result.failure(reportResult.exceptionOrNull()!!)
    }

    val createFileResult = fileSystem.createFile(fullPath)
    if (createFileResult.isFailure) {
      return Result.failure(createFileResult.exceptionOrNull()!!)
    }

    val json = try {
      jsonConverter.toJson(
        SerializedErrorReport.fromErrorReport(report)
      )
    } catch (error: Throwable) {
      return Result.failure(error)
    }

    val writeResult = fileSystem.writeFileAsync(fullPath, Buffer.buffer(json))
    if (writeResult.isFailure) {
      val deleteResult = reportRepository.deleteReport(reportHash)
      if (deleteResult.isFailure) {
        logger.error("Couldn't delete report file after unsuccessful write operation")
      }

      return Result.failure(writeResult.exceptionOrNull()!!)
    }

    return Result.success(Unit)
  }

  suspend fun deleteReport(reportHash: String): Result<Unit> {
    val repoDeleteResult = reportRepository.deleteReport(reportHash)
    if (repoDeleteResult.isFailure) {
      return Result.failure(repoDeleteResult.exceptionOrNull()!!)
    }

    val fullPath = Paths.get(
      serverSettings.reportsDir.absolutePath,
      "${reportHash}.txt"
    ).toFile().absolutePath

    val removeResult = fileSystem.removeFileAsync(fullPath)
    if (removeResult.isFailure) {
      return Result.failure(removeResult.exceptionOrNull()!!)
    }

    return Result.success(Unit)
  }

}