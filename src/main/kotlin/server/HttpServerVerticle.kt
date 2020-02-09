package server

import dispatchers.DispatcherProvider
import io.vertx.core.http.HttpServerOptions
import org.slf4j.LoggerFactory
import java.net.SocketException

class HttpServerVerticle(dispatcherProvider: DispatcherProvider) : BaseServerVerticle(dispatcherProvider) {
  private val logger = LoggerFactory.getLogger(HttpServerVerticle::class.java)

  override suspend fun start() {
    super.start()

    vertx
      .createHttpServer(HttpServerOptions().setSsl(false))
      .requestHandler(initRouter())
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