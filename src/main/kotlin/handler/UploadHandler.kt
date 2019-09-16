package handler

import ServerVerticle
import ServerVerticle.Companion.APK_VERSION_HEADER_NAME
import ServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import extensions.toHex
import handler.result.UploadHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import repository.CommitsRepository
import service.FileHeaderChecker
import java.io.File

class UploadHandler : AbstractHandler<UploadHandlerResult>() {
  private val logger = LoggerFactory.getLogger(UploadHandler::class.java)
  private val commitsRepository by inject<CommitsRepository>()
  private val fileHeaderChecker by inject<FileHeaderChecker>()

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

    if (apkVersionString.toLongOrNull() == null) {
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

    val destFilePath = File(serverSettings.apksDir, apkVersionString).absolutePath

    val fileExistsResult = fileSystem.fileExistsAsync(destFilePath)
    val exists = if (fileExistsResult.isFailure) {
      logger.error("fileExistsAsync() returned exception", fileExistsResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Error while trying to figure out whether the file exists",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return UploadHandlerResult.GenericExceptionResult(fileExistsResult.exceptionOrNull()!!)
    } else {
      fileExistsResult.getOrNull()!!
    }

    if (exists) {
      logger.error("File $destFilePath already exists")

      sendResponse(
        routingContext,
        "File already exists",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.FileAlreadyExists
    }

    if (routingContext.fileUploads().size != EXPECTED_AMOUNT_OF_FILES) {
      logger.error(
        "FileUploads count does not equal to $EXPECTED_AMOUNT_OF_FILES, " +
          "actual = ${routingContext.fileUploads().size}"
      )

      sendResponse(
        routingContext,
        "Must upload only one file at a time",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.BadAmountOfRequestParts
    }

    val (apkFile, latestCommitsFile) = getFiles(routingContext.fileUploads())
    if (apkFile == null || latestCommitsFile == null) {
      val message = "One of the multipart parameters is not present: apkFile present: " +
        "${apkFile != null}, latestCommitsFile present: ${latestCommitsFile != null}"

      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.RequestPartIsNotPresent
    }

    val readBytesResult = fileSystem.readBytes(apkFile.uploadedFileName(), 0, 2)
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

    // TODO: transaction
    run {
      val storeCommitsResult = storeLatestCommitsToDatabase(routingContext, apkVersionString, latestCommitsFile)
      if (storeCommitsResult != UploadHandlerResult.Success) {
        return storeCommitsResult
      }

      val saveApkResult = saveApkFileToDisk(routingContext, apkFile, destFilePath)
      if (saveApkResult != UploadHandlerResult.Success) {
        return saveApkResult
      }
    }

    routingContext
      .response()
      .setStatusCode(200)
      .end("Uploaded apk with version code $apkVersionString")

    return UploadHandlerResult.Success
  }

  private suspend fun storeLatestCommitsToDatabase(
    routingContext: RoutingContext,
    apkVersionString: String,
    latestCommitsFile: FileUpload
  ): UploadHandlerResult {
    if (latestCommitsFile.size() > ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE) {
      logger.error("File size is too big, actual = ${latestCommitsFile.size()}")

      sendResponse(
        routingContext,
        "Commits file is too big, size = ${latestCommitsFile.size()} max = ${ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE}",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.CommitsFileIsTooBig
    }

    val readResult = fileSystem.readJsonFileAsString(latestCommitsFile.uploadedFileName())
    if (readResult.isFailure) {
      logger.error("Couldn't read latest commits file", readResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Couldn't read latest commits file",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return UploadHandlerResult.GenericExceptionResult(
        readResult.exceptionOrNull()!!
      )
    }

    val latestCommits = readResult.getOrNull()!!

    val storeResult = commitsRepository.storeNewCommits(apkVersionString, latestCommits)
    if (storeResult.isFailure) {
      logger.error("Couldn't store latest commit into the database", readResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Couldn't store latest commit into the database",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return UploadHandlerResult.GenericExceptionResult(
        readResult.exceptionOrNull()!!
      )
    }

    return UploadHandlerResult.Success
  }

  private suspend fun saveApkFileToDisk(
    routingContext: RoutingContext,
    apkFile: FileUpload,
    destFilePath: String
  ): UploadHandlerResult {
    if (apkFile.size() > ServerVerticle.MAX_APK_FILE_SIZE) {
      logger.error("File size is too big, actual = ${apkFile.size()}")

      sendResponse(
        routingContext,
        "Apk file is too big, size = ${apkFile.size()} max = ${ServerVerticle.MAX_APK_FILE_SIZE}",
        HttpResponseStatus.BAD_REQUEST
      )

      return UploadHandlerResult.ApkFileIsTooBig
    }

    val copyResult = fileSystem.copySourceFileToDestFile(apkFile.uploadedFileName(), destFilePath)
    if (copyResult.isFailure) {
      logger.error(
        "Couldn't copy source file (${apkFile.uploadedFileName()}) into the destination file (${destFilePath})",
        copyResult.exceptionOrNull()!!
      )

      sendResponse(
        routingContext,
        "Error while trying to copy source file into the destination file",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return UploadHandlerResult.GenericExceptionResult(
        copyResult.exceptionOrNull()!!)
    }

    return UploadHandlerResult.Success
  }

  private fun getFiles(fileUploads: Set<FileUpload>): Pair<FileUpload?, FileUpload?> {
    val apkFile = fileUploads.firstOrNull { file -> file.name() == UPLOADING_FILE_NAME }
    val latestCommitsFile = fileUploads.firstOrNull { file -> file.name() == LATEST_COMMITS_FILE_NAME }

    return Pair(apkFile, latestCommitsFile)
  }

  companion object {
    private const val UPLOADING_FILE_NAME = "apk"
    private const val LATEST_COMMITS_FILE_NAME = "latest_commits"
    private const val EXPECTED_AMOUNT_OF_FILES = 2
  }
}