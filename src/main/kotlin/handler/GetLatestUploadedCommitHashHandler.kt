package handler

import handler.result.GetLatestUploadedCommitHashHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import repository.CommitRepository

class GetLatestUploadedCommitHashHandler : AbstractHandler<GetLatestUploadedCommitHashHandlerResult>() {
  private val logger = LoggerFactory.getLogger(GetLatestUploadedCommitHashHandler::class.java)
  private val commitsRepository by inject<CommitRepository>()

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

    val latestCommitHash = latestCommitHashResult.getOrNull()?.hash ?: ""

    routingContext
      .response()
      .setStatusCode(200)
      .end(latestCommitHash)

    return GetLatestUploadedCommitHashHandlerResult.Success
  }
}