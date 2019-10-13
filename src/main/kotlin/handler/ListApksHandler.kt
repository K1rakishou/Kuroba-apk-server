package handler

import data.ApkFileName
import data.Commit
import extensions.getResourceString
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat
import org.koin.core.inject
import repository.ApkRepository
import repository.CommitRepository

open class ListApksHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(ListApksHandler::class.java)
  private val commitsRepository by inject<CommitRepository>()
  private val apksRepository by inject<ApkRepository>()
  private val indexPageCss by lazy { getResourceString(ListApksHandler::class.java, "index.css") }

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New list apks request from ${routingContext.request().remoteAddress()}")

    val apksCountResult = apksRepository.getTotalApksCount()
    val apksCount = if (apksCountResult.isFailure) {
      logger.error("getTotalApksCount() returned exception")

      sendResponse(
        routingContext,
        "Error while trying to get total amount of uploaded apks",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(apksCountResult.exceptionOrNull()!!)
    } else {
      apksCountResult.getOrNull()!!
    }

    if (apksCount == 0) {
      val message = "No apks uploaded yet"
      logger.info(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.NOT_FOUND
      )

      return null
    }

    val currentPage = calculateCurrentPage(routingContext, apksCount)
    val pageOfApksResult = apksRepository.getApkListPaged(
      currentPage * serverSettings.listApksPerPageCount,
      serverSettings.listApksPerPageCount
    )

    val pageOfApks = if (pageOfApksResult.isFailure) {
      logger.error("getApkListPaged() returned exception")

      sendResponse(
        routingContext,
        "Error while trying to get a page of apks",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(apksCountResult.exceptionOrNull()!!)
    } else {
      pageOfApksResult.getOrNull()!!
    }

    val apkNames = pageOfApks
      .mapNotNull { apk -> ApkFileName.fromString(apk.apkFullPath) }

    if (apkNames.isEmpty()) {
      val message = "No apks left after trying to get apk names"
      logger.info(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.OK
      )

      return null
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

      return null
    }

    val apkInfoList = apkNames
      .map { apkName ->
        val fileSize = getFileSize(apkName.getUuid())
        check(fileSize > 0L) { "File size must be greater than zero" }

        return@map ApkInfo(apkName, fileSize)
      }
      .sortedByDescending { apkInfo -> apkInfo.apkFileName.uploadedOn }

    val html = buildIndexHtmlPage(apkInfoList, currentPage, apksCount)

    routingContext
      .response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(html)

    return Result.success(Unit)
  }

  private fun calculateCurrentPage(routingContext: RoutingContext, apksCount: Int): Int {
    val pageParam = try {
      routingContext.pathParam(PAGE_PARAM)?.toInt() ?: 0
    } catch (error: Throwable) {
      0
    }

    return pageParam.coerceIn(0, apksCount / serverSettings.listApksPerPageCount)
  }

  private suspend fun getFileSize(apkUuid: String): Long {
    val findFileResult = fileSystem.findApkFileAsync(
      serverSettings.apksDir.absolutePath,
      apkUuid
    )

    if (findFileResult.isFailure) {
      logger.error("Error while trying to find file with uuid ${apkUuid}")
      return -1
    }

    val filePath = findFileResult.getOrNull()
    if (filePath == null) {
      logger.error("Apk with uuid ${apkUuid} was not found")
      return -1
    }

    val fileSizeResult = fileSystem.getFileSize(filePath)
    if (fileSizeResult.isSuccess) {
      return fileSizeResult.getOrNull()!!
    }

    logger.error("Error while trying to get size of the file with uuid ${apkUuid}")
    return -1
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

  private fun buildIndexHtmlPage(apkInfoList: List<ApkInfo>, currentPage: Int, apksCount: Int): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body { createBody(apkInfoList, currentPage, apksCount) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(apkInfoList: List<ApkInfo>, currentPage: Int, apksCount: Int) {
    style {
      +indexPageCss
    }

    div {
      id = "wrapper"

      div {
        id = "middle"

        div {
          id = "inner"

          for ((index, apkInfo) in apkInfoList.withIndex()) {
            val apkName = apkInfo.apkFileName
            val fileSize = apkInfo.fileSize

            val fullApkNameFile = apkName.getUuid() + ".apk"
            val fullCommitsFileName = apkName.getUuid() + "_commits.txt"

            p {
              a("${serverSettings.baseUrl}/apk/${fullApkNameFile}") {
                +"${serverSettings.apkName}-${fullApkNameFile}"
              }

              val fileSizeStr = if (fileSize < 0) {
                " ??? B "
              } else {
                if (fileSize < 1024) {
                  " $fileSize B "
                } else {
                  " ${fileSize / 1024} KB "
                }
              }

              +fileSizeStr

              val time = UPLOAD_DATE_TIME_PRINTER.print(apkName.uploadedOn)
              +" Uploaded on ${time} "

              if (index == 0 && currentPage == 0) {
                +" (LATEST)"
              }

              br {
                a("${serverSettings.baseUrl}/commits/${fullCommitsFileName}") {
                  +"[View commits]"
                }
              }
            }
          }
        }
        div {
          id = "bottom"

          div {
            id = "pages"

            val pagesCount = apksCount / serverSettings.listApksPerPageCount
            if (pagesCount <= 1) {
              a("${serverSettings.baseUrl}/apks/0") {
                +"0"
              }
            } else {
              for (page in 0 until pagesCount) {
                val classes = if (page == currentPage) {
                  "active"
                } else {
                  null
                }

                a(classes = classes, href = "${serverSettings.baseUrl}/apks/$page") {
                  +page.toString()
                }
              }
            }
          }
        }
      }
    }
  }

  data class ApkInfo(
    val apkFileName: ApkFileName,
    val fileSize: Long
  )

  companion object {
    const val PAGE_PARAM = "page"

    private val UPLOAD_DATE_TIME_PRINTER = DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral(' ')
      .append(ISODateTimeFormat.hourMinuteSecond())
      .toFormatter()
  }
}