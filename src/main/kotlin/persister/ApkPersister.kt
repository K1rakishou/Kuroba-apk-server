package persister

import ServerSettings
import ServerVerticle
import data.Apk
import data.ApkFileName
import data.Commit
import fs.FileSystem
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.FileUpload
import org.joda.time.DateTime
import org.koin.core.KoinComponent
import org.koin.core.inject
import repository.CommitRepository
import java.nio.file.Paths

open class ApkPersister : KoinComponent {
  private val logger = LoggerFactory.getLogger(ApkPersister::class.java)

  private val commitsRepository by inject<CommitRepository>()
  private val fileSystem by inject<FileSystem>()
  private val serverSettings by inject<ServerSettings>()

  open suspend fun store(
    apkFile: FileUpload,
    apkVersion: Long,
    parsedCommits: List<Commit>,
    date: DateTime
  ): Result<Unit> {
    require(parsedCommits.isNotEmpty()) { "store() parsed commits must not be empty" }

    val apkSize = apkFile.size()
    val latestCommit = parsedCommits.first()
    val getFullPathResult = getFullPath(apkVersion, latestCommit, date)

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

  suspend fun removeApks(apks: List<Apk>): Result<Unit> {
    require(apks.isNotEmpty()) { "removeApks() apks must not be empty" }

    val getCommitsResult = commitsRepository.getHeadCommits(apks)
    if (getCommitsResult.isFailure) {
      logger.error("Couldn't get head commits")
      return Result.failure(getCommitsResult.exceptionOrNull()!!)
    }

    val headCommits = getCommitsResult.getOrNull()!!
      .associateBy { commit -> commit.apkUuid }

    for (apk in apks) {
      val headCommit = headCommits[apk.apkUuid]
        ?: continue

      check(apk.apkVersion == headCommit.apkVersion) {
        "ApkVersions are not the same for apk and headCommit (${apk.apkVersion}, ${headCommit.apkVersion})"
      }

      val apkUuid = ApkFileName.getUuid(
        headCommit.apkVersion,
        headCommit.commitHash
      )

      val findFileResult = fileSystem.findApkFileAsync(
        serverSettings.apksDir.absolutePath,
        apkUuid
      )

      if (findFileResult.isFailure) {
        logger.error("findFileAsync() returned exception")
        return Result.failure(findFileResult.exceptionOrNull()!!)
      }

      val filePath = findFileResult.getOrNull()
        // Does not exist
        ?: continue

      val removeResult = fileSystem.removeFileAsync(filePath)
      if (removeResult.isFailure) {
        logger.error("Error while trying to remove file from the disk, filePath = ${filePath}")
        return Result.failure(removeResult.exceptionOrNull()!!)
      }
    }

    return Result.success(Unit)
  }

  private fun getFullPath(apkVersion: Long, latestCommit: Commit, now: DateTime): Result<String> {
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