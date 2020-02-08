package server

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import java.net.SocketException

class HttpServerVerticle : CoroutineVerticle() {
  private val logger = LoggerFactory.getLogger(HttpServerVerticle::class.java)

  override suspend fun start() {
    super.start()

    vertx
      .createHttpServer(HttpServerOptions().setSsl(false))
      .requestHandler { req ->
        val redirectUrl = redirectUrl(req)
        if (redirectUrl == null) {
          req.response()
            .setStatusCode(HttpResponseStatus.BAD_REQUEST.code())
            .end("Bad request")
          return@requestHandler
        }

        req.response()
          .setStatusCode(301)
          .putHeader("Location", redirectUrl)
          .end()
      }
      .exceptionHandler { error -> logInternalNettyException(error) }
      .listen(8080)
  }

  private fun redirectUrl(req: HttpServerRequest): String? {
    val hostSplit = req.host().split(":")
    if (hostSplit.size != 2) {
      logger.error("Couldn't split req.host: ${req.host()}")
      return null
    }

    val host = hostSplit[0]

    return "https://" + host + ":8443" + req.path()
  }

  private fun logInternalNettyException(error: Throwable) {
    if (error is SocketException && error.message?.contains("Connection reset") == true) {
      // Do not spam the logs with "Connection reset" exceptions
      logger.error("[http] Connection reset by remote peer")
      return
    }

    logger.info("[http] Unhandled exception, error message = ${error}")
  }
}