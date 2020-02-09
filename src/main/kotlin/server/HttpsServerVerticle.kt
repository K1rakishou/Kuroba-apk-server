package server

import dispatchers.DispatcherProvider
import org.slf4j.LoggerFactory
import java.net.SocketException


class HttpsServerVerticle(dispatcherProvider: DispatcherProvider) : BaseServerVerticle(dispatcherProvider) {
  private val logger = LoggerFactory.getLogger(HttpsServerVerticle::class.java)

  override suspend fun start() {
    super.start()

    vertx
      .createHttpServer(createHttpServerOptions())
      .requestHandler(initRouter())
      .exceptionHandler { error -> logInternalNettyException(error) }
      .listen(8443)
  }

  private fun logInternalNettyException(error: Throwable) {
    if (error is SocketException && error.message?.contains("Connection reset") == true) {
      // Do not spam the logs with "Connection reset" exceptions
      logger.error("[https] Connection reset by remote peer")
      return
    }

    logger.info("[https] Unhandled exception, error message = ${error}")
  }
}

