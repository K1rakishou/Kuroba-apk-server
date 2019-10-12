package handler

import data.ApkFileName
import data.json.ApkUuidJsonData
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import repository.ApkRepository
import service.JsonConverter

class GetLatestApkUuidHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(GetLatestApkUuidHandler::class.java)
  private val apkRepository by inject<ApkRepository>()
  private val jsonConverter by inject<JsonConverter>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New get latest apk uuid request from ${routingContext.request().remoteAddress()}")

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

    val apkUuidJsonData = ApkUuidJsonData(
      apkFileName.apkVersion,
      apkFileName.commitHash
    )

    val responseJson = jsonConverter.toJson(apkUuidJsonData)
    sendResponse(
      routingContext,
      responseJson,
      HttpResponseStatus.OK
    )

    return Result.success(Unit)
  }

}