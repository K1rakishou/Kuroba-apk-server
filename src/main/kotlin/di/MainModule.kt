package di

import ServerSettings
import fs.FileSystem
import handler.GetApkHandler
import handler.GetLatestUploadedCommitHashHandler
import handler.ListApksHandler
import handler.UploadHandler
import io.vertx.core.Vertx
import org.koin.core.module.Module
import org.koin.dsl.module
import parser.CommitParser
import repository.ApkRepository
import repository.CommitsRepository
import service.FileHeaderChecker
import java.io.File

class MainModule(
  private val vertx: Vertx,
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

      // Misc
      single { CommitParser() }
      single { FileSystem() }

      // Repositories
      single { CommitsRepository(get()) }
      single { ApkRepository() }

      // Services
      single { FileHeaderChecker() }

      // Handlers
      single { UploadHandler() }
      single { GetApkHandler() }
      single { ListApksHandler() }
      single { GetLatestUploadedCommitHashHandler() }
    }
  }
}