package di

import ServerSettings
import fs.FileSystem
import handler.GetApkHandler
import handler.GetLatestUploadedCommitHashHandler
import handler.ListApksHandler
import handler.UploadHandler
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import parser.CommitParser
import persister.ApkPersister
import persister.CommitPersister
import repository.ApkRepository
import repository.CommitsRepository
import service.FileHeaderChecker
import java.io.File

class MainModule(
  private val vertx: Vertx,
  private val database: Database,
  private val baseUrl: String,
  private val apksDir: File,
  private val secretKey: String
) {

  fun createMainModule(): Module {
    return module {
      single { vertx }

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
      single { CommitsRepository() }
      single { ApkRepository() }

      // Services
      single { FileHeaderChecker() }

      single { CommitPersister() }
      single { ApkPersister() }

      // Handlers
      single { UploadHandler() }
      single { GetApkHandler() }
      single { ListApksHandler() }
      single { GetLatestUploadedCommitHashHandler() }
    }
  }
}