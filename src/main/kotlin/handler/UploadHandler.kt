package handler

import data.Apk
import data.ApkFileName
import data.ApkFileName.Companion.APK_EXTENSION
import data.Commit
import extensions.toHex
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import persister.ApkPersister
import persister.CommitPersister
import repository.ApkRepository
import repository.CommitRepository
import repository.NoNewCommitsLeftAfterFiltering
import server.ServerVerticle
import server.ServerVerticle.Companion.APK_VERSION_HEADER_NAME
import server.ServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import service.DeleteApkFullyService
import service.FileHeaderChecker
import service.OldApkRemoverService
import util.TimeUtils
import java.nio.file.Paths

open class UploadHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(UploadHandler::class.java)
  private val commitsRepository by inject<CommitRepository>()
  private val apksRepository by inject<ApkRepository>()
  private val fileHeaderChecker by inject<FileHeaderChecker>()
  private val commitPersister by inject<CommitPersister>()
  private val apkPersister by inject<ApkPersister>()
  private val timeUtils by inject<TimeUtils>()
  private val oldApkRemover by inject<OldApkRemoverService>()
  private val deleteApkFullyService by inject<DeleteApkFullyService>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New upload apk request from ${routingContext.request().remoteAddress()}")

    val apkVersionString = routingContext.request().getHeader(APK_VERSION_HEADER_NAME)
    if (apkVersionString.isNullOrEmpty()) {
      logger.error("apkVersionString is null or empty")

      sendResponse(
        routingContext,
        "No apk version provided",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val apkVersion = apkVersionString.toLongOrNull()
    if (apkVersion == null) {
      logger.error("apkVersionString must be numeric, actual = $apkVersionString")

      sendResponse(
        routingContext,
        "Apk version must be numeric",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val secretKey = routingContext.request().getHeader(SECRET_KEY_HEADER_NAME)
    if (secretKey != serverSettings.secretKey) {
      logger.error("secretKey != providedSecretKey, received secretKey = ${secretKey}")

      sendResponse(
        routingContext,
        "Secret key is bad",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    if (routingContext.fileUploads().size != EXPECTED_AMOUNT_OF_FILES) {
      val message = "FileUploads count does not equal to $EXPECTED_AMOUNT_OF_FILES, " +
        "actual = ${routingContext.fileUploads().size}"

      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val (apkFile, commitsFile) = getFiles(routingContext.fileUploads())
    if (apkFile == null || commitsFile == null) {
      val message = "One of the multipart parameters is not present: apkFile: " +
        "${apkFile != null}, commitsFile: ${commitsFile != null}"

      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val readBytesResult = fileSystem.readFileBytesAsync(apkFile.uploadedFileName(), 0, 2)
    if (readBytesResult.isFailure) {
      val message = "Couldn't read uploaded apk's header"
      logger.error(message, readBytesResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return null
    }

    val header = readBytesResult.getOrNull()!!
    val valid = fileHeaderChecker.isValidApkFileHeader(header)
    if (!valid) {
      val message = "Uploaded apk file does not have apk file header: ${header.toHex()}"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val storeResult = storeCommits(apkVersion, commitsFile, apkFile)
    if (storeResult.isFailure) {
      val exception = storeResult.exceptionOrNull()!!
      if (exception is NoNewCommitsLeftAfterFiltering) {
        sendResponse(
          routingContext,
          "An apk with these commits has already been uploaded",
          HttpResponseStatus.BAD_REQUEST)

        return Result.success(Unit)
      }

      logger.error("Couldn't store commits")

      sendResponse(
        routingContext,
        "Couldn't store commits",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(storeResult.exceptionOrNull()!!)
    }

    oldApkRemover.onNewApkUploaded()

    routingContext
      .response()
      .setStatusCode(200)
      .end("Uploaded apk with version code $apkVersionString")

    return Result.success(Unit)
  }

  private suspend fun storeCommits(
    apkVersion: Long,
    commitsFile: FileUpload,
    apkFile: FileUpload
  ): Result<Unit> {
    val insertCommitsResult = insertNewCommits(apkVersion, commitsFile)
    if (insertCommitsResult.isFailure) {
      logger.error("Couldn't insert new commits")
      return Result.failure(insertCommitsResult.exceptionOrNull()!!)
    }

    val commits = insertCommitsResult.getOrNull()!!
    var apk: Apk? = null

    try {
      val headCommit = checkNotNull(commits.firstOrNull { commit -> commit.head }) {
        "Inserted commits do not have the head commit"
      }

      val now = timeUtils.now()
      val fileName = ApkFileName.formatFileName(
        apkVersion,
        headCommit.commitHash,
        now
      ) + APK_EXTENSION

      val fullPath = Paths.get(
        serverSettings.apksDir.absolutePath,
        fileName
      ).toFile().absolutePath

      apk = Apk(
        headCommit.apkUuid,
        apkVersion,
        fullPath,
        now
      )

      val insertApkResult = apksRepository.insertApks(listOf(apk))
      if (insertApkResult.isFailure) {
        logger.error("Couldn't insert new apk into apk repository")
        throw insertApkResult.exceptionOrNull()!!
      }

      val storeCommitsResult = commitPersister.store(apkVersion, commits)
      if (storeCommitsResult.isFailure) {
        logger.error("Couldn't persist commits on the disk")
        throw storeCommitsResult.exceptionOrNull()!!
      }

      val storeApkResult = apkPersister.store(apkFile, apkVersion, commits, now)
      if (storeApkResult.isFailure) {
        logger.error("Couldn't persist apk on the disk")
        throw storeApkResult.exceptionOrNull()!!
      }

      return Result.success(Unit)
    } catch (error: Throwable) {
      val deleteApkResult = apk?.let { apkToDelete -> deleteApkFullyService.deleteApks(listOf(apkToDelete)) }
      if (deleteApkResult != null && deleteApkResult.isFailure) {
        logger.error("Error while trying to fully delete an apk")
        return Result.failure(deleteApkResult.exceptionOrNull()!!)
      }

      return Result.failure(error)
    }
  }

  private suspend fun insertNewCommits(
    apkVersion: Long,
    latestCommitsFile: FileUpload
  ): Result<List<Commit>> {
    if (latestCommitsFile.size() > ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE) {
      val message = "Commits file is too big, size = ${latestCommitsFile.size()} " +
        "max = ${ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE}"
      logger.error(message)

      return Result.failure(CommitsFileIsTooBig(message))
    }

    val readResult = fileSystem.readFileAsStringAsync(latestCommitsFile.uploadedFileName())
    if (readResult.isFailure) {
      logger.error("Couldn't read latest commits file")
      return Result.failure(readResult.exceptionOrNull()!!)
    }

    val insertResult = commitsRepository.insertCommits(apkVersion, readResult.getOrNull()!!)
    if (insertResult.isFailure) {
      logger.error("Couldn't store new commits into the database")
      return Result.failure(insertResult.exceptionOrNull()!!)
    }

    return Result.success(insertResult.getOrNull()!!)
  }


  private fun getFiles(fileUploads: Set<FileUpload>): Pair<FileUpload?, FileUpload?> {
    val apkFile = fileUploads.firstOrNull { file -> file.name() == APK_FILE_NAME }
    val commitsFile = fileUploads.firstOrNull { file -> file.name() == COMMITS_FILE_NAME }

    return Pair(apkFile, commitsFile)
  }

  class CommitsFileIsTooBig(message: String) : Exception(message)

  companion object {
    const val APK_FILE_NAME = "apk"
    const val COMMITS_FILE_NAME = "latest_commits"
    const val EXPECTED_AMOUNT_OF_FILES = 2
  }
}