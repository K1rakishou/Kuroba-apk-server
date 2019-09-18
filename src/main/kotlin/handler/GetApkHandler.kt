package handler

import data.ApkFileName
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import java.util.regex.Pattern

class GetApkHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(GetApkHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New get apk request from ${routingContext.request().remoteAddress()}")

    val apkNameString = routingContext.pathParam(APK_NAME_PARAM)
    if (apkNameString.isNullOrEmpty()) {
      val message = "Apk name parameter is null or empty"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val apkUuid = ApkFileName.tryGetUuid(apkNameString)
    if (apkUuid == null) {
      val message = "Apk uuid parameter is null after trying to get the uuid from it, apkNameString = $apkNameString"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val findFileResult = fileSystem.findFileAsync(
      serverSettings.apksDir.absolutePath,
      Pattern.compile(".*($apkUuid)_(\\d+)\\.apk")
    )

    val foundFiles = if (findFileResult.isFailure) {
      logger.error("findFileAsync() returned exception")

      sendResponse(
        routingContext,
        "Error while trying to figure out whether a file exists",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(findFileResult.exceptionOrNull()!!)
    } else {
      findFileResult.getOrNull()!!
    }

    if (foundFiles.isEmpty()) {
      logger.error("File with uuid $apkUuid does not exist")

      sendResponse(
        routingContext,
        "File does not exist",
        HttpResponseStatus.NOT_FOUND
      )

      return null
    }

    if (foundFiles.size > 1) {
      throw RuntimeException("Found more than one file with the same apk uuid: $foundFiles")
    }

    val apkPath = foundFiles.first()
    val apkFileName = ApkFileName.fromString(apkPath)

    val readFileResult = fileSystem.readFileAsync(apkPath)
    if (readFileResult.isFailure) {
      logger.error("Error while reading file from the disk ")

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
      .setStatusCode(200)
      .end()

    return Result.success(Unit)
  }

  companion object {
    const val APK_NAME_PARAM = "apk"
  }
}