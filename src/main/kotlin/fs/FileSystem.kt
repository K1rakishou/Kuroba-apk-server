package fs

import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FileSystem : KoinComponent {
  private val vertx by inject<Vertx>()

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

  suspend fun readJsonFileAsString(jsonFilePath: String): Result<String> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().readFile(jsonFilePath) { asyncResult ->
        if (asyncResult.succeeded()) {
          val latestCommitsString = asyncResult.result().toString(StandardCharsets.UTF_8)
          continuation.resume(Result.success(latestCommitsString))
        }
      }
    }
  }

  suspend fun copySourceFileToDestFile(sourcePath: String, destPath: String): Result<Unit> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().copy(sourcePath, destPath) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(Unit))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun readBytes(filePath: String, offset: Int, size: Int): Result<ByteArray> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().readFile(filePath) { asyncResult ->
        if (asyncResult.succeeded()) {
          val array = ByteArray(size)

          try {
            asyncResult.result().getBytes(offset, offset + size, array)
            continuation.resume(Result.success(array))
          } catch (error: IndexOutOfBoundsException) {
            continuation.resume(Result.failure(error))
          }

        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }
}