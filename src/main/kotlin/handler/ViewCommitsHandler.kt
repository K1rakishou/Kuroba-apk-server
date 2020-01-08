package handler

import data.Commit
import data.CommitFileName
import extensions.getResourceString
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.CommitRepository
import java.util.regex.Pattern

open class ViewCommitsHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(ViewCommitsHandler::class.java)

  private val commitRepository by inject<CommitRepository>()
  private val viewCommitsPageCss by lazy { getResourceString(ViewCommitsHandler::class.java, "view_commits.css") }

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New view commits request from ${routingContext.request().remoteAddress()}")

    val commitFileNameString = routingContext.pathParam(COMMIT_FILE_NAME_PARAM)
    if (commitFileNameString.isNullOrEmpty()) {
      val message = "Commit file name parameter is null or empty"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val commitUuid = CommitFileName.tryGetUuid(commitFileNameString)
    if (commitUuid == null) {
      val message = "Commit uuid parameter is null after trying to get the uuid from it, " +
        "commitFileNameString = $commitFileNameString"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val getCommitsResult = commitRepository.getCommitsByUuid(commitUuid)
    if (getCommitsResult.isFailure) {
      logger.error("getCommitsByUuid() returned exception")

      sendResponse(
        routingContext,
        "Error while trying to get commits by uuid = ${commitUuid}",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(getCommitsResult.exceptionOrNull()!!)
    }

    val commits = getCommitsResult.getOrNull()!!
    if (commits.isEmpty()) {
      val message = "No commits found by uuid = ${commitUuid}"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.OK
      )

      return null
    }

    val html = buildHtmlPage(commits)

    routingContext
      .response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(html)

    return Result.success(Unit)
  }

  private fun buildHtmlPage(commits: List<Commit>): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body { createBody(commits) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(commits: List<Commit>) {
    style {
      +viewCommitsPageCss
    }

    div {
      id = "wrapper"

      div {
        id = "inner"

        ul("list-style-type:disc;") {
          for (commit in commits) {
            val commitSafePart = commit.serializeToStringNoDescription()
            val description = commit.description

            val commitSafePartText = convertAllIssueNumbersIntoLinks(commitSafePart)
              .let { output -> convertAllTagNumbersIntoLinks(output) }
              .let { output -> convertAllCommitHashesIntoLinks(output) }

            li {
              unsafe { +commitSafePartText }
              text("; ")
              text(description)
            }
          }
        }
      }
    }
  }

  private fun convertAllIssueNumbersIntoLinks(string: String): String {
    return string.replace(GITHUB_ISSUE_NUMBER_PATTERN) { matchResult ->
      val issueNum = if (matchResult.value.startsWith("#")) {
        matchResult.value.removePrefix("#")
      } else {
        matchResult.value
      }

      return@replace String.format(GITHUB_ISSUE_LINK_FORMAT, issueNum, issueNum)
    }
  }

  private fun convertAllTagNumbersIntoLinks(string: String): String {
    return string.replace(GITHUB_TAG_NUMBER_PATTERN) { matchResult ->
      return@replace String.format(GITHUB_TAG_LINK_FORMAT, matchResult.value, matchResult.value)
    }
  }

  private fun convertAllCommitHashesIntoLinks(string: String): String {
    return string.replace(GITHUB_COMMIT_PATTERN) { matchResult ->
      return@replace String.format(GITHUB_COMMIT_LINK_FORMAT, matchResult.value, matchResult.value)
    }
  }

  companion object {
    private const val REPO_NAME_AND_OWNER = "Adamantcheese/Kuroba"
    private const val GITHUB_ISSUE_LINK_FORMAT = "<a href=\"https://github.com/$REPO_NAME_AND_OWNER/issues/%s\">#%s</a>"
    private const val GITHUB_TAG_LINK_FORMAT = "<a href=\"https://github.com/$REPO_NAME_AND_OWNER/releases/tag/%s\">%s</a>"
    private const val GITHUB_COMMIT_LINK_FORMAT = "<a href=\"https://github.com/$REPO_NAME_AND_OWNER/commit/%s\">%s</a>"
    const val COMMIT_FILE_NAME_PARAM = "commit_file_name"
    private val GITHUB_ISSUE_NUMBER_PATTERN = Pattern.compile("(#\\d+)").toRegex()
    private val GITHUB_TAG_NUMBER_PATTERN = Pattern.compile("v(\\d+.\\d+.\\d+)").toRegex()
    private val GITHUB_COMMIT_PATTERN = Pattern.compile("(\\b[0-9a-f]{5,40}\\b)").toRegex()
  }
}