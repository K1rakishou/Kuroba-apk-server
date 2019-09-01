package handler

import repository.CommitsRepository
import ServerVerticle.Companion.APK_VERSION_HEADER_NAME
import ServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class UploadHandler(
  vertx: Vertx,
  apksDir: File,
  private val providedSecretKey: String,
  private val commitsRepository: CommitsRepository
) : AbstractHandler(vertx, apksDir) {
  private val logger = LoggerFactory.getLogger(UploadHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext) {
    logger.info("New uploading request from ${routingContext.request().remoteAddress()}")

    val apkVersionString = routingContext.request().getHeader(APK_VERSION_HEADER_NAME)
    if (apkVersionString.isNullOrEmpty()) {
      logger.error("apkVersionString is null or empty")

      sendResponse(
        routingContext,
        "No apk version provided",
        HttpResponseStatus.BAD_REQUEST
      )
      return
    }

    if (apkVersionString.toLongOrNull() == null) {
      logger.error("apkVersionString must be a value, actual = $apkVersionString")

      sendResponse(
        routingContext,
        "apkVersionString must be a value",
        HttpResponseStatus.BAD_REQUEST
      )
      return
    }

    val secretKey = routingContext.request().getHeader(SECRET_KEY_HEADER_NAME)
    if (secretKey != providedSecretKey) {
      logger.error("secretKey != providedSecretKey")

      sendResponse(
        routingContext,
        "Secret key is bad",
        HttpResponseStatus.BAD_REQUEST
      )
      return
    }

    val destFilePath = File(apksDir, apkVersionString).absolutePath

    val fileExistsResult = fileExistsAsync(destFilePath)
    val exists = if (fileExistsResult.isFailure) {
      logger.error("fileExistsAsync() returned exception", fileExistsResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Error while trying to figure out whether the file exists",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )
      return
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
      return
    }

    if (routingContext.fileUploads().size != EXPECTED_AMOUNT_OF_FILES) {
      logger.error("FileUploads count does not equal to $EXPECTED_AMOUNT_OF_FILES, actual = ${routingContext.fileUploads().size}")

      sendResponse(
        routingContext,
        "Must upload only one file at a time",
        HttpResponseStatus.BAD_REQUEST
      )
      return
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
      return
    }

    // TODO: transaction
    run {
      if (!storeLatestCommitsToDatabase(routingContext, apkVersionString, latestCommitsFile)) {
        return
      }

      if (!saveApkFileToDisk(apkFile, routingContext, destFilePath)) {
        return
      }
    }

    routingContext
      .response()
      .setStatusCode(200)
      .end("Uploaded apk with version code $apkVersionString")
  }

  private suspend fun storeLatestCommitsToDatabase(
    routingContext: RoutingContext,
    apkVersionString: String,
    latestCommitsFile: FileUpload
  ): Boolean {
    if (latestCommitsFile.size() > ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE) {
      logger.error("File size is too big, actual = ${latestCommitsFile.size()}")

      sendResponse(
        routingContext,
        "Commits file is too big, size = ${latestCommitsFile.size()} max = ${ServerVerticle.MAX_LATEST_COMMITS_FILE_SIZE}",
        HttpResponseStatus.BAD_REQUEST
      )
      return false
    }

    val readResult = readJsonFileAsString(latestCommitsFile.uploadedFileName())
    if (readResult.isFailure) {
      logger.error("Couldn't read latest commits file", readResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Couldn't read latest commits file",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return false
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

      return false
    }

    return true
  }

  private suspend fun saveApkFileToDisk(
    apkFile: FileUpload,
    routingContext: RoutingContext,
    destFilePath: String
  ): Boolean {
    if (apkFile.size() > ServerVerticle.MAX_APK_FILE_SIZE) {
      logger.error("File size is too big, actual = ${apkFile.size()}")

      sendResponse(
        routingContext,
        "Apk file is too big, size = ${apkFile.size()} max = ${ServerVerticle.MAX_APK_FILE_SIZE}",
        HttpResponseStatus.BAD_REQUEST
      )
      return false
    }

    val copyResult = copySourceFileToDestFile(apkFile.uploadedFileName(), destFilePath)
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
      return false
    }

    return true
  }

  private fun getFiles(fileUploads: Set<FileUpload>): Pair<FileUpload?, FileUpload?> {
    val apkFile = fileUploads.firstOrNull { file -> file.name() == UPLOADING_FILE_NAME }
    val latestCommitsFile = fileUploads.firstOrNull { file -> file.name() == LATEST_COMMITS_FILE_NAME }

    return Pair(apkFile, latestCommitsFile)
  }

  private suspend fun readJsonFileAsString(jsonFilePath: String): Result<String> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().readFile(jsonFilePath) { asyncResult ->
        if (asyncResult.succeeded()) {
          val latestCommitsString = asyncResult.result().toString(StandardCharsets.UTF_8)
          continuation.resume(Result.success(latestCommitsString))
        }
      }
    }
  }

  private suspend fun copySourceFileToDestFile(sourcePath: String, destPath: String): Result<Unit> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().copy(sourcePath, destPath) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(Unit))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  companion object {
    private const val UPLOADING_FILE_NAME = "apk"
    private const val LATEST_COMMITS_FILE_NAME = "latest_commits"
    private const val EXPECTED_AMOUNT_OF_FILES = 2
  }
}