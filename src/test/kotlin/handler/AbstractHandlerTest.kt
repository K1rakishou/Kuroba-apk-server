package handler

import db.ApkTable
import db.CommitTable
import dispatchers.DispatcherProvider
import dispatchers.TestDispatcherProvider
import fs.FileSystem
import init.ApkRepositoryInitializer
import init.CommitRepositoryInitializer
import init.MainInitializer
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.Mockito
import parser.CommitParser
import persister.ApkPersister
import persister.CommitPersister
import repository.ApkRepository
import repository.CommitRepository
import server.ServerSettings
import service.DeleteApkFullyService
import service.FileHeaderChecker
import service.OldApkRemoverService
import service.RequestThrottler
import util.TimeUtils
import java.io.File

abstract class AbstractHandlerTest {
  protected val dispatcherProvider = TestDispatcherProvider()

  protected lateinit var timeUtils: TimeUtils
  protected lateinit var mainInitializer: MainInitializer
  protected lateinit var commitRepositoryInitializer: CommitRepositoryInitializer
  protected lateinit var apkRepositoryInitializer: ApkRepositoryInitializer
  protected lateinit var serverSettings: ServerSettings
  protected lateinit var commitParser: CommitParser
  protected lateinit var fileSystem: FileSystem
  protected lateinit var commitsRepository: CommitRepository
  protected lateinit var apksRepository: ApkRepository
  protected lateinit var fileHeaderChecker: FileHeaderChecker
  protected lateinit var commitPersister: CommitPersister
  protected lateinit var apkPersister: ApkPersister
  protected lateinit var uploadHandler: UploadHandler
  protected lateinit var getApkHandler: GetApkHandler
  protected lateinit var ListApksHandler: ListApksHandler
  protected lateinit var getLatestUploadedCommitHashHandler: GetLatestUploadedCommitHashHandler
  protected lateinit var viewCommitsHandler: ViewCommitsHandler
  protected lateinit var deleteApkFullyService: DeleteApkFullyService
  protected lateinit var oldApkRemoverService: OldApkRemoverService
  protected lateinit var requestThrottler: RequestThrottler

  protected fun getModule(vertx: Vertx, database: Database, dispatcherProvider: DispatcherProvider): Module = module {
    timeUtils = Mockito.spy(TimeUtils())
    mainInitializer = Mockito.spy(MainInitializer(dispatcherProvider))
    commitRepositoryInitializer = Mockito.spy(CommitRepositoryInitializer())
    apkRepositoryInitializer = Mockito.spy(ApkRepositoryInitializer())
    serverSettings =
      Mockito.spy(
        ServerSettings(
          "http://127.0.0.1:8080",
          File("src/test/resources/test_dump"),
          File("src/test/resources/reports_test_dump"),
          "test_key",
          "kuroba-dev"
        )
      )
    commitParser = Mockito.spy(CommitParser())
    fileSystem = Mockito.spy(FileSystem())
    commitsRepository = Mockito.spy(CommitRepository(dispatcherProvider))
    apksRepository = Mockito.spy(ApkRepository(dispatcherProvider))
    fileHeaderChecker = Mockito.spy(FileHeaderChecker())
    deleteApkFullyService = Mockito.spy(DeleteApkFullyService())
    oldApkRemoverService = Mockito.spy(OldApkRemoverService(dispatcherProvider))
    commitPersister = Mockito.spy(CommitPersister())
    apkPersister = Mockito.spy(ApkPersister())
    uploadHandler = Mockito.spy(UploadHandler())
    getApkHandler = Mockito.spy(GetApkHandler())
    ListApksHandler = Mockito.spy(ListApksHandler())
    getLatestUploadedCommitHashHandler = Mockito.spy(GetLatestUploadedCommitHashHandler())
    viewCommitsHandler = Mockito.spy(ViewCommitsHandler())
    requestThrottler = Mockito.spy(RequestThrottler(serverSettings))

    single { timeUtils }
    single { vertx }
    single<DispatcherProvider> { dispatcherProvider }
    single { mainInitializer }
    single { commitRepositoryInitializer }
    single { apkRepositoryInitializer }

    single {
      serverSettings
    }

    // Database instance
    single { database }

    // Misc
    single { commitParser }
    single { fileSystem }

    // Repositories
    single { commitsRepository }
    single { apksRepository }

    // Services
    single { fileHeaderChecker }
    single { commitPersister }
    single { apkPersister }
    single { oldApkRemoverService }
    single { deleteApkFullyService }
    single { requestThrottler }

    // Handlers
    single { uploadHandler }
    single { getApkHandler }
    single { ListApksHandler }
    single { getLatestUploadedCommitHashHandler }
    single { viewCommitsHandler }
  }

  protected fun initDatabase(): Database {
    return run {
      println("Initializing DB")
      val database = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

      transaction(database) {
        SchemaUtils.create(CommitTable)
        SchemaUtils.create(ApkTable)
      }

      println("Done")
      return@run database
    }
  }

}