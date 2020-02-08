package server

import dispatchers.DispatcherProvider
import handler.*
import init.MainInitializer
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.FaviconHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.slf4j.LoggerFactory
import service.RequestThrottler
import java.io.File
import java.net.SocketException


class ServerVerticle(
  private val dispatcherProvider: DispatcherProvider
) : CoroutineVerticle(), KoinComponent {
  private val logger = LoggerFactory.getLogger(ServerVerticle::class.java)

  private val serverSettings by inject<ServerSettings>()
  private val mainInitializer by inject<MainInitializer>()
  private val requestThrottler by inject<RequestThrottler>()

  private val uploadHandler by inject<UploadHandler>()
  private val getApkHandler by inject<GetApkHandler>()
  private val getLatestUploadedCommitHashHandler by inject<GetLatestUploadedCommitHashHandler>()
  private val listApksHandler by inject<ListApksHandler>()
  private val viewCommitsHandler by inject<ViewCommitsHandler>()
  private val getLatestApkHandler by inject<GetLatestApkHandler>()
  private val getLatestApkUuidHandler by inject<GetLatestApkUuidHandler>()
  private val saveServerStateHandler by inject<SaveServerStateHandler>()
  private val reportHandler by inject<ReportHandler>()
  private val viewReportsHandler by inject<ViewReportsHandler>()
  private val deleteReportHandler by inject<DeleteReportHandler>()

  override suspend fun start() {
    super.start()

    if (!serverSettings.apksDir.exists()) {
      throw FatalHandlerException("apksDir does not exist! dir = ${serverSettings.apksDir.absolutePath}")
    }

    if (!mainInitializer.initEverything()) {
      throw FatalHandlerException("Initialization error")
    }

    val httpOpts = createHttpServerOptions()

    vertx
      .createHttpServer(httpOpts)
      .requestHandler(initRouter())
      .exceptionHandler { error -> logInternalNettyException(error) }
      .listen(8080)
  }

  private fun logInternalNettyException(error: Throwable) {
    if (error is SocketException && error.message?.contains("Connection reset") == true) {
      // Do not spam the logs with "Connection reset" exceptions
      logger.error("Connection reset by remote peer")
      return
    }

    logger.info("Unhandled exception, error message = ${error}")
  }

  private fun initRouter(): Router {
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
      route("/favicon.ico").handler(FaviconHandler.create("favicon.ico"))
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

  private fun createHttpServerOptions(): HttpServerOptions {
    val sslCertDir = File(serverSettings.sslCertDirPath)

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