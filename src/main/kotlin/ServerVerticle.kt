import handler.GetApkHandler
import handler.GetLatestUploadedCommitHashHandler
import handler.ListApksHandler
import handler.UploadHandler
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Context
import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import repository.CommitsRepository
import java.io.File

class ServerVerticle(
  private val providedSecretKey: String,
  private val apksDir: File
) : CoroutineVerticle() {
  private val logger = LoggerFactory.getLogger(ServerVerticle::class.java)

  private lateinit var commitsRepository: CommitsRepository

  private lateinit var uploadHandler: UploadHandler
  private lateinit var getApkHandler: GetApkHandler
  private lateinit var listApksHandler: ListApksHandler
  private lateinit var getLatestUploadedCommitHashHandler: GetLatestUploadedCommitHashHandler

  override fun init(vertx: Vertx, context: Context) {
    super.init(vertx, context)

    commitsRepository = CommitsRepository()

    uploadHandler = UploadHandler(vertx, apksDir, providedSecretKey, commitsRepository)
    getApkHandler = GetApkHandler(vertx, apksDir)
    listApksHandler = ListApksHandler(vertx, apksDir, commitsRepository)
    getLatestUploadedCommitHashHandler = GetLatestUploadedCommitHashHandler(vertx, apksDir, commitsRepository)
  }

  override suspend fun start() {
    super.start()

    if (!apksDir.exists()) {
      throw RuntimeException("apksDir does not exist! dir = ${apksDir.absolutePath}")
    }

    if (providedSecretKey.length != SECRET_KEY_LENGTH) {
      throw RuntimeException("Secret key's length != $SECRET_KEY_LENGTH")
    }

    val router = Router.router(vertx)

    router.post("/upload").handler(createBodyHandler())
    router.post("/upload").handler { routingContext ->
      handle(routingContext) {
        routingContext.response().setChunked(true);
        uploadHandler.handle(routingContext)
      }
    }
    router.get("/apk/:${GetApkHandler.APK_NAME_PARAM}").handler { routingContext ->
      handle(routingContext) { getApkHandler.handle(routingContext) }
    }
    router.get("/latest_commit_hash").handler { routingContext ->
      handle(routingContext) { getLatestUploadedCommitHashHandler.handle(routingContext)}
    }
    router.get("/").handler { routingContext ->
      handle(routingContext) { listApksHandler.handle(routingContext) }
    }

    vertx
      .createHttpServer()
      .requestHandler(router)
      .exceptionHandler { logger.fatal("Unhandled exception", it) }
      .listen(8080)
  }

  private fun createBodyHandler(): BodyHandler {
    return BodyHandler.create()
      .setMergeFormAttributes(true)
      .setDeleteUploadedFilesOnEnd(true)
      .setBodyLimit(MAX_APK_FILE_SIZE)
  }

  private fun handle(routingContext: RoutingContext, func: suspend (RoutingContext) -> Unit) {
    launch(Dispatchers.IO) {
      try {
        func(routingContext)
      } catch (error: Exception) {
        logger.fatal("Unhandled handler exception", error)

        routingContext
          .response()
          .setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
          .end("Internal server error")
      }
    }
  }

  companion object {
    const val SECRET_KEY_HEADER_NAME = "SECRET_KEY"
    const val APK_VERSION_HEADER_NAME = "APK_VERSION"
    const val SECRET_KEY_LENGTH = 128
    const val MAX_APK_FILE_SIZE = 1024L * 1024L * 32L // 32 MB
    const val MAX_LATEST_COMMITS_FILE_SIZE = 1024L * 512L // 512 KB
  }
}