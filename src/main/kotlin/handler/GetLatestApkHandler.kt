package handler

import data.ApkFileName
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.ApkRepository

class GetLatestApkHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(GetLatestApkHandler::class.java)

  private val apkRepository by inject<ApkRepository>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New get latest apk request from ${routingContext.request().remoteAddress()}")

    val getLatestApkResult = apkRepository.getLatestApk()
    if (getLatestApkResult.isFailure) {
      logger.error("getLatestApk() returned exception")

      sendResponse(
        routingContext,
        "Error while trying to get the latest apk file",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(getLatestApkResult.exceptionOrNull()!!)
    }

    val latestApk = getLatestApkResult.getOrNull()
    if (latestApk == null) {
      val message = "No apks uploaded yet"
      logger.info(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.NOT_FOUND
      )

      return null
    }

    val fileExistsResult = fileSystem.fileExistsAsync(latestApk.apkFullPath)
    if (fileExistsResult.isFailure) {
      logger.error("fileExistsAsync() returned exception")

      sendResponse(
        routingContext,
        "Error while trying figure out whether the latest apk file exists or not",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(fileExistsResult.exceptionOrNull()!!)
    }

    val apkFileName = ApkFileName.fromString(latestApk.apkFullPath)
    if (apkFileName == null) {
      val message = "Error while trying to convert apkFullPath into ApkFileName, apkFullPath = ${latestApk.apkFullPath}"
      logger.info(message)

      sendResponse(
        routingContext,
        "Error while trying to convert apkFullPath into ApkFileName",
        HttpResponseStatus.NOT_FOUND
      )

      return null
    }

    val readFileResult = fileSystem.readFileAsync(latestApk.apkFullPath)
    if (readFileResult.isFailure) {
      logger.error("Error while reading file from the disk")

      sendResponse(
        routingContext,
        "Couldn't read file from disk",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(readFileResult.exceptionOrNull()!!)
    }

    routingContext
      .response()
      .putHeader("Content-Disposition", "attachment; filename=\"${serverSettings.apkName}-${apkFileName}.apk\"")
      .setChunked(true)
      .write(readFileResult.getOrNull()!!)
      .setStatusCode(HttpResponseStatus.OK.code())
      .end()

    return Result.success(Unit)
  }
}