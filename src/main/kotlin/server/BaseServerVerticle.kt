package server

import dispatchers.DispatcherProvider
import extensions.isAuthorized
import handler.*
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.FaviconHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import service.RequestThrottler
import java.io.File

abstract class BaseServerVerticle(
  private val dispatcherProvider: DispatcherProvider
) : CoroutineVerticle(), KoinComponent {
  private val logger = LoggerFactory.getLogger(BaseServerVerticle::class.java)

  protected val serverSettings by inject<ServerSettings>()
  protected val requestThrottler by inject<RequestThrottler>()

  protected val uploadHandler by inject<UploadHandler>()
  protected val getApkHandler by inject<GetApkHandler>()
  protected val getLatestUploadedCommitHashHandler by inject<GetLatestUploadedCommitHashHandler>()
  protected val listApksHandler by inject<ListApksHandler>()
  protected val viewCommitsHandler by inject<ViewCommitsHandler>()
  protected val getLatestApkHandler by inject<GetLatestApkHandler>()
  protected val getLatestApkUuidHandler by inject<GetLatestApkUuidHandler>()
  protected val saveServerStateHandler by inject<SaveServerStateHandler>()
  protected val reportHandler by inject<ReportHandler>()
  protected val viewReportsHandler by inject<ViewReportsHandler>()
  protected val deleteReportHandler by inject<DeleteReportHandler>()

  protected fun initRouter(): Router {
    return Router.router(vertx).apply {
      post("/upload").handler(createApkUploadBodyHandler())
      post("/upload").handler { routingContext ->
        handle(true, routingContext) {
          uploadHandler.handle(routingContext)
        }
      }
      post("/report").handler(createReportBodyHandler())
      post("/report").handler { routingContext ->
        handle(true, routingContext) { reportHandler.handle(routingContext) }
      }
      post("/delete_report").handler(createDeleteReportBodyHandler())
      post("/delete_report").handler { routingContent ->
        handle(false, routingContent) { deleteReportHandler.handle(routingContent) }
      }
      get("/apk/:${GetApkHandler.APK_NAME_PARAM}").handler { routingContext ->
        handle(true, routingContext) { getApkHandler.handle(routingContext) }
      }
      get("/latest_commit_hash").handler { routingContext ->
        handle(false, routingContext) { getLatestUploadedCommitHashHandler.handle(routingContext) }
      }
      get("/commits/:${ViewCommitsHandler.COMMIT_FILE_NAME_PARAM}").handler { routingContext ->
        handle(false, routingContext) { viewCommitsHandler.handle(routingContext) }
      }
      get("/reports").handler { routingContent ->
        handle(false, routingContent) { viewReportsHandler.handle(routingContent) }
      }
      get("/").handler { routingContext ->
        routingContext
          .response()
          .setStatusCode(HttpResponseStatus.SEE_OTHER.code())
          .putHeader("Location", "/apks/0")
          .end()
      }
      get("/apks/:${ListApksHandler.PAGE_PARAM}").handler { routingContext ->
        handle(false, routingContext) { listApksHandler.handle(routingContext) }
      }
      get("/latest_apk").handler { routingContext ->
        handle(true, routingContext) { getLatestApkHandler.handle(routingContext) }
      }
      get("/latest_apk_uuid").handler { routingContext ->
        handle(false, routingContext) { getLatestApkUuidHandler.handle(routingContext) }
      }
      get("/save_state").handler { routingContext ->
        handle(false, routingContext) { saveServerStateHandler.handle(routingContext) }
      }
      route("/favicon.ico").handler(FaviconHandler.create(vertx, "favicon.ico"))
    }
  }

  private fun createDeleteReportBodyHandler(): BodyHandler {
    return BodyHandler.create()
      .setDeleteUploadedFilesOnEnd(true)
      .setBodyLimit(MAX_DELETE_REPORT_SIZE)
  }

  private fun createReportBodyHandler(): BodyHandler {
    return BodyHandler.create()
      .setDeleteUploadedFilesOnEnd(true)
      .setBodyLimit(MAX_REPORT_SIZE)
  }

  private fun createApkUploadBodyHandler(): BodyHandler {
    return BodyHandler.create()
      .setMergeFormAttributes(true)
      .setDeleteUploadedFilesOnEnd(true)
      .setBodyLimit(MAX_APK_FILE_SIZE)
  }

  private fun handle(isSlowRequest: Boolean, routingContext: RoutingContext, func: suspend () -> Result<Unit>?) {
    launch(dispatcherProvider.IO()) {
      try {
        if (throttleRequestIfNeeded(routingContext, isSlowRequest)) {
          return@launch
        }

        val result = func()
        if (result != null && result.isFailure) {
          logger.error("Handler error", result.exceptionOrNull()!!)
        }
      } catch (error: Exception) {
        routingContext
          .response()
          .setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
          .end("Unknown server error")

        if (error is FatalHandlerException) {
          // Shutdown the server whenever the FatalHandlerException is thrown

          logger.error("!!! Fatal exception !!!", error)
          vertx.close()
        } else {
          logger.error("Unhandled handler exception", error)
        }
      }
    }
  }

  /**
   * @return true if the request was throttled
   * */
  private suspend fun throttleRequestIfNeeded(
    routingContext: RoutingContext,
    isSlowRequest: Boolean
  ): Boolean {
    if (routingContext.isAuthorized(serverSettings.secretKey)) {
      return false
    }

    val remoteVisitorAddress = checkNotNull(routingContext.request().remoteAddress().host()) {
      "Remote address is null"
    }

    if (requestThrottler.shouldBeThrottled(remoteVisitorAddress, isSlowRequest)) {
      val banTime = requestThrottler.getLeftBanTime(remoteVisitorAddress)

      routingContext
        .response()
        .setStatusCode(HttpResponseStatus.TOO_MANY_REQUESTS.code())
        .end("Hold your horses! You are sending requests way too fast! Yoo will have to wait for ${banTime}ms")

      return true
    }

    return false
  }

  protected fun createHttpServerOptions(): HttpServerOptions {
    val sslCertDir = serverSettings.sslCertDir

    if (!sslCertDir.exists()) {
      throw FatalHandlerException("sslCertDir (${sslCertDir.absoluteFile}) does not exist!")
    }

    val certFile = File(sslCertDir, CERTIFICATE_FILE_NAME)
    if (!certFile.exists()) {
      throw FatalHandlerException("$CERTIFICATE_FILE_NAME does not exist in ${sslCertDir.absoluteFile}")
    }

    val keyFile = File(sslCertDir, KEY_FILE_NAME)
    if (!keyFile.exists()) {
      throw FatalHandlerException("$KEY_FILE_NAME does not exist in ${sslCertDir.absoluteFile}")
    }

    return HttpServerOptions().apply {
      setPemKeyCertOptions(
        PemKeyCertOptions()
          .setCertPath(certFile.absolutePath)
          .setKeyPath(keyFile.absolutePath)
      ).apply {
        isSsl = true
      }
    }
  }

  companion object {
    private const val CERTIFICATE_FILE_NAME = "cert.crt"
    private const val KEY_FILE_NAME = "key.pem"

    const val SECRET_KEY_HEADER_NAME = "SECRET_KEY"
    const val APK_VERSION_HEADER_NAME = "APK_VERSION"
    const val MAX_APK_FILE_SIZE = 1024L * 1024L * 32L // 32 MB
    const val MAX_REPORT_SIZE = 1024L * 1024L // 1 MB
    const val MAX_DELETE_REPORT_SIZE = 1024L // 1 KB
    const val MAX_LATEST_COMMITS_FILE_SIZE = 1024L * 512L // 512 KB
  }

}

class FatalHandlerException(message: String, cause: Throwable? = null) : Exception(message, cause)