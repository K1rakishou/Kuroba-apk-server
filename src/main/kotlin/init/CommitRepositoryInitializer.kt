package init

import data.ApkFileName
import data.CommitFileName
import fs.FileSystem
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.CommitRepository
import server.FatalHandlerException
import server.ServerSettings
import java.io.File
import java.nio.file.Paths

open class CommitRepositoryInitializer : Initializer, KoinComponent {
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

    var commitsWithOldFormatterTime = 0

    for ((_, commitFileName) in files) {
      val commitsFilePath =
        Paths.get(serverSettings.apksDir.absolutePath, commitFileName.formatFileName()).toFile().absolutePath

      val readFileResult = fileSystem.readFileAsStringAsync(commitsFilePath)
      if (readFileResult.isFailure) {
        logger.error("Couldn't read commit file $commitsFilePath")
        return Result.failure(readFileResult.exceptionOrNull()!!)
      }

      val commitsFileText = readFileResult.getOrNull()!!
      if (containsOldFormattedTime(commitsFileText)) {
        ++commitsWithOldFormatterTime
      }

      val insertResult = commitRepository.insertCommits(commitFileName.apkVersion, commitsFileText)
      if (insertResult.isFailure) {
        logger.error("Couldn't insert read from the file commits")
        return Result.failure(insertResult.exceptionOrNull()!!)
      }
    }

    if (commitsWithOldFormatterTime == 0) {
      throw RuntimeException("There are no commits with old time format left, time to remove the hack!")
    }

    logger.info("Restored ${files.size} files with commits")
    return Result.success(Unit)
  }

  private fun containsOldFormattedTime(commitsFileText: String): Boolean {
    val splitText = commitsFileText.split(';')
    if (splitText.size < 2) {
      return false
    }

    return splitText[1].endsWith("UTC")
  }

  private suspend fun splitFiles(files: List<String>): List<Pair<ApkFileName, CommitFileName>> {
    if (files.isEmpty()) {
      return emptyList()
    }

    logger.info("splitFiles() got ${files.size} files to split")

    val apkFilePathList = mutableSetOf<String>()
    val commitFiles = mutableSetOf<String>()

    for (file in files) {
      when {
        file.endsWith("_commits.txt") -> commitFiles += file
        file.endsWith(".apk") -> apkFilePathList += file
        else -> logger.error("Unknown file: $file")
      }
    }

    if (apkFilePathList.size != commitFiles.size) {
      throw FatalHandlerException("apkFilePathList.size (${apkFilePathList.size}) != commitFiles.size (${commitFiles.size})")
    }

    val resultFiles = mutableListOf<Pair<ApkFileName, CommitFileName>>()
    val badCommitFiles = mutableSetOf<String>()

    for (apkFilePath in apkFilePathList) {
      val apkFileName = ApkFileName.fromString(apkFilePath)
      if (apkFileName == null) {
        logger.error("Bad apk file path: $apkFilePath")
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

        if (apkFileName.apkVersion == commitFileName.apkVersion &&
          apkFileName.commitHash == commitFileName.commitHash
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

    // Ascending order, because that's how we are receiving them from the CI server
    return resultFiles.sortedBy { (apkFileName, _) -> apkFileName.uploadedOn }
  }

}