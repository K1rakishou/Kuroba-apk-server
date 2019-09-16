package handler

import ServerSettings
import fs.FileSystem
import handler.result.HandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import org.koin.core.KoinComponent
import org.koin.core.inject

abstract class AbstractHandler<T : HandlerResult> : KoinComponent {
  protected val vertx by inject<Vertx>()
  protected val fileSystem by inject<FileSystem>()
  protected val serverSettings by inject<ServerSettings>()

  abstract suspend fun handle(routingContext: RoutingContext): T

  protected fun sendResponse(routingContext: RoutingContext, message: String, status: HttpResponseStatus) {
    routingContext
      .response()
      .setStatusCode(status.code())
      .end(message)
  }
}