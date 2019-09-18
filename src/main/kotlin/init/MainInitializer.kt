package init

import dispatchers.DispatcherProvider
import io.vertx.core.logging.LoggerFactory
import kotlinx.coroutines.withContext
import org.koin.core.KoinComponent
import org.koin.core.inject
import kotlin.system.measureTimeMillis

open class MainInitializer : KoinComponent {
  private val logger = LoggerFactory.getLogger(MainInitializer::class.java)

  private val commitRepositoryInitializer by inject<CommitRepositoryInitializer>()
  private val dispatcherProvider by inject<DispatcherProvider>()

  open suspend fun initEverything(): Boolean {
    return withContext(dispatcherProvider.IO()) {
      logger.info("Start initialization...")

      val initializationTime = measureTimeMillis {
        val commitsRepoInitResult = commitRepositoryInitializer.init()
        if (commitsRepoInitResult.isFailure) {
          logger.error("Couldn't init commits repo", commitsRepoInitResult.exceptionOrNull()!!)
          return@withContext false
        }
      }

      logger.info("Initialization ok, took ${initializationTime}ms")
      return@withContext true
    }
  }
}