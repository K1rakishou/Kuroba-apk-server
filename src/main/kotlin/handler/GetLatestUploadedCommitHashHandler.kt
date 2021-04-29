package handler

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import repository.CommitRepository

open class GetLatestUploadedCommitHashHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(GetLatestUploadedCommitHashHandler::class.java)
  private val commitsRepository by inject<CommitRepository>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New get latest uploaded commit hash request from ${routingContext.request().remoteAddress()}")

    val latestCommitHashResult = commitsRepository.getLatestCommitHash()
    if (latestCommitHashResult.isFailure) {
      logger.error("Error while trying to get latest commit hash")

      sendResponse(
        routingContext,
        "Error while trying to get latest commit hash",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(latestCommitHashResult.exceptionOrNull()!!)
    }

    val latestCommitHash = latestCommitHashResult.getOrNull() ?: ""

    routingContext
      .response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(latestCommitHash)

    return Result.success(Unit)
  }
}