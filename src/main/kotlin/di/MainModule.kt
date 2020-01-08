package di

import com.squareup.moshi.Moshi
import data.adapter.DateTimeJsonAdapter
import dispatchers.DispatcherProvider
import fs.FileSystem
import handler.*
import init.ApkRepositoryInitializer
import init.CommitRepositoryInitializer
import init.MainInitializer
import init.ReportRepositoryInitializer
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.joda.time.DateTime
import org.koin.core.module.Module
import org.koin.dsl.module
import parser.CommitParser
import persister.ApkPersister
import persister.CommitPersister
import persister.ReportPersister
import repository.ApkRepository
import repository.CommitRepository
import repository.ReportRepository
import server.ServerSettings
import service.*
import util.TimeUtils
import java.io.File

class MainModule(
  private val vertx: Vertx,
  private val database: Database,
  private val baseUrl: String,
  private val apksDir: File,
  private val reportsDir: File,
  private val secretKey: String,
  private val dispatcherProvider: DispatcherProvider

) {

  fun createMainModule(): Module {
    // We cannot inject this because it breaks tests in classes that inherit from CoroutineScope

    return module {
      single { vertx }

      single { CommitRepositoryInitializer() }
      single { ApkRepositoryInitializer() }
      single { ReportRepositoryInitializer() }
      single { MainInitializer(dispatcherProvider) }

      single {
        Moshi.Builder()
          .add(DateTime::class.java, DateTimeJsonAdapter())
          .build()
      }
      single { TimeUtils() }
      single {
        ServerSettings(
          baseUrl = baseUrl,
          apksDir = apksDir,
          reportsDir = reportsDir,
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
      single { ReportRepository(get(), dispatcherProvider) }

      // Services
      single { FileHeaderChecker() }
      single { OldApkRemoverService(dispatcherProvider) }
      single { DeleteApkFullyService() }
      single { JsonConverter(get()) }
      single { RequestThrottler(get()) }
      single { ServerStateSaverService(dispatcherProvider, get()) }

      // Persisters
      single { CommitPersister() }
      single { ApkPersister() }
      single { ReportPersister() }

      // Handlers
      single { UploadHandler() }
      single { GetApkHandler() }
      single { ListApksHandler() }
      single { GetLatestUploadedCommitHashHandler() }
      single { ViewCommitsHandler() }
      single { GetLatestApkHandler() }
      single { GetLatestApkUuidHandler() }
      single { SaveServerStateHandler() }
      single { ReportHandler() }
      single { ViewReportsHandler() }
      single { DeleteReportHandler() }
    }
  }
}