package handler

import data.ErrorReport
import extensions.getResourceString
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.RoutingContext
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import org.koin.core.inject
import org.slf4j.LoggerFactory
import repository.ReportRepository

class ViewReportsHandler : AbstractHandler() {
  private val logger = LoggerFactory.getLogger(ViewReportsHandler::class.java)

  private val reportRepository by inject<ReportRepository>()
  private val viewReportsPageCss by lazy { getResourceString(ViewCommitsHandler::class.java, "view_reports.css") }

  override suspend fun handle(routingContext: RoutingContext): Result<Unit>? {
    logger.info("New view reports request from ${routingContext.request().remoteAddress()}")

    val getReportsResult = reportRepository.getLatestReports()
    if (getReportsResult.isFailure) {
      val message = "Couldn't get reports"
      logger.error(message, getReportsResult.exceptionOrNull()!!)

      sendResponse(
        routingContext,
        message,
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )

      return null
    }

    val reports = getReportsResult.getOrNull()!!
    val isAuthorized = isAuthorized(routingContext)

    val html = buildHtmlPage(isAuthorized, reports)

    routingContext
      .response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(html)

    return Result.success(Unit)
  }

  private fun buildHtmlPage(isAuthorized: Boolean, reports: List<ErrorReport>): String {
    return buildString {
      appendln("<!DOCTYPE html>")
      appendHTML().html {
        unsafe {
          +"<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
          +"<link rel=\"stylesheet\" href=\"https://www.w3schools.com/w3css/4/w3.css\">"
        }

        body { createBody(isAuthorized, reports) }
      }

      appendln()
    }
  }

  private fun BODY.createBody(isAuthorized: Boolean, reports: List<ErrorReport>) {
    style {
      +viewReportsPageCss
    }

    article(classes = "w3-container") {
      div {
        id = "wrapper"

        div {
          id = "inner"

          a(href = "${serverSettings.baseUrl}/") {
            +"[Main]"
          }

          +" "

          a(href = "${serverSettings.baseUrl}/reports") {
            +"[Reports]"
          }

          br()

          if (reports.isEmpty()) {
            +"No reports"
          } else {
            renderReports(isAuthorized, reports)
          }
        }
      }
    }
  }

  private fun DIV.renderReports(isAuthorized: Boolean, reports: List<ErrorReport>) {
    for ((index, report) in reports.withIndex()) {
      h2 {
        +report.title
      }

      span {
        style = "color:#A1A2A2;font-weight:bold"
        +"UUID: "
      }

      text(report.getHash())
      br()

      span {
        style = "color:#A1A2A2;font-weight:bold"
        +"Build flavor: "
      }

      text(report.buildFlavor)
      br()

      span {
        style = "color:#A1A2A2;font-weight:bold"
        +"Version name: "
      }

      text(report.versionName)
      br()

      span {
        style = "color:#A1A2A2;font-weight:bold"
        +"OS info: "
      }

      text(report.osInfo)
      br()

      span {
        style = "color:#A1A2A2;font-weight:bold"
        +"Report description: "
      }

      printDescription(report.description)

      span {
        style = "color:#A1A2A2;font-weight:bold"
        +"Logs: "
      }

      div {
        id = "logs"

        printLogs(report.logs)
      }

      span {
        style = "color:#A1A2A2;font-weight:bold"
        +"Reported at: "
      }

      text(ErrorReport.REPORT_DATE_TIME_PRINTER.print(report.reportedAt))
      br()

      if (isAuthorized) {
        form {
          action = "/delete_report"

          input {
            type = InputType.hidden
            id = "report_hash"
            name = "report_hash"
            value = report.getHash()
          }

          input {
            formMethod = InputFormMethod.post
            type = InputType.submit
            value = "Delete report"
          }
        }
      }

      if (index != reports.lastIndex) {
        br()
        hr()
      }
    }
  }

  private fun DIV.printDescription(description: String) {
    for (line in description.split("\n")) {
      text(line)
      br()
    }
  }

  private fun DIV.printLogs(logs: String?) {
    if (logs == null) {
      text("No logs attached")
      br()
      return
    }

    for (line in logs.split("\n")) {
      text(line)
      br()
    }
  }

  private fun isAuthorized(routingContext: RoutingContext): Boolean {
    val authCookie = routingContext.cookieMap().getOrDefault(AUTH_COOKIE_KEY, null)
    if (authCookie == null) {
      return false
    }

    if (authCookie.value != serverSettings.secretKey) {
      return false
    }

    return true
  }
}