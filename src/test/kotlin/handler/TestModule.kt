package handler

import ServerSettings
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import dispatchers.DispatcherProvider
import dispatchers.TestDispatcherProvider
import fs.FileSystem
import init.MainInitializer
import io.vertx.core.Vertx
import org.koin.core.module.Module
import org.koin.dsl.module
import parser.CommitParser
import repository.ApkRepository
import repository.CommitRepository
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

      single<DispatcherProvider> { TestDispatcherProvider() }
      single {
        return@single mock<MainInitializer> {
          onBlocking { initEverything() } doReturn true
        }
      }

      single {
        ServerSettings(
          baseUrl = baseUrl,
          apksDir = apksDir,
          secretKey = secretKey,
          apkName = "kuroba-dev"
        )
      }

      // Misc
      single { CommitParser() }
      single { FileSystem() }

      // Repositories
      single { CommitRepository() }
      single { ApkRepository() }

      // Services
      single { FileHeaderChecker() }

      // Handlers
      single { UploadHandler() }
      single { GetApkHandler() }
      single { ListApksHandler() }
      single { GetLatestUploadedCommitHashHandler() }
      single { ViewCommitsHandler() }
    }
  }

}