package handler

import fs.FileSystem
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import java.io.File

abstract class AbstractHandler(
  protected val vertx: Vertx,
  protected val fileSystem: FileSystem,
  protected val apksDir: File
) {
  abstract suspend fun handle(routingContext: RoutingContext)

  protected fun sendResponse(routingContext: RoutingContext, message: String, status: HttpResponseStatus) {
    routingContext
      .response()
      .setStatusCode(status.code())
      .end(message)
  }

}