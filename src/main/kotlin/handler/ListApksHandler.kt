package handler

import data.ApkFileName
import data.Commit
import handler.result.ListApksHandlerResult
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.koin.core.inject
import repository.CommitRepository
import java.io.File
import java.util.regex.Pattern

class ListApksHandler : AbstractHandler<ListApksHandlerResult>() {
  private val logger = LoggerFactory.getLogger(ListApksHandler::class.java)
  private val commitsRepository by inject<CommitRepository>()

  override suspend fun handle(routingContext: RoutingContext): ListApksHandlerResult {
    logger.info("New list apks request from ${routingContext.request().remoteAddress()}")

    val getUploadedApksResult = fileSystem.enumerateFilesAsync(serverSettings.apksDir.absolutePath, APK_PATTERN)
    val uploadedApks = if (getUploadedApksResult.isFailure) {
      logger.error("getUploadedApksAsync() returned exception", getUploadedApksResult.exceptionOrNull()!!)

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
      val message = "No apks uploaded yet"
      logger.info(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.OK
      )

      return ListApksHandlerResult.NoApksUploaded
    }

    val apkNames = uploadedApks.mapNotNull { path ->
      val fullName = path.split(File.separator).lastOrNull()
      if (fullName == null) {
        return@mapNotNull null
      }

      return@mapNotNull ApkFileName.fromString(fullName)
    }

    if (apkNames.isEmpty()) {
      val message = "No apks left after trying to get apk names"
      logger.info(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.OK
      )

      return ListApksHandlerResult.NoApksUploaded
    }

    val filteredCommits = filterBadCommitResults(apkNames)
    if (filteredCommits.isEmpty()) {
      val message = "No apks left after filtering bad apk names"
      logger.info(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.OK
      )

      return ListApksHandlerResult.NoApksLeftAfterFiltering
    }

    val html = buildIndexHtmlPage(
      apkNames.sortedByDescending { apkName -> apkName.committedAt },
      filteredCommits)

    routingContext
      .response()
      .setStatusCode(200)
      .end(html)

    return ListApksHandlerResult.Success
  }

  private suspend fun filterBadCommitResults(apkFileNames: List<ApkFileName>): HashMap<ApkFileName, List<Commit>> {
    val commits = apkFileNames.associateWith { apkName ->
      commitsRepository.getCommitsByApkVersion(apkName)
    }

    val filteredCommits = HashMap<ApkFileName, List<Commit>>(commits.size / 2)
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
    apkFileNames: List<ApkFileName>,
    commits: Map<ApkFileName, List<Commit>>
  ): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body { createBody(apkFileNames, commits) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(
    apkFileNames: List<ApkFileName>,
    commits: Map<ApkFileName, List<Commit>>
  ) {
    for (apkName in apkFileNames) {
      val commitsForThisApk = commits[apkName]
        ?.take(LAST_COMMITS_COUNT)
        ?.joinToString("\n", transform = { commit -> commit.asString() })

      if (commitsForThisApk == null) {
        logger.warn("Couldn't find any commits for apk ${apkName}")
      }

      val fullApkName = apkName.getUuid() + ".apk"

      p {
        a("${serverSettings.baseUrl}/apk/${fullApkName}") {
          if (commitsForThisApk != null) {
            title = commitsForThisApk
          }

          +fullApkName
        }
      }
    }
  }

  companion object {
    private const val LAST_COMMITS_COUNT = 10
    private val APK_PATTERN = Pattern.compile(".*\\.apk")
  }
}