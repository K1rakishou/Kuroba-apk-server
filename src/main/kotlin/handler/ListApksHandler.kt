package handler

import data.Commit
import handler.result.ListApksHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.koin.core.inject
import repository.CommitsRepository
import java.io.File

class ListApksHandler : AbstractHandler<ListApksHandlerResult>() {
  private val logger = LoggerFactory.getLogger(ListApksHandler::class.java)
  private val commitsRepository by inject<CommitsRepository>()

  override suspend fun handle(routingContext: RoutingContext): ListApksHandlerResult {
    logger.info("New list apks request from ${routingContext.request().remoteAddress()}")

    val getUploadedApksResult = fileSystem.getUploadedApksAsync(serverSettings.apksDir.absolutePath)
    val uploadedApks = if (getUploadedApksResult.isFailure) {
      logger.error("getUploadedApksAsync() returned exception ${getUploadedApksResult.exceptionOrNull()!!}")

      sendResponse(
        routingContext,
        "Error while trying to collect uploaded apks",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return ListApksHandlerResult.GenericExceptionResult(getUploadedApksResult.exceptionOrNull()!!)
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

      return ListApksHandlerResult.NoApksUploaded
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

      return ListApksHandlerResult.NoApksUploaded
    }

    val filteredCommit = filterBadCommitResults(apkNames)
    val html = buildIndexHtmlPage(apkNames, filteredCommit)

    routingContext
      .response()
      .setStatusCode(200)
      .end(html)

    return ListApksHandlerResult.Success
  }

  private suspend fun filterBadCommitResults(apkNames: List<String>): HashMap<String, List<Commit>> {
    val commits = apkNames.associateWith { apkName ->
      commitsRepository.getCommitsByApkVersion(apkName)
    }

    val filteredCommits = HashMap<String, List<Commit>>(commits.size / 2)

    for ((apkName, getCommitResult) in commits) {
      if (getCommitResult.isFailure) {
        logger.error(
          "Error while trying to get commits by apk version, " +
            "apkName = $apkName", getCommitResult.exceptionOrNull()
        )
        continue
      }

      filteredCommits[apkName] = getCommitResult.getOrNull()!!
    }

    return filteredCommits
  }

  private fun buildIndexHtmlPage(
    apkNames: List<String>,
    commits: Map<String, List<Commit>>
  ): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body { createBody(apkNames, commits) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(
    apkNames: List<String>,
    commits: Map<String, List<Commit>>
  ) {
    for (apkName in apkNames) {
      val commitsForThisApk = commits[apkName]
        ?.take(LAST_COMMITS_COUNT)
        ?.joinToString("\n", transform = { commit -> commit.asString() })

      if (commitsForThisApk == null) {
        logger.warn("Couldn't find any commits for apk ${apkName}")
      }

      p {
        a("http://94.140.116.243:8080/apk/${apkName}") {
          if (commitsForThisApk != null) {
            title = commitsForThisApk
          }

          +apkName
        }
      }
    }
  }

  companion object {
    private const val LAST_COMMITS_COUNT = 10
  }
}