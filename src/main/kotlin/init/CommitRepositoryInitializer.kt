package init

import ServerSettings
import data.ApkFileName
import data.CommitFileName
import fs.FileSystem
import io.vertx.core.logging.LoggerFactory
import org.koin.core.KoinComponent
import org.koin.core.inject
import repository.CommitRepository
import java.io.File
import java.nio.file.Paths

class CommitRepositoryInitializer : Initializer, KoinComponent {
  private val logger = LoggerFactory.getLogger(CommitRepositoryInitializer::class.java)

  private val serverSettings by inject<ServerSettings>()
  private val fileSystem by inject<FileSystem>()
  private val commitRepository by inject<CommitRepository>()

  override suspend fun init(): Result<Unit> {
    logger.info("Initializing CommitRepository")
    val result = restoreCommits()
    logger.info("done")

    return result
  }

  private suspend fun restoreCommits(): Result<Unit> {
    val enumerateFilesResult = fileSystem.enumerateFilesAsync(serverSettings.apksDir.absolutePath)
    if (enumerateFilesResult.isFailure) {
      logger.error("Couldn't enumerate files in the apk directory")
      return Result.failure(enumerateFilesResult.exceptionOrNull()!!)
    }

    val files = splitFiles(enumerateFilesResult.getOrNull()!!)
    if (files.isEmpty()) {
      logger.info("splitFiles() returned empty file map")
      return Result.success(Unit)
    }

    for ((_, commitFileName) in files) {
      val commitsFilePath =
        Paths.get(serverSettings.apksDir.absolutePath, commitFileName.formatFileName()).toFile().absolutePath

      val readFileResult = fileSystem.readFileAsStringAsync(commitsFilePath)
      if (readFileResult.isFailure) {
        logger.error("Couldn't read commit file $commitsFilePath")
        return Result.failure(readFileResult.exceptionOrNull()!!)
      }

      val insertResult = commitRepository.insertNewCommits(commitFileName.apkVersion, readFileResult.getOrNull()!!)
      if (insertResult.isFailure) {
        logger.error("Couldn't insert read from the file commits")
        return Result.failure(insertResult.exceptionOrNull()!!)
      }
    }

    logger.info("Restored ${files.size} files with commits")
    return Result.success(Unit)
  }

  private suspend fun splitFiles(files: List<String>): List<Pair<ApkFileName, CommitFileName>> {
    if (files.isEmpty()) {
      return emptyList()
    }

    logger.info("splitFiles() got ${files.size} files to split")

    val apkFiles = mutableSetOf<String>()
    val commitFiles = mutableSetOf<String>()

    for (file in files) {
      when {
        file.endsWith("_commits.txt") -> commitFiles += file
        file.endsWith(".apk") -> apkFiles += file
        else -> logger.error("Unknown file: $file")
      }
    }

    if (apkFiles.size != commitFiles.size) {
      throw RuntimeException("apkFiles.size (${apkFiles.size}) != commitFiles.size (${commitFiles.size})")
    }

    val resultFiles = mutableListOf<Pair<ApkFileName, CommitFileName>>()
    val badCommitFiles = mutableSetOf<String>()

    for (apkFile in apkFiles) {
      val apkFileNameString = apkFile.split(File.separator).lastOrNull()
      if (apkFileNameString == null) {
        logger.error("Couldn't extract file name from file path: $apkFile")
        continue
      }

      val apkFileName = ApkFileName.fromString(apkFileNameString)
      if (apkFileName == null) {
        logger.error("Bad apk file: $apkFile")
        continue
      }

      for (commitFile in commitFiles) {
        if (commitFile in badCommitFiles) {
          continue
        }

        val commitFileNameString = commitFile.split(File.separator).lastOrNull()
        if (commitFileNameString == null) {
          logger.error("Couldn't extract commit name from file path: $commitFile")
          continue
        }

        val commitFileName = CommitFileName.fromString(commitFileNameString)
        if (commitFileName == null) {
          logger.error("Bad commit file: $commitFile")
          badCommitFiles += commitFile
          continue
        }

        if (apkFileName.apkVersion.version == commitFileName.apkVersion.version &&
          apkFileName.commitHash.hash == commitFileName.commitHash.hash
        ) {
          if (apkFileName.getUuid() != commitFileName.getUuid()) {
            badCommitFiles += commitFile

            logger.error(
              "ApkFileName and CommitFileName both have the same apkVersion and hash but have different uuids! " +
                "apkFileName = $apkFileName, commitFileName = $commitFileName"
            )
            continue
          }

          resultFiles += Pair(apkFileName, commitFileName)
          break
        }
      }
    }

    return resultFiles.sortedBy { (apkFileName, _) -> apkFileName.committedAt }
  }

}