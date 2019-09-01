package handler

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import java.io.File

class GetApkHandler(
  vertx: Vertx,
  apksDir: File
) : AbstractHandler(vertx, apksDir) {
  private val logger = LoggerFactory.getLogger(GetApkHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext) {
    logger.info("New get apk request from ${routingContext.request().remoteAddress()}")

    val apkName = routingContext.pathParam(APK_NAME_PARAM)
    if (apkName.isNullOrEmpty()) {
      logger.error("Apk name parameter is null or empty")

      sendResponse(
        routingContext,
        "Bad apk name",
        HttpResponseStatus.BAD_REQUEST
      )
      return
    }

    val apkFilePath = String.format("%s${File.separator}%s", apksDir.absolutePath, apkName)

    val fileExistsResult = fileExistsAsync(apkFilePath)
    val exists = if (fileExistsResult.isFailure) {
      logger.error("fileExistsAsync() returned exception", fileExistsResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Error while trying to figure out whether a file exists",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )
      return
    } else {
      fileExistsResult.getOrNull()!!
    }

    if (!exists) {
      logger.error("File $apkFilePath does not exist")

      sendResponse(
        routingContext,
        "File does not exist",
        HttpResponseStatus.NOT_FOUND
      )
      return
    }

    try {
      writeFileAsync(routingContext, apkFilePath)
    } catch (error: Exception) {
      logger.error("Error while writing file back to the user " + error.message)

      sendResponse(
        routingContext,
        "Couldn't save file on the disk",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )
      return
    }

    routingContext
      .response()
      .setStatusCode(200)
      .end()
  }

  companion object {
    const val APK_NAME_PARAM = "apk"
  }
}