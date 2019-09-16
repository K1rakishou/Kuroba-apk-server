package handler

import ServerSettings
import fs.FileSystem
import io.vertx.core.Vertx
import org.koin.core.module.Module
import org.koin.dsl.module
import parser.CommitParser
import repository.ApkRepository
import repository.CommitsRepository
import service.FileHeaderChecker
import java.io.File

class TestModule(
  private val vertx: Vertx,
  private val baseUrl: String,
  private val apksDir: File,
  private val secretKey: String
) {

  fun createTestModule(): Module {
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

  companion object {
    const val APKS_DIR = "apks_dir"
    const val SECRET_KEY = "secret_key"
  }

}