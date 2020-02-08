package server

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpServerOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.slf4j.LoggerFactory
import java.net.SocketException

class HttpServerVerticle : CoroutineVerticle(), KoinComponent {
  private val logger = LoggerFactory.getLogger(HttpServerVerticle::class.java)

  private val serverSettings by inject<ServerSettings>()

  override suspend fun start() {
    super.start()

    vertx
      .createHttpServer(HttpServerOptions().setSsl(false))
      .requestHandler { req ->
        req.response()
          .setStatusCode(HttpResponseStatus.MOVED_PERMANENTLY.code())
          .putHeader("Location", serverSettings.baseUrl + req.path())
          .end()
      }
      .exceptionHandler { error -> logInternalNettyException(error) }
      .listen(8080)
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