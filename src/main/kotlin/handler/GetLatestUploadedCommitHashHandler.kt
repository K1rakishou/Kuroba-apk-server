package handler

import fs.FileSystem
import handler.result.GetLatestUploadedCommitHashHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import repository.CommitsRepository
import java.io.File

class GetLatestUploadedCommitHashHandler(
  vertx: Vertx,
  fileSystem: FileSystem,
  apksDir: File,
  private val commitsRepository: CommitsRepository
) : AbstractHandler<GetLatestUploadedCommitHashHandlerResult>(vertx, fileSystem, apksDir) {
  private val logger = LoggerFactory.getLogger(GetLatestUploadedCommitHashHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext): GetLatestUploadedCommitHashHandlerResult {
    logger.info("New get latest uploaded commit hash request from ${routingContext.request().remoteAddress()}")

    val latestCommitHashResult = commitsRepository.getLatestCommitHash()
    if (latestCommitHashResult.isFailure) {
      logger.error("Error while trying to get latest commit hash", latestCommitHashResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        "Error while trying to get latest commit hash",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return GetLatestUploadedCommitHashHandlerResult.GenericExceptionResult(latestCommitHashResult.exceptionOrNull()!!)
    }

    val latestCommitHash = latestCommitHashResult.getOrNull() ?: ""

    routingContext
      .response()
      .setStatusCode(200)
      .end(latestCommitHash)

    return GetLatestUploadedCommitHashHandlerResult.Success
  }
}