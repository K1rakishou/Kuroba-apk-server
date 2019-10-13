package di

import com.squareup.moshi.Moshi
import dispatchers.DispatcherProvider
import fs.FileSystem
import handler.*
import init.ApkRepositoryInitializer
import init.CommitRepositoryInitializer
import init.MainInitializer
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import parser.CommitParser
import persister.ApkPersister
import persister.CommitPersister
import repository.ApkRepository
import repository.CommitRepository
import server.ServerSettings
import service.*
import util.TimeUtils
import java.io.File

class MainModule(
  private val vertx: Vertx,
  private val database: Database,
  private val baseUrl: String,
  private val apksDir: File,
  private val secretKey: String,
  private val dispatcherProvider: DispatcherProvider

) {

  fun createMainModule(): Module {
    // We cannot inject this because it breaks tests in classes that inherit from CoroutineScope

    return module {
      single { vertx }

      single { CommitRepositoryInitializer() }
      single { ApkRepositoryInitializer() }
      single { MainInitializer(dispatcherProvider) }
      single { Moshi.Builder().build() }

      single { TimeUtils() }

      single {
        ServerSettings(
          baseUrl = baseUrl,
          apksDir = apksDir,
          secretKey = secretKey
        )
      }

      // Database instance
      single { database }

      // Misc
      single { CommitParser() }
      single { FileSystem() }

      // Repositories
      single { CommitRepository(dispatcherProvider) }
      single { ApkRepository(dispatcherProvider) }

      // Services
      single { FileHeaderChecker() }
      single { OldApkRemoverService(dispatcherProvider) }
      single { DeleteApkFullyService() }
      single { JsonConverter(get()) }
      single { RequestThrottler(dispatcherProvider, get()) }

      // Persisters
      single { CommitPersister() }
      single { ApkPersister() }

      // Handlers
      single { UploadHandler() }
      single { GetApkHandler() }
      single { ListApksHandler() }
      single { GetLatestUploadedCommitHashHandler() }
      single { ViewCommitsHandler() }
      single { GetLatestApkHandler() }
      single { GetLatestApkUuidHandler() }
    }
  }
}