package handler

import fs.FileSystem
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import server.ServerSettings

abstract class AbstractHandler : KoinComponent {
  private val logger = LoggerFactory.getLogger(AbstractHandler::class.java)

  protected val vertx by inject<Vertx>()
  protected val fileSystem by inject<FileSystem>()
  protected val serverSettings by inject<ServerSettings>()

  abstract suspend fun handle(routingContext: RoutingContext): Result<Unit>?

  protected fun sendResponse(routingContext: RoutingContext, message: String, status: HttpResponseStatus) {
    routingContext
      .response()
      .setStatusCode(status.code())
      .end(message)
  }
}