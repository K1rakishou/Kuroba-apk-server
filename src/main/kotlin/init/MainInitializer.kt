package init

import dispatchers.DispatcherProvider
import kotlinx.coroutines.withContext
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

open class MainInitializer(
  private val dispatcherProvider: DispatcherProvider
) : KoinComponent {
  private val logger = LoggerFactory.getLogger(MainInitializer::class.java)

  private val commitRepositoryInitializer by inject<CommitRepositoryInitializer>()
  private val apkRepositoryInitializer by inject<ApkRepositoryInitializer>()

  open suspend fun initEverything(): Boolean {
    return withContext(dispatcherProvider.IO()) {
      logger.info("Start initialization...")

      val initializationTime = measureTimeMillis {
        val commitsRepoInitResult = commitRepositoryInitializer.init()
        if (commitsRepoInitResult.isFailure) {
          logger.error("Couldn't init commits repo", commitsRepoInitResult.exceptionOrNull()!!)
          return@withContext false
        }

        val apkRepoInitResult = apkRepositoryInitializer.init()
        if (apkRepoInitResult.isFailure) {
          logger.error("Couldn't init apks repo", apkRepoInitResult.exceptionOrNull()!!)
          return@withContext false
        }
      }

      logger.info("Initialization ok, took ${initializationTime}ms")
      return@withContext true
    }
  }
}