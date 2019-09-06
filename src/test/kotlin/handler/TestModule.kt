package handler

import fs.FileSystem
import io.vertx.core.Vertx
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import repository.CommitsRepository
import service.FileHeaderChecker
import java.io.File

class TestModule(
  private val vertx: Vertx,
  private val apksDir: File,
  private val secretKey: String
) {

  fun createTestModule(): Module {
    return module {
      single { vertx }
      single<File>(qualifier = named(APKS_DIR)) { apksDir }
      single<String>(qualifier = named(SECRET_KEY)) { secretKey }

      single { CommitsRepository() }
      single { FileSystem() }

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