package persister

import data.Apk
import data.Commit
import data.CommitFileName
import fs.FileSystem
import io.vertx.core.buffer.Buffer
import io.vertx.core.logging.LoggerFactory
import org.koin.core.KoinComponent
import org.koin.core.inject
import parser.CommitParser
import repository.CommitRepository
import server.ServerSettings
import java.nio.file.Paths

open class CommitPersister : KoinComponent {
  private val logger = LoggerFactory.getLogger(CommitPersister::class.java)

  private val commitsRepository by inject<CommitRepository>()
  private val commitParser by inject<CommitParser>()
  private val serverSettings by inject<ServerSettings>()
  private val fileSystem by inject<FileSystem>()

  open suspend fun store(apkVersion: Long, parsedCommits: List<Commit>): Result<Unit> {
    require(parsedCommits.isNotEmpty()) { "store() parsed commits must not be empty" }

    val latestCommit = parsedCommits.first()
    val getFullPathResult = getFullPath(apkVersion, latestCommit)

    if (getFullPathResult.isFailure) {
      return Result.failure(getFullPathResult.exceptionOrNull()!!)
    }

    val fullPath = getFullPathResult.getOrNull()!!
    val fileExistsResult = fileSystem.fileExistsAsync(fullPath)
    if (fileExistsResult.isFailure) {
      logger.error("Error while trying to figure out whether a commits file exists or not, file path = ${fullPath}")
      return Result.failure(fileExistsResult.exceptionOrNull()!!)
    }

    val exists = fileExistsResult.getOrNull()!!
    if (exists) {
      logger.error("Commits file already exists, full path = ${fullPath}")
      return Result.failure(CommitsFileAlreadyExists())
    }

    val createFileResult = fileSystem.createFile(fullPath)
    if (createFileResult.isFailure) {
      logger.error("Error while trying to create a new json file on the disk, file path = ${fullPath}")
      return Result.failure(fileExistsResult.exceptionOrNull()!!)
    }

    val commitsString = commitParser.commitsToString(parsedCommits)

    val writeFileResult = fileSystem.writeFileAsync(
      fullPath,
      Buffer.buffer(commitsString)
    )

    if (writeFileResult.isFailure) {
      logger.error("Error while trying to write commits to a file, file path = ${fullPath}")
      return Result.failure(fileExistsResult.exceptionOrNull()!!)
    }

    return Result.success(Unit)
  }

  suspend fun removeCommitsByApkList(apks: List<Apk>): Result<Unit> {
    require(apks.isNotEmpty()) { "removeCommitsByApkList() apks must not be empty" }

    val getCommitsResult = commitsRepository.getHeadCommits(apks)
    if (getCommitsResult.isFailure) {
      logger.error("Couldn't get head commits")
      return Result.failure(getCommitsResult.exceptionOrNull()!!)
    }

    val headCommits = getCommitsResult.getOrNull()!!
      .associateBy { commit -> commit.apkUuid }
    check(headCommits.isNotEmpty()) { "headCommits must not be empty" }

    for (apk in apks) {
      val headCommit = headCommits[apk.apkUuid]
        ?: continue

      check(apk.apkVersion == headCommit.apkVersion) {
        "ApkVersions are not the same for apk and headCommit (${apk.apkVersion}, ${headCommit.apkVersion})"
      }

      val getFullPathResult = getFullPath(headCommit.apkVersion, headCommit)
      if (getFullPathResult.isFailure) {
        logger.error("Couldn't get full path")
        return Result.failure(getFullPathResult.exceptionOrNull()!!)
      }

      val filePath = getFullPathResult.getOrNull()!!
      val removeResult = fileSystem.removeFileAsync(filePath)
      if (removeResult.isFailure) {
        logger.error("Error while trying to remove file from the disk, filePath = ${filePath}")
        return Result.failure(removeResult.exceptionOrNull()!!)
      }
    }

    return Result.success(Unit)
  }

  private fun getFullPath(apkVersion: Long, latestCommit: Commit): Result<String> {
    val fileName = try {
      CommitFileName.formatFileName(
        apkVersion,
        latestCommit.commitHash
      )
    } catch (error: Throwable) {
      logger.error("Error while trying to format commit file name")
      return Result.failure(error)
    }

    val path = Paths.get(serverSettings.apksDir.absolutePath, "${fileName}.txt").toFile().absolutePath
    return Result.success(path)
  }

  class CommitsFileAlreadyExists : Exception("Commits file already exists")
}