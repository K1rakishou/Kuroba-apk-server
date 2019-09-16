import handler.GetApkHandler
import handler.GetLatestUploadedCommitHashHandler
import handler.ListApksHandler
import handler.UploadHandler
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.KoinComponent
import org.koin.core.inject

class ServerVerticle : CoroutineVerticle(), KoinComponent {
  private val logger = LoggerFactory.getLogger(ServerVerticle::class.java)

  private val serverSettings by inject<ServerSettings>()
  private val uploadHandler by inject<UploadHandler>()
  private val getApkHandler by inject<GetApkHandler>()
  private val getLatestUploadedCommitHashHandler by inject<GetLatestUploadedCommitHashHandler>()
  private val listApksHandler by inject<ListApksHandler>()

  override suspend fun start() {
    super.start()

    if (!serverSettings.apksDir.exists()) {
      throw RuntimeException("apksDir does not exist! dir = ${serverSettings.apksDir.absolutePath}")
    }

    vertx
      .createHttpServer()
      .requestHandler(initRouter())
      .exceptionHandler { error -> logger.fatal("Unhandled exception", error) }
      .listen(8080)
  }

  private fun initRouter(): Router {
    return Router.router(vertx).apply {
      post("/upload").handler(createBodyHandler())
      post("/upload").handler { routingContext ->
        handle(routingContext) {
          routingContext.response().setChunked(true)
          uploadHandler.handle(routingContext)
        }
      }
      get("/apk/:${GetApkHandler.APK_NAME_PARAM}").handler { routingContext ->
        handle(routingContext) { getApkHandler.handle(routingContext) }
      }
      get("/latest_commit_hash").handler { routingContext ->
        handle(routingContext) { getLatestUploadedCommitHashHandler.handle(routingContext) }
      }
      get("/").handler { routingContext ->
        handle(routingContext) { listApksHandler.handle(routingContext) }
      }
    }
  }

  private fun createBodyHandler(): BodyHandler {
    return BodyHandler.create()
      .setMergeFormAttributes(true)
      .setDeleteUploadedFilesOnEnd(true)
      .setBodyLimit(MAX_APK_FILE_SIZE)
  }

  private fun handle(routingContext: RoutingContext, func: suspend () -> Unit) {
    launch(Dispatchers.IO) {
      try {
        func()
      } catch (error: Exception) {
        logger.fatal("Unhandled handler exception", error)

        routingContext
          .response()
          .setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
          .end("Unknown server error")
      }
    }
  }

  companion object {
    const val SECRET_KEY_HEADER_NAME = "SECRET_KEY"
    const val APK_VERSION_HEADER_NAME = "APK_VERSION"
    const val MAX_APK_FILE_SIZE = 1024L * 1024L * 32L // 32 MB
    const val MAX_LATEST_COMMITS_FILE_SIZE = 1024L * 512L // 512 KB
  }
}