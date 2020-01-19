package handler

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import org.koin.core.inject
import org.slf4j.LoggerFactory
import persister.ReportPersister

class DeleteReportHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(DeleteReportHandler::class.java)

  private val reportPersister by inject<ReportPersister>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New delete report request from ${routingContext.request().remoteAddress()}")

    if (!checkAuthCookie(routingContext)) {
      return null
    }

    val requestParam = routingContext.bodyAsString
    if (requestParam.isNullOrEmpty()) {
      logger.error("DeleteReport request got empty body")

      sendResponse(
        routingContext,
        "No body",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val split = requestParam.split('=')
    if (split.size != 2 || split.first() != REPORT_HASH_PARAM) {
      logger.error("Bad parameter $requestParam")

      sendResponse(
        routingContext,
        "Bad parameter report_id",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val reportHash = split.getOrNull(1)
    if (reportHash.isNullOrEmpty()) {
      logger.error("Bad parameter $reportHash")

      sendResponse(
        routingContext,
        "Bad parameter report_hash",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    if (reportHash.contains(BAD_CHARS_REGEX)) {
      logger.error("Bad characters in reportHash: ${reportHash}")

      sendResponse(
        routingContext,
        "Bad parameter report_hash",
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val deleteResult = reportPersister.deleteReport(reportHash)
    if (deleteResult.isFailure) {
      val message = "Error while trying to delete report ${reportHash}"
      logger.error(message, deleteResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return null
    }

    routingContext
      .reroute(HttpMethod.GET,"/reports")

    return Result.success(Unit)
  }

  private fun checkAuthCookie(routingContext: RoutingContext): Boolean {
    val authCookie = routingContext.cookieMap().getOrDefault(AUTH_COOKIE_KEY, null)
    if (authCookie == null) {
      val message = "No auth cookie provided"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.FORBIDDEN
      )

      return false
    }

    if (authCookie.value != serverSettings.secretKey) {
      val message = "Bad auth cookie"
      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.FORBIDDEN
      )

      return false
    }

    return true
  }

  companion object {
    const val REPORT_HASH_PARAM = "report_hash"

    private val BAD_CHARS_REGEX = "[^0-9a-fA-F]".toRegex()
  }
}