package handler

import ServerSettings
import dispatchers.DispatcherProvider
import dispatchers.TestDispatcherProvider
import fs.FileSystem
import init.ApkRepositoryInitializer
import init.CommitRepositoryInitializer
import init.MainInitializer
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.Mockito
import parser.CommitParser
import persister.ApkPersister
import persister.CommitPersister
import repository.ApkRepository
import repository.CommitRepository
import service.FileHeaderChecker
import util.TimeUtils
import java.io.File

abstract class AbstractHandlerTest {
  protected lateinit var timeUtils: TimeUtils
  protected lateinit var mainInitializer: MainInitializer
  protected lateinit var commitRepositoryInitializer: CommitRepositoryInitializer
  protected lateinit var apkRepositoryInitializer: ApkRepositoryInitializer
  protected lateinit var serverSettings: ServerSettings
  protected lateinit var commitParser: CommitParser
  protected lateinit var fileSystem: FileSystem
  protected lateinit var commitRepository: CommitRepository
  protected lateinit var apkRepository: ApkRepository
  protected lateinit var fileHeaderChecker: FileHeaderChecker
  protected lateinit var commitPersister: CommitPersister
  protected lateinit var apkPersister: ApkPersister
  protected lateinit var uploadHandler: UploadHandler
  protected lateinit var getApkHandler: GetApkHandler
  protected lateinit var ListApksHandler: ListApksHandler
  protected lateinit var getLatestUploadedCommitHashHandler: GetLatestUploadedCommitHashHandler
  protected lateinit var viewCommitsHandler: ViewCommitsHandler

  protected fun getModule(vertx: Vertx, database: Database): Module = module {
    timeUtils = Mockito.spy(TimeUtils())
    mainInitializer = Mockito.spy(MainInitializer())
    commitRepositoryInitializer = Mockito.spy(CommitRepositoryInitializer())
    apkRepositoryInitializer = Mockito.spy(ApkRepositoryInitializer())
    serverSettings =
      Mockito.spy(ServerSettings("http://127.0.0.1:8080", File("src/test/resources/test_dump"), "test_key", "kuroba-dev"))
    commitParser = Mockito.spy(CommitParser())
    fileSystem = Mockito.spy(FileSystem())
    commitRepository = Mockito.spy(CommitRepository())
    apkRepository = Mockito.spy(ApkRepository())
    fileHeaderChecker = Mockito.spy(FileHeaderChecker())
    commitPersister = Mockito.spy(CommitPersister())
    apkPersister = Mockito.spy(ApkPersister())
    uploadHandler = Mockito.spy(UploadHandler())
    getApkHandler = Mockito.spy(GetApkHandler())
    ListApksHandler = Mockito.spy(ListApksHandler())
    getLatestUploadedCommitHashHandler = Mockito.spy(GetLatestUploadedCommitHashHandler())
    viewCommitsHandler = Mockito.spy(ViewCommitsHandler())

    single { timeUtils }
    single { vertx }
    single<DispatcherProvider> { TestDispatcherProvider() }
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
    single { commitRepository }
    single { apkRepository }

    // Services
    single { fileHeaderChecker }
    single { commitPersister }
    single { apkPersister }

    // Handlers
    single { uploadHandler }
    single { getApkHandler }
    single { ListApksHandler }
    single { getLatestUploadedCommitHashHandler }
    single { viewCommitsHandler }
  }

}