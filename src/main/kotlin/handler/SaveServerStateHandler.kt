package handler

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import org.slf4j.LoggerFactory
import server.BaseServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import service.ServerStateSaverService

class SaveServerStateHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(SaveServerStateHandler::class.java)
  private val serverStateSaverService by inject<ServerStateSaverService>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New save server state request from ${routingContext.request().remoteAddress()}")

    val secretKey = routingContext.request().getHeader(SECRET_KEY_HEADER_NAME)
    if (secretKey != serverSettings.secretKey) {
      logger.error("secretKey != providedSecretKey, received secretKey = ${secretKey}")

      sendResponse(
        routingContext,
        "Secret key is bad",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    serverStateSaverService.newSaveServerStateRequest(true)

    sendResponse(
      routingContext,
      "Save server state request successfully enqueued",
      HttpResponseStatus.OK
    )

    return Result.success(Unit)

  }

}