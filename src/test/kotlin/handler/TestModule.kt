package handler

import ServerSettings
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import dispatchers.DispatcherProvider
import dispatchers.TestDispatcherProvider
import fs.FileSystem
import init.MainInitializer
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import parser.CommitParser
import persister.ApkPersister
import persister.CommitPersister
import repository.ApkRepository
import repository.CommitRepository
import service.FileHeaderChecker
import java.io.File

class TestModule(
  private val vertx: Vertx,
  private val database: Database,
  private val baseUrl: String,
  private val apksDir: File,
  private val secretKey: String
) {

  fun createTestModule(): Module {
    return module {
      single { vertx }

      single<DispatcherProvider> { TestDispatcherProvider() }
      single {
        mock<MainInitializer> {
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

      // Database instance
      single { database }

      // Misc
      single { CommitParser() }
      single { FileSystem() }

      // Repositories
      single { CommitRepository() }
      single { ApkRepository() }

      // Services
      single { FileHeaderChecker() }

      single {
        mock<CommitPersister> {
          onBlocking { store(anyLong(), anyList()) } doReturn Result.success(Unit)
          onBlocking { remove(anyLong(), anyList()) } doReturn Result.success(Unit)
        }
      }
      single {
        mock<ApkPersister> {
          onBlocking { store(any(), anyLong(), anyList()) } doReturn Result.success(Unit)
          onBlocking { remove(anyLong(), anyList()) } doReturn Result.success(Unit)
        }
      }

      // Handlers
      single { UploadHandler() }
      single { GetApkHandler() }
      single { ListApksHandler() }
      single { GetLatestUploadedCommitHashHandler() }
      single { ViewCommitsHandler() }
    }
  }

}