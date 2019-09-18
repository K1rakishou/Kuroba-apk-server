package handler

import ServerVerticle
import ServerVerticle.Companion.APK_VERSION_HEADER_NAME
import ServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import data.ApkVersion
import data.Commit
import extensions.toHex
import handler.result.UploadHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import persister.ApkPersister
import persister.CommitPersister
import repository.CommitRepository
import service.FileHeaderChecker

class UploadHandler : AbstractHandler<UploadHandlerResult>() {
  private val logger = LoggerFactory.getLogger(UploadHandler::class.java)
  private val commitsRepository by inject<CommitRepository>()
  private val fileHeaderChecker by inject<FileHeaderChecker>()
  private val commitPersister by inject<CommitPersister>()
  private val apkPersister by inject<ApkPersister>()

  override suspend fun handle(routingContext: RoutingContext): UploadHandlerResult {
    logger.info("New uploading request from ${routingContext.request().remoteAddress()}")

    val apkVersionString = routingContext.request().getHeader(APK_VERSION_HEADER_NAME)
    if (apkVersionString.isNullOrEmpty()) {
      logger.error("apkVersionString is null or empty")

      sendResponse(
        routingContext,
        "No apk version provided",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.NoApkVersion
    }

    val apkVersion = apkVersionString.toLongOrNull()
    if (apkVersion == null) {
      logger.error("apkVersionString must be numeric, actual = $apkVersionString")

      sendResponse(
        routingContext,
        "Apk version must be numeric",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.ApkVersionMustBeNumeric
    }

    val secretKey = routingContext.request().getHeader(SECRET_KEY_HEADER_NAME)
    if (secretKey != serverSettings.secretKey) {
      logger.error("secretKey != providedSecretKey")

      sendResponse(
        routingContext,
        "Secret key is bad",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.SecretKeyIsBad
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

      return UploadHandlerResult.BadAmountOfRequestParts
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

      return UploadHandlerResult.RequestPartIsNotPresent
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

      return UploadHandlerResult.CouldNotReadApkFileHeader
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

      return UploadHandlerResult.NotAnApkFile
    }

    val storeResult = storeCommits(ApkVersion(apkVersion), commitsFile, apkFile)
    if (storeResult.isFailure) {
      logger.error("Couldn't store commits", storeResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Couldn't store commits",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.GenericExceptionResult(storeResult.exceptionOrNull()!!)
    }

    routingContext
      .response()
      .setStatusCode(200)
      .end("Uploaded apk with version code $apkVersionString")

    return UploadHandlerResult.Success
  }

  private suspend fun storeCommits(
    apkVersion: ApkVersion,
    commitsFile: FileUpload,
    apkFile: FileUpload
  ): Result<Unit> {
    val insertCommitsResult = insertNewCommits(apkVersion, commitsFile)
    if (insertCommitsResult.isFailure) {
      logger.error("Couldn't insert new commits", insertCommitsResult.exceptionOrNull()!!)
      return Result.failure(insertCommitsResult.exceptionOrNull()!!)
    }

    val commits = insertCommitsResult.getOrNull()!!

    try {
      val storeCommitsResult = commitPersister.store(apkVersion, commits)
      if (storeCommitsResult.isFailure) {
        logger.error("Couldn't persist commits on the disk", storeCommitsResult.exceptionOrNull()!!)
        throw storeCommitsResult.exceptionOrNull()!!
      }

      val storeApkResult = apkPersister.store(apkFile, apkVersion, commits)
      if (storeApkResult.isFailure) {
        logger.error("Couldn't persist apk on the disk", storeApkResult.exceptionOrNull()!!)
        throw storeApkResult.exceptionOrNull()!!
      }

      return Result.success(Unit)
    } catch (error: Throwable) {
      // If one of these fails that means that the data is not consistent anymore so we can't do anything in such cause
      // and we need to terminate the server
      // TODO: log errors as well
      if (commitsRepository.removeCommits(commits).isFailure) {
        throw RuntimeException("Couldn't remove inserted commits after unknown error during inserting")
      }

      if (commitPersister.remove(apkVersion, commits).isFailure) {
        throw RuntimeException("Couldn't remove commits file from disk after unknown error during storing")
      }

      if (apkPersister.remove(apkVersion, commits).isFailure) {
        throw RuntimeException("Couldn't remove apk file from disk after unknown error during storing")
      }

      return Result.failure(error)
    }
  }

  private suspend fun insertNewCommits(
    apkVersion: ApkVersion,
    latestCommitsFile: FileUpload
  ): Result<List<Commit>> {
    if (latestCommitsFile.size() > ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE) {
      val message = "Commits file is too big, size = ${latestCommitsFile.size()} " +
        "max = ${ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE}"
      logger.error(message)

      return Result.failure(CommitsFileIsTooBig())
    }

    val readResult = fileSystem.readFileAsStringAsync(latestCommitsFile.uploadedFileName())
    if (readResult.isFailure) {
      logger.error("Couldn't read latest commits file", readResult.exceptionOrNull()!!)
      return Result.failure(readResult.exceptionOrNull()!!)
    }

    val insertResult = commitsRepository.insertNewCommits(apkVersion, readResult.getOrNull()!!)
    if (insertResult.isFailure) {
      logger.error("Couldn't store new commits into the database", insertResult.exceptionOrNull()!!)
      return Result.failure(insertResult.exceptionOrNull()!!)
    }

    return Result.success(insertResult.getOrNull()!!)
  }


  private fun getFiles(fileUploads: Set<FileUpload>): Pair<FileUpload?, FileUpload?> {
    val apkFile = fileUploads.firstOrNull { file -> file.name() == UPLOADING_FILE_NAME }
    val commitsFile = fileUploads.firstOrNull { file -> file.name() == LATEST_COMMITS_FILE_NAME }

    return Pair(apkFile, commitsFile)
  }

  class CommitsFileIsTooBig : Exception("Commits file is too big")

  companion object {
    private const val UPLOADING_FILE_NAME = "apk"
    private const val LATEST_COMMITS_FILE_NAME = "latest_commits"
    private const val EXPECTED_AMOUNT_OF_FILES = 2
  }
}