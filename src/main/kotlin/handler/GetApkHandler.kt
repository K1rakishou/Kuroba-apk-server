package handler

import handler.result.GetApkHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import java.io.File

class GetApkHandler : AbstractHandler<GetApkHandlerResult>() {
  private val logger = LoggerFactory.getLogger(GetApkHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext): GetApkHandlerResult {
    logger.info("New get apk request from ${routingContext.request().remoteAddress()}")

    val apkName = routingContext.pathParam(APK_NAME_PARAM)
    if (apkName.isNullOrEmpty()) {
      logger.error("Apk name parameter is null or empty")

      sendResponse(
        routingContext,
        "Bad apk name",
        HttpResponseStatus.BAD_REQUEST
      )

      return GetApkHandlerResult.BadApkName
    }

    val apkFilePath = String.format("%s${File.separator}%s", serverSettings.apksDir.absolutePath, apkName)

    val fileExistsResult = fileSystem.fileExistsAsync(apkFilePath)
    val exists = if (fileExistsResult.isFailure) {
      logger.error("fileExistsAsync() returned exception", fileExistsResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Error while trying to figure out whether a file exists",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return GetApkHandlerResult.GenericExceptionResult(fileExistsResult.exceptionOrNull()!!)
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

      return GetApkHandlerResult.FileDoesNotExist
    }

    val writeFileResult = fileSystem.writeFileAsync(routingContext, apkFilePath)
    if (writeFileResult.isFailure) {
      logger.error("Error while writing file back to the user " + writeFileResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Couldn't save file on the disk",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return GetApkHandlerResult.GenericExceptionResult(writeFileResult.exceptionOrNull()!!)
    }

    routingContext
      .response()
      .setStatusCode(200)
      .end()

    return GetApkHandlerResult.Success
  }

  companion object {
    const val APK_NAME_PARAM = "apk"
  }
}