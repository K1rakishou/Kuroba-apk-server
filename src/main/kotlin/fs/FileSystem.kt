package fs

import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FileSystem(
  private val vertx: Vertx
) {

  suspend fun getUploadedApksAsync(path: String): Result<List<String>> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().readDir(path) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(asyncResult.result()))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun writeFileAsync(routingContext: RoutingContext, path: String): Result<Unit> {
    routingContext.response().setChunked(true)

    return suspendCoroutine { continuation ->
      vertx.fileSystem().readFile(path) { asyncResult ->
        if (asyncResult.succeeded()) {
          routingContext.response().write(asyncResult.result())
          continuation.resume(Result.success(Unit))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun fileExistsAsync(path: String): Result<Boolean> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().exists(path) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(asyncResult.result()))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }
}