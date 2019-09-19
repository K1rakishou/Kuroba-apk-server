package persister

import ServerSettings
import ServerVerticle
import data.ApkFileName
import data.ApkVersion
import data.Commit
import fs.FileSystem
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.FileUpload
import org.joda.time.DateTime
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.nio.file.Paths
import java.util.regex.Pattern

class ApkPersister : KoinComponent {
  private val logger = LoggerFactory.getLogger(ApkPersister::class.java)

  private val fileSystem by inject<FileSystem>()
  private val serverSettings by inject<ServerSettings>()

  suspend fun store(
    apkFile: FileUpload,
    apkVersion: ApkVersion,
    parsedCommits: List<Commit>
  ): Result<Unit> {
    require(parsedCommits.isNotEmpty())

    val now = DateTime.now()
    val apkSize = apkFile.size()
    val latestCommit = parsedCommits.first()
    val getFullPathResult = getFullPath(apkVersion, latestCommit, now)

    if (getFullPathResult.isFailure) {
      return Result.failure(getFullPathResult.exceptionOrNull()!!)
    }

    val destFilePath = getFullPathResult.getOrNull()!!
    val fileExistsResult = fileSystem.fileExistsAsync(destFilePath)
    if (fileExistsResult.isFailure) {
      logger.error("Error while trying to figure out whether an apk file exists or not, file path = ${destFilePath}")
      return Result.failure(fileExistsResult.exceptionOrNull()!!)
    }

    val exists = fileExistsResult.getOrNull()!!
    if (exists) {
      logger.error("Apk file already exists, full path = ${destFilePath}")
      return Result.failure(ApkFileAlreadyExists())
    }

    if (apkSize > ServerVerticle.MAX_APK_FILE_SIZE) {
      logger.error("File size is too big, actual = ${apkFile.size()}")
      return Result.failure(ApkFileIsTooBigException(apkSize))
    }

    val copyResult = fileSystem.copySourceFileToDestFileAsync(apkFile.uploadedFileName(), destFilePath)
    if (copyResult.isFailure) {
      logger.error(
        "Couldn't copy source file (${apkFile.uploadedFileName()}) into the destination file (${destFilePath})",
        copyResult.exceptionOrNull()!!
      )

      return Result.failure(CopyFileException())
    }

    return Result.success(Unit)
  }

  suspend fun remove(apkVersion: ApkVersion, parsedCommits: List<Commit>): Result<Unit> {
    require(parsedCommits.isNotEmpty())

    val latestCommit = parsedCommits.first()
    val apkUuid = ApkFileName.getUuid(apkVersion, latestCommit.commitHash)

    val findFileResult = fileSystem.findFileAsync(
      serverSettings.apksDir.absolutePath,
      Pattern.compile(".*($apkUuid)_(\\d+)\\.apk"))

    if (findFileResult.isFailure) {
      logger.error("findFileAsync() returned exception")
      return Result.failure(findFileResult.exceptionOrNull()!!)
    }

    val foundFiles = findFileResult.getOrNull()!!
    if (foundFiles.isEmpty()) {
      logger.info("No files were found with uuid ${apkUuid}, nothing to remove")
      return Result.success(Unit)
    }

    if (foundFiles.size > 1) {
      val message = "More than one file was found with uuid ${apkUuid}, count = ${foundFiles.size}"
      logger.error(message)
      return Result.failure(MoreThanOneFileWithTheSameUuidFound(apkUuid, foundFiles.size))
    }

    return fileSystem.removeFileAsync(foundFiles.first())
  }

  private fun getFullPath(apkVersion: ApkVersion, latestCommit: Commit, now: DateTime): Result<String> {
    val fileName = try {
      ApkFileName.formatFileName(
        apkVersion,
        latestCommit.commitHash,
        now
      )
    } catch (error: Throwable) {
      return Result.failure(error)
    }

    val path = Paths.get(serverSettings.apksDir.absolutePath, "${fileName}.apk").toFile().absolutePath
    return Result.success(path)
  }

  class MoreThanOneFileWithTheSameUuidFound(apkUuid: String, count: Int)
    : Exception("More than one file was found with uuid ${apkUuid}, count = ${count}")
  class ApkFileIsTooBigException(apkSize: Long)
    : Exception("Apk file is too big, size = $apkSize max = ${ServerVerticle.MAX_APK_FILE_SIZE}")
  class CopyFileException
    : Exception("Error while trying to copy source file into the destination file")
  class ApkFileAlreadyExists : Exception("Apk file already exists")
}