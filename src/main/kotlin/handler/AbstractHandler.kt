package handler

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.AsyncFile
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.logging.Logger
import io.vertx.ext.web.RoutingContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class AbstractHandler(
  protected val vertx: Vertx,
  protected val apksDir: File
) {
  abstract suspend fun handle(routingContext: RoutingContext)

  protected suspend fun getUploadedApksAsync(path: String): Result<List<String>> {
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

  protected suspend fun writeFileAsync(routingContext: RoutingContext, path: String): Result<Unit> {
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

  protected suspend fun fileExistsAsync(path: String): Result<Boolean> {
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

  protected fun sendResponse(routingContext: RoutingContext, message: String, status: HttpResponseStatus) {
    routingContext
      .response()
      .setStatusCode(status.code())
      .end(message)
  }

  protected suspend fun copyFileChunked(fileSize: Long, source: AsyncFile, destination: AsyncFile): Result<Unit> {
    val buffer = Buffer.buffer(CHUNK_SIZE.toInt())

    for (offset in 0 until fileSize step CHUNK_SIZE) {
      val chunk = if (fileSize - offset > CHUNK_SIZE) {
        CHUNK_SIZE
      } else {
        fileSize - offset
      }

      val copyResult = copySourceToDestination(source, buffer, offset, chunk, destination)
      if (copyResult.isFailure) {
        return Result.failure(copyResult.exceptionOrNull()!!)
      }
    }

    return Result.success(Unit)
  }

  private suspend fun copySourceToDestination(
    source: AsyncFile,
    buffer: Buffer,
    offset: Long,
    chunk: Long,
    destination: AsyncFile
  ): Result<Unit> {
    val readResult = readChunk(source, buffer, offset, chunk.toInt())
    if (readResult.isFailure) {
      return Result.failure(readResult.exceptionOrNull()!!)
    }

    val writeResult = writeChunk(destination, readResult.getOrNull()!!, offset)
    if (writeResult.isFailure) {
      return Result.failure(writeResult.exceptionOrNull()!!)
    }

    return Result.success(Unit)
  }

  private suspend fun readChunk(file: AsyncFile, buffer: Buffer, offset: Long, length: Int): Result<Buffer> {
    return suspendCoroutine { continuation ->
      file.read(buffer, 0, offset, length) { readAsyncResult ->
        if (readAsyncResult.succeeded()) {
          continuation.resume(Result.success(readAsyncResult.result()))
        } else {
          continuation.resume(Result.failure(readAsyncResult.cause()))
        }
      }
    }
  }

  private suspend fun writeChunk(file: AsyncFile, buffer: Buffer, offset: Long): Result<Unit> {
    return suspendCoroutine { continuation ->
      file.write(buffer, offset) { writeAsyncResult ->
        if (writeAsyncResult.succeeded()) {
          continuation.resume(Result.success(Unit))
        } else {
          continuation.resume(Result.failure(writeAsyncResult.cause()))
        }
      }
    }
  }

  companion object {
    const val CHUNK_SIZE = 4096L
  }
}