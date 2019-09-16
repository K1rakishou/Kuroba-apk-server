package handler

import data.ApkFileName
import handler.result.GetApkHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import java.nio.file.Paths

class GetApkHandler : AbstractHandler<GetApkHandlerResult>() {
  private val logger = LoggerFactory.getLogger(GetApkHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext): GetApkHandlerResult {
    logger.info("New get apk request from ${routingContext.request().remoteAddress()}")

    val apkNameString = routingContext.pathParam(APK_NAME_PARAM)
    if (apkNameString.isNullOrEmpty()) {
      logger.error("Apk name parameter is null or empty")

      sendResponse(
        routingContext,
        "Bad apk name 1",
        HttpResponseStatus.BAD_REQUEST
      )

      return GetApkHandlerResult.BadApkName
    }

    val apkFileName = ApkFileName.fromString(apkNameString)
    if (apkFileName == null) {
      logger.error("Apk file name parameter is null, apkNameString = $apkNameString")

      sendResponse(
        routingContext,
        "Bad apk name 2",
        HttpResponseStatus.BAD_REQUEST
      )

      return GetApkHandlerResult.BadApkName
    }

    val apkFilePath = Paths.get(
      serverSettings.apksDir.absolutePath,
      apkFileName.getUuid() + ".apk"
    ).toFile().absolutePath

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

    val readFileResult = fileSystem.readFileAsync(apkFilePath)
    if (readFileResult.isFailure) {
      logger.error("Error while reading file from the disk " + readFileResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Couldn't save read file from disk",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return GetApkHandlerResult.GenericExceptionResult(readFileResult.exceptionOrNull()!!)
    }

    routingContext
      .response()
      .setChunked(true)
      .write(readFileResult.getOrNull()!!)
      .setStatusCode(200)
      .end()

    return GetApkHandlerResult.Success
  }

  companion object {
    const val APK_NAME_PARAM = "apk"
  }
}