package fs

import data.ApkFileName.Companion.APK_EXTENSION
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.CopyOptions
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import server.FatalHandlerException
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

open class FileSystem : KoinComponent {
  private val vertx by inject<Vertx>()

  suspend fun createFile(path: String): Result<Unit> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().createFile(path) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(Unit))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun moveFile(oldFilePath: String, newFilePath: String, options: CopyOptions): Result<Unit> {
    val fileExistsResult = fileExistsAsync(oldFilePath)
    if (fileExistsResult.isFailure) {
      return Result.failure(fileExistsResult.exceptionOrNull()!!)
    }

    if (!fileExistsResult.getOrNull()!!) {
      return Result.failure(FileDoesNotExistException(oldFilePath))
    }

    return suspendCoroutine { continuation ->
      vertx.fileSystem().move(oldFilePath, newFilePath, options) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(Unit))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun removeFileAsync(path: String): Result<Unit> {
    val fileExistsResult = fileExistsAsync(path)
    if (fileExistsResult.isFailure) {
      return Result.failure(fileExistsResult.exceptionOrNull()!!)
    }

    if (!fileExistsResult.getOrNull()!!) {
      return Result.success(Unit)
    }

    return suspendCoroutine { continuation ->
      vertx.fileSystem().delete(path) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(Unit))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun writeFileAsync(path: String, data: Buffer): Result<Unit> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().writeFile(path, data) { asyncResult ->
        if (asyncResult.succeeded()) {
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

  /**
   * Returns null is file does not exist! Be careful when using Result.getOrNull() with null-assert
   * */
  suspend fun findApkFileAsync(apksDir: String, apkUuid: String): Result<String?> {
    val findFileResult = findFileAsync(
      apksDir,
      String.format(APK_NAME_REGEX_FORMAT, apkUuid)
    )

    if (findFileResult.isFailure) {
      return Result.failure(findFileResult.exceptionOrNull()!!)
    }

    val foundFiles = findFileResult.getOrNull()!!
    if (foundFiles.isEmpty()) {
      // Does not exist
      return Result.success(null)
    }

    if (foundFiles.size > 1) {
      throw FatalHandlerException("Found more than one file with the same apk uuid: $foundFiles")
    }

    return Result.success(foundFiles.first())
  }

  suspend fun findFileAsync(path: String, filterRegEx: String): Result<List<String>> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().readDir(path, filterRegEx) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(asyncResult.result()))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun copySourceFileToDestFileAsync(sourcePath: String, destPath: String): Result<Unit> {
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

  suspend fun readFileAsStringAsync(filePath: String): Result<String> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().readFile(filePath) { asyncResult ->
        if (asyncResult.succeeded()) {
          val latestCommitsString = asyncResult.result().toString(StandardCharsets.UTF_8)
          continuation.resume(Result.success(latestCommitsString))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun readFileBytesAsync(filePath: String, offset: Int, size: Int): Result<ByteArray> {
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

  suspend fun readFileAsync(path: String): Result<Buffer> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().readFile(path) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(asyncResult.result()))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  suspend fun enumerateFilesAsync(filePath: String, filter: Pattern? = null): Result<List<String>> {
    return suspendCoroutine { continuation ->
      if (filter == null) {
        vertx.fileSystem().readDir(filePath) { asyncResult ->
          if (asyncResult.succeeded()) {
            continuation.resume(Result.success(asyncResult.result()))
          } else {
            continuation.resume(Result.failure(asyncResult.cause()))
          }
        }
      } else {
        vertx.fileSystem().readDir(filePath, filter.pattern()) { asyncResult ->
          if (asyncResult.succeeded()) {
            continuation.resume(Result.success(asyncResult.result()))
          } else {
            continuation.resume(Result.failure(asyncResult.cause()))
          }
        }
      }
    }
  }

  suspend fun getFileSize(fullPath: String): Result<Long> {
    return suspendCoroutine { continuation ->
      vertx.fileSystem().lprops(fullPath) { asyncResult ->
        if (asyncResult.succeeded()) {
          continuation.resume(Result.success(asyncResult.result().size()))
        } else {
          continuation.resume(Result.failure(asyncResult.cause()))
        }
      }
    }
  }

  class FileDoesNotExistException(path: String) : Exception("File ${path} does not exist")

  companion object {
    private const val APK_NAME_REGEX_FORMAT = ".*(%s)_(\\d+)\\$APK_EXTENSION"
  }
}