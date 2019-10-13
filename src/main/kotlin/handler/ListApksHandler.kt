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
import java.text.DecimalFormat
import kotlin.math.max

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

    val html = buildIndexHtmlPage(
      buildApkInfoList(apkNames),
      currentPage,
      apksCount
    )

    routingContext
      .response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(html)

    return Result.success(Unit)
  }

  private suspend fun buildApkInfoList(apkNames: List<ApkFileName>): List<ApkInfoWithSizeDiff> {
    if (apkNames.isEmpty()) {
      return emptyList()
    }

    val sortedApks = apkNames
      .map { apkName ->
        val fileSize = getFileSize(apkName.getUuid())
        check(fileSize > 0L) { "File size must be greater than zero" }

        return@map ApkInfo(apkName, fileSize)
      }
      .sortedByDescending { apkInfo -> apkInfo.apkFileName.uploadedOn }

    check(sortedApks.isNotEmpty()) { "sortedApks is empty!" }

    if (sortedApks.size < 2) {
      return listOf(
        ApkInfoWithSizeDiff(sortedApks.first(), 0f)
      )
    }

    val resultList = mutableListOf<ApkInfoWithSizeDiff>()
    var prevApkInfoWithSizeDiff = ApkInfoWithSizeDiff(
      sortedApks.first(),
      0f
    )

    resultList.add(0, prevApkInfoWithSizeDiff)

    for (index in sortedApks.size - 1 downTo 0) {
      val currentApkInfo = sortedApks[index]

      val onePercent = max(prevApkInfoWithSizeDiff.apkInfo.fileSize, currentApkInfo.fileSize) / 100f
      val diff = onePercent * (currentApkInfo.fileSize - prevApkInfoWithSizeDiff.apkInfo.fileSize)

      val currentApkInfoWithSizeDiff = ApkInfoWithSizeDiff(currentApkInfo, diff)
      resultList.add(0, currentApkInfoWithSizeDiff)

      prevApkInfoWithSizeDiff = currentApkInfoWithSizeDiff
    }

    return resultList
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

  private fun buildIndexHtmlPage(apkInfoList: List<ApkInfoWithSizeDiff>, currentPage: Int, apksCount: Int): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body { createBody(apkInfoList, currentPage, apksCount) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(apkInfoList: List<ApkInfoWithSizeDiff>, currentPage: Int, apksCount: Int) {
    style {
      +indexPageCss
    }

    div {
      id = "wrapper"

      div {
        id = "middle"

        div {
          id = "inner"

          showApks(apkInfoList, currentPage)
        }
        div {
          id = "bottom"

          div {
            id = "pages"

            showPages(apksCount, currentPage)
          }
        }
      }
    }
  }

  private fun DIV.showPages(apksCount: Int, currentPage: Int) {
    val pagesCount = apksCount / serverSettings.listApksPerPageCount
    if (pagesCount > 1) {
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

  private fun DIV.showApks(
    apkInfoList: List<ApkInfoWithSizeDiff>,
    currentPage: Int
  ) {
    require(apkInfoList.isNotEmpty()) { "apkInfoList is empty!" }

    val range = if (apkInfoList.size == 1) {
      (apkInfoList.indices)
    } else {
      (0 until apkInfoList.size - 1)
    }

    for (index in range) {
      val apkInfoWithDiffSize = apkInfoList[index]
      val apkInfo = apkInfoWithDiffSize.apkInfo
      val apkName = apkInfo.apkFileName
      val fileSize = apkInfo.fileSize

      val fullApkNameFile = apkName.getUuid() + ".apk"
      val fullCommitsFileName = apkName.getUuid() + "_commits.txt"

      p {
        a("${serverSettings.baseUrl}/apk/${fullApkNameFile}") {
          +"${serverSettings.apkName}-${fullApkNameFile}"
        }

        +buildFileSizeStr(fileSize, apkInfoWithDiffSize.sizeDiff)

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

  private fun buildFileSizeStr(fileSize: Long, sizeDiff: Float): String {
    if (fileSize < 0) {
      return " ??? B "
    }

    val sizeString = if (fileSize < 1024) {
      "$fileSize B"
    } else {
      "${fileSize / 1024} KB"
    }

    var formattedSize = String.format("%s%%", FILE_SIZE_FORMAT.format(sizeDiff))
    if (sizeDiff > 0f) {
      formattedSize = "+$formattedSize"
    }

    return String.format(" %s (%s) ", sizeString, formattedSize)
  }

  data class ApkInfoWithSizeDiff(
    val apkInfo: ApkInfo,
    val sizeDiff: Float
  )

  data class ApkInfo(
    val apkFileName: ApkFileName,
    val fileSize: Long
  )

  companion object {
    const val PAGE_PARAM = "page"

    private val FILE_SIZE_FORMAT = DecimalFormat("#######.###")

    private val UPLOAD_DATE_TIME_PRINTER = DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral(' ')
      .append(ISODateTimeFormat.hourMinuteSecond())
      .toFormatter()
  }
}