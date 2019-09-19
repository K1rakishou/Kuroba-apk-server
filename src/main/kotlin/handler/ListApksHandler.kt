package handler

import data.ApkFileName
import data.Commit
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat
import org.koin.core.inject
import repository.CommitRepository
import java.io.File
import java.util.regex.Pattern

class ListApksHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(ListApksHandler::class.java)
  private val commitsRepository by inject<CommitRepository>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New list apks request from ${routingContext.request().remoteAddress()}")

    val getUploadedApksResult = fileSystem.enumerateFilesAsync(serverSettings.apksDir.absolutePath, APK_PATTERN)
    val uploadedApks = if (getUploadedApksResult.isFailure) {
      logger.error("getUploadedApksAsync() returned exception")

      sendResponse(
        routingContext,
        "Error while trying to collect uploaded apks",
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return Result.failure(getUploadedApksResult.exceptionOrNull()!!)
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

      return null
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
        return@map ApkInfo(apkName, fileSize)
      }
      .sortedByDescending { apkInfo -> apkInfo.apkFileName.uploadedOn }

    val html = buildIndexHtmlPage(apkInfoList)

    routingContext
      .response()
      .setStatusCode(200)
      .end(html)

    return Result.success(Unit)
  }

  private suspend fun getFileSize(apkUuid: String): Long {
    val findFileResult = fileSystem.findFileAsync(
      serverSettings.apksDir.absolutePath,
      Pattern.compile(".*($apkUuid)_(\\d+)\\.apk")
    )

    if (findFileResult.isFailure) {
      logger.error("Error while trying to find file with uuid ${apkUuid}")
      return -1
    }

    val foundFiles = findFileResult.getOrNull()!!
    if (foundFiles.isEmpty()) {
      logger.info("Couldn't find any files with uuid ${apkUuid}")
      return -1
    }

    if (foundFiles.size > 1) {
      logger.info("Found more than one file with uuid ${apkUuid}, files = ${foundFiles}")
      return -1
    }

    val fileSizeResult = fileSystem.getFileSize(foundFiles.first())
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

  private fun buildIndexHtmlPage(apkInfoList: List<ApkInfo>): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        body { createBody(apkInfoList) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(apkInfoList: List<ApkInfo>) {
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
          " ??? KB "
        } else {
          " ${fileSize / 1024} KB "
        }

        val sb = StringBuilder(16)
        sb.append(fileSizeStr)

        if (index == 0) {
          sb.append(" (LATEST)")
        }

        +sb.toString()

        br {
          val time = UPLOAD_DATE_TIME_PRINTER.print(apkName.uploadedOn)
          +" Uploaded on ${time}"

          br {
            a("${serverSettings.baseUrl}/commits/${fullCommitsFileName}") {
              +"[View commits]"
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
    private val APK_PATTERN = Pattern.compile(".*\\.apk")

    private val UPLOAD_DATE_TIME_PRINTER = DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral(' ')
      .append(ISODateTimeFormat.hourMinuteSecond())
      .toFormatter()
  }
}