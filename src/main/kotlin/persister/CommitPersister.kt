package persister

import ServerSettings
import data.ApkVersion
import data.Commit
import data.CommitFileName
import fs.FileSystem
import io.vertx.core.buffer.Buffer
import io.vertx.core.logging.LoggerFactory
import org.koin.core.KoinComponent
import org.koin.core.inject
import parser.CommitParser
import java.nio.file.Paths

class CommitPersister : KoinComponent {
  private val logger = LoggerFactory.getLogger(CommitPersister::class.java)

  private val commitParser by inject<CommitParser>()
  private val serverSettings by inject<ServerSettings>()
  private val fileSystem by inject<FileSystem>()

  suspend fun store(apkVersion: ApkVersion, parsedCommits: List<Commit>): Result<Unit> {
    require(parsedCommits.isNotEmpty())

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

  suspend fun remove(apkVersion: ApkVersion, parsedCommits: List<Commit>): Result<Unit> {
    require(parsedCommits.isNotEmpty())

    val latestCommit = parsedCommits.first()
    val getFullPathResult = getFullPath(apkVersion, latestCommit)

    if (getFullPathResult.isFailure) {
      return Result.failure(getFullPathResult.exceptionOrNull()!!)
    }

    return fileSystem.removeFileAsync(getFullPathResult.getOrNull()!!)
  }

  private fun getFullPath(apkVersion: ApkVersion, latestCommit: Commit): Result<String> {
    val fileName = try {
      CommitFileName.formatUuid(
        apkVersion,
        latestCommit.commitHash
      )
    } catch (error: Throwable) {
      return Result.failure(error)
    }

    val path = Paths.get(serverSettings.apksDir.absolutePath, "${fileName}.txt").toFile().absolutePath
    return Result.success(path)
  }

  class CommitsFileAlreadyExists : Exception("Commits file already exists")
}