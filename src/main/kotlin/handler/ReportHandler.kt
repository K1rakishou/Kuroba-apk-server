package handler

import data.ErrorReport
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import persister.ReportPersister
import service.JsonConverter
import util.TimeUtils

class ReportHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(ReportHandler::class.java)
  private val jsonConverter by inject<JsonConverter>()
  private val reportPersister by inject<ReportPersister>()
  private val timeUtils by inject<TimeUtils>()

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New send report request from ${routingContext.request().remoteAddress()}")

    val bodyString = routingContext.bodyAsString
    if (bodyString.isNullOrEmpty()) {
      val message = "No body in request"

      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val report = try {
      ErrorReport.fromJsonData(
        jsonConverter.fromJson(bodyString),
        timeUtils.now()
      )
    } catch (error: Throwable) {
      val message = "Couldn't convert report json to an object"
      logger.error(message, error)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    if (
      report.buildFlavor.isEmpty()
      || report.versionName.isEmpty()
      || report.osInfo.isEmpty()
      || report.title.isEmpty()
      || (report.description.isEmpty() && report.logs == null)
    ) {
      val message = "One of the required parameters is empty! " +
        "(buildFlavor = ${report.buildFlavor.isEmpty() }), " +
        "versionName = ${report.versionName.isEmpty()}, " +
        "osInfo = ${report.osInfo.isEmpty()}, " +
        "title = ${report.title.isEmpty()}, " +
        "description = ${report.description.isEmpty()}, " +
        "logs = ${report.logs}"

      logger.error(message)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.BAD_REQUEST
      )

      return null
    }

    val storeResult = reportPersister.storeReport(report)
    if (storeResult.isFailure) {
      val message = "Couldn't store report in the repository"
      logger.error(message, storeResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return null
    }

    routingContext
      .response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end("Report created")

    return Result.success(Unit)
  }


}