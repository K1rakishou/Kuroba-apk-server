package handler

import data.ApkFileName
import extensions.getResourceString
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.ApkRepository
import repository.ReportRepository
import service.ServerStateSaverService
import java.text.DecimalFormat
import kotlin.math.max

open class ListApksHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(ListApksHandler::class.java)
  private val apksRepository by inject<ApkRepository>()
  private val reportRepository by inject<ReportRepository>()
  private val serverStateSaverService by inject<ServerStateSaverService>()
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
    val totalPages = apksCount / serverSettings.listApksPerPageCount

    val pageOfApksResult = apksRepository.getApkListPaged(
      currentPage * serverSettings.listApksPerPageCount,
      serverSettings.listApksPerPageCount + 1
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

    val apkDownloadTimesResult = apksRepository.getApkDownloadTimes(
      apkNames.map { apkFileName -> apkFileName.getUuid() }
    )

    val apkDownloadTimes = if (apkDownloadTimesResult.isFailure) {
      logger.error("Error while trying to get apk download times", apkDownloadTimesResult.exceptionOrNull()!!)
      emptyList()
    } else {
      apkDownloadTimesResult.getOrNull()!!
    }

    val reportsCount = reportRepository.countReports().getOrNull() ?: -1

    val html = buildIndexHtmlPage(
      buildApkInfoList(apkNames, apkDownloadTimes),
      currentPage,
      apksCount,
      reportsCount,
      totalPages
    )

    serverStateSaverService.newSaveServerStateRequest(false)

    routingContext
      .response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(html)

    return Result.success(Unit)
  }

  private suspend fun buildApkInfoList(
    apkNames: List<ApkFileName>,
    apkDownloadTimes: List<Pair<String, Int>>
  ): List<FullApkInfo> {
    if (apkNames.isEmpty()) {
      return emptyList()
    }

    val downloadTimesMap = apkDownloadTimes.associate { (apkUuid, downloadTimes) ->
      Pair(apkUuid, downloadTimes)
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
      val apkInfo = sortedApks.first()
      val apkUuid = apkInfo.apkFileName.getUuid()
      val downloadedTimes = downloadTimesMap.getOrDefault(apkUuid, 0)

      return listOf(
        FullApkInfo(apkInfo, 0f, downloadedTimes)
      )
    }

    val resultList = mutableListOf<FullApkInfo>()
    val lastApk = sortedApks.last()

    var prevApkInfoWithSizeDiff = FullApkInfo(
      lastApk,
      0f,
      downloadTimesMap.getOrDefault(
        lastApk.apkFileName.getUuid(),
        0
      )
    )

    resultList.add(0, prevApkInfoWithSizeDiff)

    for (index in sortedApks.lastIndex - 1 downTo 0) {
      val currentApkInfo = sortedApks[index]

      val bigger = max(prevApkInfoWithSizeDiff.apkInfo.fileSize, currentApkInfo.fileSize)
      val diff = ((prevApkInfoWithSizeDiff.apkInfo.fileSize - currentApkInfo.fileSize) / (bigger / 100f)) * -1f

      val downloadedTimes = downloadTimesMap.getOrDefault(
        currentApkInfo.apkFileName.getUuid(),
        0
      )

      val currentApkInfoWithSizeDiff = FullApkInfo(currentApkInfo, diff, downloadedTimes)
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

  private fun buildIndexHtmlPage(
    apkInfoList: List<FullApkInfo>,
    currentPage: Int,
    apksCount: Int,
    reportsCount: Int,
    totalPages: Int
  ): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body { createBody(apkInfoList, currentPage, apksCount, reportsCount, totalPages) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(
    apkInfoList: List<FullApkInfo>,
    currentPage: Int,
    apksCount: Int,
    reportsCount: Int,
    totalPages: Int
  ) {
    style {
      +indexPageCss
    }

    div {
      id = "wrapper"

      div {
        id = "top"

        a(href = "${serverSettings.baseUrl}/reports") {
          +"[Reports ($reportsCount)]"
        }
      }
      div {
        id = "middle"

        showApks(apkInfoList, currentPage, totalPages)
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

  private fun DIV.showPages(apksCount: Int, currentPage: Int) {
    var pagesCount = apksCount / serverSettings.listApksPerPageCount
    if (pagesCount * serverSettings.listApksPerPageCount < apksCount) {
      ++pagesCount
    }

    if (pagesCount > 1) {
      for (page in 0 until pagesCount) {
        val classes = if (page == currentPage) {
          "active"
        } else {
          "inactive"
        }

        a(classes = classes, href = "${serverSettings.baseUrl}/apks/$page") {
          +page.toString()
        }
      }
    }
  }

  private fun DIV.showApks(
    apkInfoList: List<FullApkInfo>,
    currentPage: Int,
    totalPages: Int
  ) {
    require(apkInfoList.isNotEmpty()) { "apkInfoList is empty!" }

    val range = if (apkInfoList.size > serverSettings.listApksPerPageCount && currentPage != totalPages) {
      (0 until apkInfoList.size - 1)
    } else {
      (apkInfoList.indices)
    }

    table(classes = "apks") {
      for (index in range) {
        val fullApkInfo = apkInfoList[index]
        val apkInfo = fullApkInfo.apkInfo
        val apkName = apkInfo.apkFileName
        val fileSize = apkInfo.fileSize

        val fullApkNameFile = apkName.getUuid() + ".apk"
        val fullCommitsFileName = apkName.getUuid() + "_commits.txt"

        tr {
          td {
            a("${serverSettings.baseUrl}/apk/${fullApkNameFile}") {
              +"${serverSettings.apkName}-${fullApkNameFile}"
            }
          }
          td {
            +buildFileSizeStr(fileSize, fullApkInfo.sizeDiff)
          }
          td {
            val time = UPLOAD_DATE_TIME_PRINTER.print(apkName.uploadedOn)
            +" Uploaded on ${time} "

            if (index == 0 && currentPage == 0) {
              +" (LATEST)"
            }
          }
          td {
            +" Downloaded ${fullApkInfo.downloadedTimes} times"
          }
          td {
            a("${serverSettings.baseUrl}/commits/${fullCommitsFileName}") {
              +"[View commits]"
            }
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

    val formattedSize = when {
      sizeDiff > 0f && sizeDiff < 0.01f -> "+<0.01%"
      sizeDiff < 0f && sizeDiff > -0.01f -> "-<0.01%"
      else -> {
        var res = String.format("%s%%", FILE_SIZE_FORMAT.format(sizeDiff))
        if (sizeDiff > 0f && res[0] != '+') {
          res = "+$res"
        } else if (sizeDiff < 0f && res[0] != '-') {
          res = "-$res"
        }

        res
      }
    }

    return String.format(" %s (%s) ", sizeString, formattedSize)
  }

  data class FullApkInfo(
    val apkInfo: ApkInfo,
    val sizeDiff: Float,
    val downloadedTimes: Int
  )

  data class ApkInfo(
    val apkFileName: ApkFileName,
    val fileSize: Long
  )

  companion object {
    const val PAGE_PARAM = "page"

    private val FILE_SIZE_FORMAT = DecimalFormat("######.###")

    private val UPLOAD_DATE_TIME_PRINTER = DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral(' ')
      .append(ISODateTimeFormat.hourMinuteSecond())
      .appendLiteral(" UTC")
      .toFormatter()
      .withZoneUTC()
  }
}