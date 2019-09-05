package handler

import fs.FileSystem
import handler.result.HandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import java.io.File

abstract class AbstractHandler<T : HandlerResult>(
  protected val vertx: Vertx,
  protected val fileSystem: FileSystem,
  protected val apksDir: File
) {
  abstract suspend fun handle(routingContext: RoutingContext): T

  protected fun sendResponse(routingContext: RoutingContext, message: String, status: HttpResponseStatus) {
    routingContext
      .response()
      .setStatusCode(status.code())
      .end(message)
  }
}