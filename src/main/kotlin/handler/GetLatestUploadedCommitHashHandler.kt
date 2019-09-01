package handler

import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import java.io.File

class GetLatestUploadedCommitHashHandler(
  vertx: Vertx,
  apksDir: File
) : AbstractHandler(vertx, apksDir) {
  private val logger = LoggerFactory.getLogger(GetLatestUploadedCommitHashHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext) {
    logger.info("New get latest uploaded commit hash request from ${routingContext.request().remoteAddress()}")

    // TODO

    routingContext
      .response()
      .setStatusCode(200)
      .end("a03149610f28aa241c8e06fe2614645a1a11724d")
  }
}