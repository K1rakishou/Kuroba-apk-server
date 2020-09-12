package handler

import data.ApkFileName
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.ApkRepository
import service.ServerStateSaverService

open class GetApkHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(GetApkHandler::class.java)
  private val apkRepository by inject<ApkRepository>()
  private val serverStateSaverService by inject<ServerStateSaverService>()

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

    val findFileResult = fileSystem.findApkFileAsync(serverSettings.apksDir.absolutePath, apkUuid)
    val apkPath = if (findFileResult.isFailure) {
      logger.error("findApkFileAsync() returned exception")

      sendResponse(
        routingContext,
        "Error while trying to find a file ${apkUuid}",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(findFileResult.exceptionOrNull()!!)
    } else {
      findFileResult.getOrNull()
    }

    if (apkPath == null) {
      logger.error("findApkFileAsync() returned null")

      sendResponse(
        routingContext,
        "Apk with uuid ${apkUuid} was not found",
        HttpResponseStatus.NOT_FOUND
      )

      return Result.failure(FileDoesNotExist(serverSettings.apksDir.absolutePath, apkUuid))
    }

    val fileSizeResult = fileSystem.getFileSize(apkPath)
    if (fileSizeResult.isFailure) {
      logger.error("Error while trying to get size of file \"$apkPath\"")

      sendResponse(
        routingContext,
        "Error while trying to get file size",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(fileSizeResult.exceptionOrNull()!!)
    }

    val increaseDownloadCountResult = apkRepository.increaseDownloadCountForApk(apkUuid)
    if (increaseDownloadCountResult.isFailure) {
      logger.error(
        "Error while trying to increase the apk download times counter",
        increaseDownloadCountResult.exceptionOrNull()!!
      )
    }

    serverStateSaverService.newSaveServerStateRequest(false)

    val apkFileName = ApkFileName.fromString(apkPath)
    val fileSize = fileSizeResult.getOrNull()!!

    routingContext
      .response()
      .putHeader("Content-Disposition", "attachment; filename=\"${serverSettings.apkName}-${apkFileName}.apk\"")
      .putHeader("Content-Length", fileSize.toString())
      .putHeader("Content-Type", "application/vnd.android.package-archive")
      .setStatusCode(HttpResponseStatus.OK.code())
      .sendFile(apkPath, 0, fileSize)

    return Result.success(Unit)
  }

  companion object {
    const val APK_NAME_PARAM = "apk"
  }
}

class FileDoesNotExist(apksPath: String, apkUuid: String)
  : Exception("Apk with uuid ${apkUuid} does not exist in directory ${apksPath}")