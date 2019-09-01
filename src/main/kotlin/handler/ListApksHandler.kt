package handler

import CommitsRepository
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import java.io.File

class ListApksHandler(
  vertx: Vertx,
  apksDir: File,
  private val commitsRepository: CommitsRepository
) : AbstractHandler(vertx, apksDir) {
  private val logger = LoggerFactory.getLogger(ListApksHandler::class.java)

  override suspend fun handle(routingContext: RoutingContext) {
    logger.info("New list apks request from ${routingContext.request().remoteAddress()}")

    val getUploadedApksResult = getUploadedApksAsync(apksDir.absolutePath)
    val uploadedApks = if (getUploadedApksResult.isFailure) {
      logger.error("getUploadedApksAsync() returned exception ${getUploadedApksResult.exceptionOrNull()!!}")

      sendResponse(
        routingContext,
        "Error while trying to collect uploaded apks",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )
      return
    } else {
      getUploadedApksResult.getOrNull()!!
    }

    if (uploadedApks.isEmpty()) {
      logger.info("No apks uploaded yet")

      sendResponse(
        routingContext,
        "No apks uploaded yet",
        HttpResponseStatus.OK
      )
      return
    }

    val apkNames = uploadedApks.mapNotNull { path ->
      path.split(File.separator).lastOrNull()
    }

    if (apkNames.isEmpty()) {
      logger.info("No apks uploaded yet")

      sendResponse(
        routingContext,
        "No apks uploaded yet",
        HttpResponseStatus.OK
      )
      return
    }

    /**
     * <p><a title="
    b2978284 - k1rakishou, 26 hours ago : (#172) Merge branch 'cheese-multi-feature' into (#172)-storage-access-framework
    00eb3837 - Adamantcheese, 35 hours ago : Use enhanced for loops when possible Put some continues on the same line if short enough
    "> download link </a></p>
     * */

    val commits = apkNames.associateWith { apkName ->
      commitsRepository.getCommitsByApkVersion(apkName)
    }

    val html = buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body {
          for (apkName in apkNames) {
            val commitsForThisApk = commits[apkName]
              ?.take(LAST_COMMITS_COUNT)
              ?.joinToString("\n", transform = { commit -> commit.asString() })

            p {
              a("http://127.0.0.1:8080/apk/${apkName}") {
                if (commitsForThisApk != null) {
                  title = commitsForThisApk
                }

                +apkName
              }
            }
          }
        }
      }

      appendln()
    }

    routingContext
      .response()
      .setStatusCode(200)
      .end(html)
  }

  companion object {
    private const val LAST_COMMITS_COUNT = 10
  }
}